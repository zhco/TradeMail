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

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(trustAllSsl.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class ImapState(var tagIndex: Int = 0)

    private fun readResponse(reader: BufferedReader, state: ImapState): String {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            val tagPrefix = "A${state.tagIndex} "
            if (line.startsWith(tagPrefix)) break
            if (line.startsWith("* BYE")) break
        }
        return sb.toString()
    }

    private fun sendCommand(writer: BufferedWriter, reader: BufferedReader, cmd: String, state: ImapState): String {
        val tag = "A${state.tagIndex}"
        state.tagIndex++
        writer.write("$tag $cmd\r\n")
        writer.flush()
        return readResponse(reader, state)
    }

    private fun decodeHeader(raw: String): String {
        return try {
            val decoded = StringBuilder()
            var remaining = raw
            val regex = Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""")
            var lastEnd = 0
            regex.findAll(raw).forEach { match ->
                decoded.append(raw.substring(lastEnd, match.range.first))
                lastEnd = match.range.last + 1
                val charset = match.groupValues[1]
                val encoding = match.groupValues[2].uppercase()
                val text = match.groupValues[3]
                val decodedPart = when (encoding) {
                    "B" -> String(Base64.getDecoder().decode(text), charset(charset))
                    "Q" -> decodeQuotedPrintable(text, charset)
                    else -> text
                }
                decoded.append(decodedPart)
            }
            decoded.append(raw.substring(lastEnd))
            decoded.toString()
        } catch (e: Exception) { raw }
    }

    private fun decodeQuotedPrintable(text: String, charset: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '=' && i + 2 < text.length -> {
                    bytes.write(text.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                text[i] == '_' -> { bytes.write(' '.code); i++ }
                else -> { bytes.write(text[i].code); i++ }
            }
        }
        return String(bytes.toByteArray(), Charset.forName(charset))
    }

    private fun charset(name: String) = try { Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US),
        SimpleDateFormat("d MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    )

    private fun parseDate(dateStr: String): Long {
        for (fmt in dateFormats) {
            try { return fmt.parse(dateStr.trim())?.time ?: 0L } catch (_: Exception) {}
        }
        return 0L
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
        val socketFactory = trustAllSsl.socketFactory
        val socket = socketFactory.createSocket(account.imapHost, account.imapPort) as SSLSocket
        socket.soTimeout = 30000
        socket.startHandshake()
        socket.soTimeout = 30000

        val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        val state = ImapState()

        try {
            val greeting = reader.readLine() ?: throw IOException("No IMAP greeting")
            if (!greeting.startsWith("* OK")) throw IOException("Bad greeting: $greeting")

            val loginCmd = "LOGIN ${account.email} ${account.password}"
            val loginResp = sendCommand(writer, reader, loginCmd, state)
            if (!loginResp.contains("OK")) throw IOException("Login failed")

            val selectResp = sendCommand(writer, reader, "SELECT INBOX", state)
            if (!selectResp.contains("OK")) throw IOException("SELECT INBOX failed")

            val countRegex = Regex("""\* (\d+) EXISTS""")
            val totalMessages = countRegex.find(selectResp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (totalMessages == 0) return emptyList()

            val end = totalMessages - page * pageSize
            val start = maxOf(1, end - pageSize + 1)
            if (start > end) return emptyList()

            // Fetch all headers+flags in one command
            val headerResp = sendCommand(writer, reader,
                "FETCH $start:$end (FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE)])", state)

            val emails = mutableListOf<Email>()
            // Extract per-message header blocks
            val headerBlocks = extractFetchBlocks(headerResp, start, end)

            for (seqNum in start..end) {
                val hdr = headerBlocks[seqNum] ?: ""
                val bodyResp = sendCommand(writer, reader, "FETCH $seqNum BODY.PEEK[TEXT]", state)
                val body = extractFetchBlock(bodyResp, seqNum)

                val from = findHeaderValue(hdr, "From")
                val subject = findHeaderValue(hdr, "Subject")
                val date = findHeaderValue(hdr, "Date")
                val seen = hdr.contains("""\Seen""")

                if (subject.isNotEmpty() || from.isNotEmpty()) {
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
            }

            sendCommand(writer, reader, "LOGOUT", state)
            return emails.reversed()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun findHeaderValue(headers: String, name: String): String {
        val regex = Regex("""$name:\s*(.+)""", RegexOption.IGNORE_CASE)
        return regex.find(headers)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractFetchBlocks(response: String, start: Int, end: Int): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val lines = response.split("\n")
        var currentSeq: Int? = null
        val currentBlock = StringBuilder()
        var bytesLeft = 0

        for (line in lines) {
            val fetchMatch = Regex("""\*\s+(\d+)\s+FETCH""").find(line)
            if (fetchMatch != null) {
                // Save previous block
                currentSeq?.let { map[it] = currentBlock.toString().trim() }
                currentSeq = fetchMatch.groupValues[1].toIntOrNull()
                currentBlock.clear()
                currentBlock.append(line).append("\n")
                bytesLeft = 0
            } else if (currentSeq != null) {
                if (line.startsWith(")") || line.startsWith("A")) {
                    map[currentSeq!!] = currentBlock.toString().trim()
                    currentSeq = null
                } else {
                    currentBlock.append(line).append("\n")
                }
            }
        }
        currentSeq?.let { map[it] = currentBlock.toString().trim() }
        return map
    }

    private fun extractFetchBlock(response: String, seqNum: Int): String {
        val lines = response.split("\n")
        val sb = StringBuilder()
        var inBlock = false
        var byteCount = 0
        var lineCount = 0
        for (line in lines) {
            if (!inBlock && line.contains("* $seqNum FETCH")) {
                inBlock = true
                // Check for literal size {n}
                val sizeMatch = Regex("""\{(\d+)\}""").find(line)
                byteCount = sizeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                continue
            }
            if (inBlock) {
                if (line.trim() == ")" || line.startsWith("A")) {
                    inBlock = false
                } else {
                    sb.append(line).append("\n")
                }
            }
        }
        return sb.toString()
    }
}
