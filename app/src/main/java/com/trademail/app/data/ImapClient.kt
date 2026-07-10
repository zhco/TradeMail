package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.*
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.security.Security
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class ImapClient {

    companion object {
        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

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

    suspend fun fetchInbox(account: Account, page: Int = 0, pageSize: Int = 20): Result<List<Email>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(fetch(account, page, pageSize))
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message}\n${sw.toString().take(800)}"))
            }
        }

    private fun fetch(account: Account, page: Int, pageSize: Int): List<Email> {
        // Bouncy Castle TLS — bypass Conscrypt
        val sslCtx = SSLContext.getInstance("TLS", "BC")
        sslCtx.init(null, null, null)
        val socket = sslCtx.socketFactory.createSocket() as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.soTimeout = 30000
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(account.imapHost, 993), 15000)
        socket.startHandshake()

        val input: InputStream = socket.inputStream
        val output: OutputStream = socket.outputStream
        val state = ImapState()

        try {
            val greeting = readLine(input)
            if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH"))
                throw IOException("Bad greeting: ${greeting.take(80)}")

            sendCmd(output, "LOGIN ${account.email} ${account.password}", state)
            val login = readResp(input, state)
            if (!login.contains("OK"))
                throw IOException("Login failed: ${login.take(200)}")

            sendCmd(output, "SELECT INBOX", state)
            val sel = readResp(input, state)
            if (!sel.contains("OK"))
                throw IOException("Select failed: ${sel.take(200)}")

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
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }
}
