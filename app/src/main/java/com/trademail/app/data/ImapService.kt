package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ImapService {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val trustAllFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        ctx.socketFactory
    }

    suspend fun fetchInbox(account: Account, page: Int = 0, pageSize: Int = 20): Result<List<Email>> =
        withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply {
                    put("mail.imap.host", account.imapHost)
                    put("mail.imap.port", account.imapPort.toString())
                    put("mail.imap.ssl.enable", "true")
                    put("mail.imap.ssl.socketFactory", trustAllFactory)
                    put("mail.imap.socketFactory.fallback", "false")
                    put("mail.imap.connectiontimeout", "15000")
                    put("mail.imap.timeout", "20000")
                }

                val session = Session.getInstance(props)
                // Use "imap" protocol (not "imaps") to avoid NoSuchProviderException on Android
                // SSL is enabled via mail.imap.ssl.enable=true above
                val store = session.getStore("imap")
                store.connect(account.imapHost, account.email, account.password)

                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                val totalMessages = inbox.messageCount
                val start = maxOf(1, totalMessages - page * pageSize - pageSize + 1)
                val end = totalMessages - page * pageSize
                val messages = if (start <= end) inbox.getMessages(start, end) else emptyArray()
                messages.reverse()
                val emails = messages.mapNotNull { msg -> mapToEmail(msg) }
                inbox.close(false)
                store.close()
                Result.success(emails)
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val chain = generateSequence<Throwable>(e) { it.cause }.joinToString(" <-") {
                    "${it.javaClass.simpleName}: ${it.message}"
                }
                Result.failure(RuntimeException("imap <-" + chain + "\n\n" + sw.toString().take(800)))
            }
        }
    private fun mapToEmail(msg: Message): Email? {
        return try {
            Email(
                uid = msg.messageNumber.toLong(),
                from = (msg.from?.firstOrNull() as? InternetAddress)?.address ?: "",
                subject = msg.subject ?: "(无主题)",
                bodyPlain = getTextContent(msg),
                bodyHtml = "",
                receivedDate = msg.receivedDate?.time ?: msg.sentDate?.time ?: 0,
                isRead = msg.flags?.contains(Flags.Flag.SEEN) == true,
                hasAttachments = false
            )
        } catch (e: Exception) { null }
    }

    private fun getTextContent(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> part.content?.toString() ?: ""
                part.isMimeType("text/html") -> part.content?.toString()?.replace(Regex("<[^>]*>"), " ")?.trim() ?: ""
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as MimeMultipart
                    val sb = StringBuilder()
                    for (i in 0 until mp.count) {
                        val text = getTextContent(mp.getBodyPart(i))
                        if (text.isNotBlank()) sb.appendLine(text)
                    }
                    sb.toString().trim()
                }
                else -> ""
            }
        } catch (e: Exception) { "" }
    }
}
