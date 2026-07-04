package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class ImapClient {

    companion object {
        private val TAG_REGEX = Regex("\* (\d+) FETCH.*?BODY\[TEXT\] \{(\d+)\}", RegexOption.IGNORE_CASE)
        private val HEADER_REGEX = Regex("\* (\d+) FETCH.*?BODY\[HEADER\.FIELDS \(.*?\)\] \{(\d+)\}", RegexOption.IGNORE_CASE)
        private val SUBJECT_REGEX = Regex("Subject: (.*)", RegexOption.IGNORE_CASE)
        private val FROM_REGEX = Regex("From: (.*)", RegexOption.IGNORE_CASE)
        private val DATE_REGEX = Regex("Date: (.*)", RegexOption.IGNORE_CASE)
        private val SEEN_REGEX = Regex("\\Seen")
    }

    private val trustAllSsl by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        ctx
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(trustAllSsl.socketFactory, trustAllSsl.trustManagers!!.first() as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class ImapState(var tagIndex: Int = 0, var capabilities: String = "")
    
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

    private fun parseEmail(headers: String, body: String): Email? {
        return try {
            val from = FROM_REGEX.find(headers)?.groupValues?.get(1)?.trim()?.let { raw ->
                // Decode =?UTF-8?B?...?= and =?UTF-8?Q?...?= 
                decodeHeader(raw)
            } ?: ""
            val subject = SUBJECT_REGEX.find(headers)?.groupValues?.get(1)?.trim()?.let { decodeHeader(it) } ?: "(无主题)"
            val dateStr = DATE_REGEX.find(headers)?.groupValues?.get(1)?.trim() ?: ""
            val isRead = SEEN_REGEX.containsMatchIn(headers)
            
            Email(
                uid = 0,
                from = from,
                subject = subject,
                bodyPlain = body.trim(),
                bodyHtml = "",
                receivedDate = parseDate(dateStr),
                isRead = isRead,
                hasAttachments = false
            )
        } catch (e: Exception) { null }
    }

    private fun decodeHeader(raw: String): String {
        // Handle =?charset?encoding?text?=
        return try {
            val decoded = StringBuilder()
            var remaining = raw
            val regex = Regex("=\?([^?]+)\?([BbQq])\?([^?]*)\?=")
            var lastEnd = 0
            regex.findAll(raw).forEach { match ->
                decoded.append(raw.substring(lastEnd, match.range.first))
                lastEnd = match.range.last + 1
                val charset = match.groupValues[1]
                val encoding = match.groupValues[2].uppercase()
                val text = match.groupValues[3]
                val decodedPart = when (encoding) {
                    "B" -> String(Base64.getDecoder().decode(text), Charsets.forName(charset))
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
        return String(bytes.toByteArray(), Charsets.forName(charset))
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val formats = arrayOf(
                "EEE, d MMM yyyy HH:mm:ss Z",
                "EEE, d MMM yyyy HH:mm:ss z",
                "d MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd HH:mm:ss"
            )
            for (fmt in formats) {
                try {
                    return SimpleDateFormat(fmt, Locale.US).parse(dateStr.trim())?.time ?: 0L
                } catch (_: Exception) {}
            }
            0L
        } catch (e: Exception) { 0L }
    }

    suspend fun fetchInbox(account: Account, page: Int = 0, pageSize: Int = 20): Result<List<Email>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://${account.imapHost}:${account.imapPort}"
                val request = Request.Builder().url(url).build()
                
                // Use raw socket via OkHttp's connection for IMAP
                // IMAP is not HTTP, so we need raw socket
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
        socket.soTimeout = 20000
        socket.startHandshake()
        
        val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        val state = ImapState()
        
        try {
            // Read greeting
            val greeting = reader.readLine() ?: throw IOException("No IMAP greeting")
            if (!greeting.startsWith("* OK")) throw IOException("Bad greeting: $greeting")
            
            // Login
            val loginCmd = "LOGIN ${account.email} ${account.password}"
            val loginResp = sendCommand(writer, reader, loginCmd, state)
            if (!loginResp.contains("OK")) throw IOException("Login failed")
            
            // Select INBOX
            val selectResp = sendCommand(writer, reader, "SELECT INBOX", state)
            if (!selectResp.contains("OK")) throw IOException("SELECT INBOX failed")
            
            // Get total count from SELECT response
            val countRegex = Regex("\* (\d+) EXISTS")
            val totalMessages = countRegex.find(selectResp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (totalMessages == 0) return emptyList()
            
            // Calculate range (newest = highest seq)
            val end = totalMessages - page * pageSize
            val start = maxOf(1, end - pageSize + 1)
            if (start > end) return emptyList()
            
            // Fetch headers first
            val emails = mutableListOf<Email>()
            val headerResp = sendCommand(writer, reader,
                "FETCH $start:$end (FLAGS BODY[HEADER.FIELDS (SUBJECT FROM DATE)])", state)
            
            // Fetch plain text body for each
            for (seqNum in start..end) {
                val bodyResp = sendCommand(writer, reader,
                    "FETCH $seqNum BODY[TEXT]", state)
                
                // Parse header from headerResp
                val headerBlock = extractFetchBlock(headerResp, seqNum)
                val bodyBlock = extractFetchBlock(bodyResp, seqNum)
                
                val email = parseEmail(headerBlock, bodyBlock)
                if (email != null) {
                    emails.add(email.copy(uid = seqNum.toLong()))
                }
            }
            
            // Logout
            sendCommand(writer, reader, "LOGOUT", state)
            
            return emails.reversed()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
    
    private fun extractFetchBlock(response: String, seqNum: Int): String {
        val lines = response.split("\n")
        val sb = StringBuilder()
        var inBlock = false
        var byteCount = 0
        for (line in lines) {
            if (line.startsWith("* $seqNum FETCH") && line.contains("{")) {
                inBlock = true
                byteCount = line.substringAfterLast("{").substringBefore("}").toIntOrNull() ?: 0
                continue
            }
            if (inBlock) {
                if (byteCount > 0 && sb.length < byteCount) {
                    sb.append(line).append("\n")
                } else if (line.startsWith(")") || line.trim().isEmpty()) {
                    inBlock = false
                }
            }
        }
        return sb.toString()
    }
}
