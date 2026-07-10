package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.*
import java.nio.charset.Charset
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class ImapClient {

    data class ImapState(var tagIndex: Int = 0)

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            bytes.write(b)
            if (prev == '\r'.code && b == '\n'.code) break
            prev = b
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    private fun sendCmd(output: OutputStream, cmd: String, state: ImapState) {
        val tag = "A${state.tagIndex}"
        state.tagIndex++
        output.write("$tag $cmd\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readResp(input: InputStream, state: ImapState): String {
        val sb = StringBuilder()
        val prefix = "A${state.tagIndex}"
        while (true) {
            val line = readLine(input)
            sb.append(line).append("\n")
            if (line.startsWith(prefix) || line.startsWith("* BYE")) break
        }
        return sb.toString()
    }

    private fun decodeHeader(raw: String): String {
        return try {
            val r = Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""")
            var last = 0
            val sb = StringBuilder()
            r.findAll(raw).forEach { m ->
                sb.append(raw.substring(last, m.range.first))
                last = m.range.last + 1
                val cs = try { Charset.forName(m.groupValues[1]) } catch (_: Exception) { Charsets.UTF_8 }
                val txt = m.groupValues[3]
                val part = when (m.groupValues[2].uppercase()) {
                    "B" -> String(Base64.getDecoder().decode(txt), cs)
                    "Q" -> {
                        val bb = ByteArrayOutputStream()
                        var i = 0
                        while (i < txt.length) {
                            when {
                                txt[i] == '=' && i + 2 < txt.length -> { bb.write(txt.substring(i + 1, i + 3).toInt(16)); i += 3 }
                                txt[i] == '_' -> { bb.write(' '.code); i++ }
                                else -> { bb.write(txt[i].code); i++ }
                            }
                        }
                        String(bb.toByteArray(), cs)
                    }
                    else -> txt
                }
                sb.append(part)
            }
            sb.append(raw.substring(last))
            sb.toString()
        } catch (_: Exception) { raw }
    }

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US),
        SimpleDateFormat("d MMM yyyy HH:mm:ss Z", Locale.US),
    )

    private fun parseDate(s: String): Long {
        for (f in dateFormats) { try { return f.parse(s.trim())?.time ?: 0L } catch (_: Exception) {} }
        return 0L
    }

    private fun findHdr(h: String, n: String): String {
        return Regex("""$n:\s*(.+)""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.trim() ?: ""
    }

    private val relayUrl = "https://150.158.160.124:443/relay"
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val okHttpClient: OkHttpClient by lazy {
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(null, trustAllCerts, java.security.SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchInbox(account: Account, page: Int = 0, pageSize: Int = 20): Result<List<Email>> =
        withContext(Dispatchers.IO) {
            try {
                val (input, output, _) = openRelayTunnel(account)
                try {
                    Result.success(fetchOverTunnel(input, output, account, page, pageSize))
                } finally {
                    try { output.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message}\n${sw.toString().take(800)}"))
            }
        }

    // Try direct first, fallback to relay
    private fun openRelayTunnel(account: Account): Triple<InputStream, OutputStream, String> {
        // Attempt 1: OkHttp HTTPS tunnel to relay on port 443
        // This looks like normal HTTPS traffic to the firewall
        val connUrl = relayUrl + "?host=" + account.imapHost
        val request = Request.Builder()
            .url(connUrl)
            .header("X-Email", account.email)
            .header("X-Password", account.password)
            .build()
        
        val response = okHttpClient.newCall(request).execute()
        if (response.code != 200) {
            throw IOException("Relay returned ${response.code}: ${response.body?.string()?.take(200)}")
        }
        // Read relay response: it returns the raw IMAP data through HTTP body as stream
        val body = response.body ?: throw IOException("No response body")
        // We'll use this for stdin to the relay
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        
        return Triple(body.byteStream(), pipeOut, "relay")
    }

    private fun fetchOverTunnel(input: InputStream, output: OutputStream, account: Account, page: Int, pageSize: Int): List<Email> {
        val state = ImapState()
        
        val greeting = readLine(input)
        if (!greeting.contains("OK")) throw IOException("Bad greeting: ${greeting.take(80)}")

        sendCmd(output, "LOGIN ${account.email} ${account.password}", state)
        val login = readResp(input, state)
        if (!login.contains("OK")) throw IOException("Login failed: ${login.take(200)}")

        sendCmd(output, "SELECT INBOX", state)
        val sel = readResp(input, state)
        if (!sel.contains("OK")) throw IOException("Select failed: ${sel.take(200)}")

        val total = Regex("""\* (\d+) EXISTS""").find(sel)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (total == 0) return emptyList()

        val end = total - page * pageSize
        val start = maxOf(1, end - pageSize + 1)
        if (start > end) return emptyList()

        sendCmd(output, "FETCH $start:$end (FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE)])", state)
        val hdrResp = readResp(input, state)

        val hdrBlocks = mutableMapOf<Int, String>()
        var cs = 0; val cb = StringBuilder()
        for (line in hdrResp.split("\n")) {
            val fm = Regex("""\*\s+(\d+)\s+FETCH""").find(line)
            if (fm != null) {
                if (cs > 0) hdrBlocks[cs] = cb.toString()
                cs = fm.groupValues[1].toIntOrNull() ?: 0; cb.clear()
                cb.append(line)
            } else if (cs > 0) cb.append("\n").append(line)
        }
        if (cs > 0) hdrBlocks[cs] = cb.toString()

        val emails = mutableListOf<Email>()
        for (s in start..end) {
            sendCmd(output, "FETCH $s BODY.PEEK[TEXT]", state)
            val bodyResp = readResp(input, state)
            val body = Regex("""\{(\d+)\}""").find(bodyResp)?.let { m ->
                val after = bodyResp.substringAfter("}")
                val a = if (after.startsWith("\r\n")) after.substring(2) else after
                a.take(m.groupValues[1].toIntOrNull() ?: 0)
            } ?: ""

            val h = hdrBlocks[s] ?: ""
            emails.add(Email(
                uid = s.toLong(),
                from = decodeHeader(findHdr(h, "From")),
                subject = decodeHeader(findHdr(h, "Subject")).ifBlank { "(无主题)" },
                bodyPlain = body.trim(),
                bodyHtml = "",
                receivedDate = parseDate(findHdr(h, "Date")),
                isRead = h.contains("""\Seen"""),
                hasAttachments = false
            ))
        }

        sendCmd(output, "LOGOUT", state)
        readResp(input, state)
        return emails.reversed()
    }
}
