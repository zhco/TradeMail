package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.*
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class ImapClient {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val trustAllSsl by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        ctx
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

    private fun readFull(input: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(buf, offset, size - offset)
            if (n < 0) break
            offset += n
        }
        return buf
    }

    private fun sendCmd(output: OutputStream, cmd: String, state: ImapState) {
        val tag = "A${state.tagIndex}"
        state.tagIndex++
        val line = "$tag $cmd\r\n"
        output.write(line.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readResponse(input: InputStream, state: ImapState): String {
        val sb = StringBuilder()
        val tagPrefix = "A${state.tagIndex}"
        while (true) {
            val line = readLine(input)
            sb.append(line).append("\n")
            if (line.startsWith(tagPrefix)) break
            if (line.startsWith("* BYE")) break
        }
        return sb.toString()
    }

    private fun decodeHeader(raw: String): String {
        return try {
            val regex = Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""")
            var lastEnd = 0
            val decoded = StringBuilder()
            regex.findAll(raw).forEach { match ->
                decoded.append(raw.substring(lastEnd, match.range.first))
                lastEnd = match.range.last + 1
                val charset = match.groupValues[1]
                val encoding = match.groupValues[2].uppercase()
                val text = match.groupValues[3]
                val cs = try { Charset.forName(charset) } catch (_: Exception) { Charsets.UTF_8 }
                val decodedPart = when (encoding) {
                    "B" -> String(Base64.getDecoder().decode(text), cs)
                    "Q" -> {
                        val bbuf = ByteArrayOutputStream()
                        var i = 0
                        while (i < text.length) {
                            when {
                                text[i] == '=' && i + 2 < text.length -> { bbuf.write(text.substring(i + 1, i + 3).toInt(16)); i += 3 }
                                text[i] == '_' -> { bbuf.write(' '.code); i++ }
                                else -> { bbuf.write(text[i].code); i++ }
                            }
                        }
                        String(bbuf.toByteArray(), cs)
                    }
                    else -> text
                }
                decoded.append(decodedPart)
            }
            decoded.append(raw.substring(lastEnd))
            decoded.toString()
        } catch (e: Exception) { raw }
    }

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US),
        SimpleDateFormat("d MMM yyyy HH:mm:ss Z", Locale.US),
    )

    private fun parseDate(dateStr: String): Long {
        for (fmt in dateFormats) {
            try { return fmt.parse(dateStr.trim())?.time ?: 0L } catch (_: Exception) {}
        }
        return 0L
    }

    private fun findHeader(headers: String, name: String): String {
        val r = Regex("""$name:\s*(.+)""", RegexOption.IGNORE_CASE)
        return r.find(headers)?.groupValues?.get(1)?.trim() ?: ""
    }

    suspend fun fetchInbox(account: Account, page: Int = 0, pageSize: Int = 20): Result<List<Email>> =
        withContext(Dispatchers.IO) {
            try {
                val emails = fetchViaSocket(account, page, pageSize)
                Result.success(emails)
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message}\n${sw.toString().take(600)}"))
            }
        }

    private fun fetchViaSocket(account: Account, page: Int, pageSize: Int): List<Email> {
        // Connect plain TCP first, then wrap in SSL to avoid Conscrypt timing issues
        val plainSocket = java.net.Socket(account.imapHost, account.imapPort)
        plainSocket.soTimeout = 30000
        val socket = trustAllSsl.socketFactory.createSocket(
            plainSocket, account.imapHost, account.imapPort, true
        ) as SSLSocket
        socket.soTimeout = 30000
        // Handshake on first I/O — don't call startHandshake()
        val input = socket.inputStream
        val output = socket.outputStream
        val state = ImapState()

        try {
            // Read greeting
            val greeting = readLine(input)
            if (!greeting.startsWith("* OK")) throw IOException("Bad greeting: $greeting")

            // Login
            sendCmd(output, "LOGIN ${account.email} ${account.password}", state)
            val loginResp = readResponse(input, state)
            if (!loginResp.contains("OK")) throw IOException("Login failed: ${loginResp.take(200)}")

            // Select INBOX
            sendCmd(output, "SELECT INBOX", state)
            val selectResp = readResponse(input, state)
            if (!selectResp.contains("OK")) throw IOException("SELECT failed: ${selectResp.take(200)}")

            val totalMessages = Regex("""\* (\d+) EXISTS""").find(selectResp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (totalMessages == 0) return emptyList()

            val end = totalMessages - page * pageSize
            val start = maxOf(1, end - pageSize + 1)
            if (start > end) return emptyList()

            // Fetch headers
            sendCmd(output, "FETCH $start:$end (FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE)])", state)
            val headerResp = readResponse(input, state)

            val headerBlocks = mutableMapOf<Int, String>()
            var curSeq = 0
            val curBlock = StringBuilder()
            for (line in headerResp.split("\n")) {
                val fm = Regex("""\*\s+(\d+)\s+FETCH""").find(line)
                if (fm != null) {
                    if (curSeq > 0) headerBlocks[curSeq] = curBlock.toString()
                    curSeq = fm.groupValues[1].toIntOrNull() ?: 0
                    curBlock.clear()
                    curBlock.append(line)
                } else if (curSeq > 0) {
                    curBlock.append("\n").append(line)
                }
            }
            if (curSeq > 0) headerBlocks[curSeq] = curBlock.toString()

            val emails = mutableListOf<Email>()
            for (seqNum in start..end) {
                sendCmd(output, "FETCH $seqNum BODY.PEEK[TEXT]", state)
                val bodyResp = readResponse(input, state)
                val body = extractLiteral(bodyResp)

                val hdr = headerBlocks[seqNum] ?: ""
                val from = findHeader(hdr, "From")
                val subject = findHeader(hdr, "Subject")
                val date = findHeader(hdr, "Date")
                val seen = hdr.contains("""\Seen""")

                emails.add(Email(
                    uid = seqNum.toLong(),
                    from = decodeHeader(from),
                    subject = decodeHeader(subject).ifBlank { "(无主题)" },
                    bodyPlain = body.trim(),
                    bodyHtml = "",
                    receivedDate = parseDate(date),
                    isRead = seen,
                    hasAttachments = false
                ))
            }

            sendCmd(output, "LOGOUT", state)
            readResponse(input, state)
            return emails.reversed()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun extractLiteral(resp: String): String {
        val m = Regex("""\{(\d+)\}""").find(resp) ?: return ""
        val size = m.groupValues[1].toIntOrNull() ?: return ""
        val after = resp.substringAfter("}")
        // Skip \r\n after literal size
        val content = if (after.startsWith("\r\n")) after.substring(2) else if (after.startsWith("\n")) after.substring(1) else after
        return content.take(size)
    }
}
