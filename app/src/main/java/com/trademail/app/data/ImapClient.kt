package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import jakarta.mail.*
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringWriter
import java.io.PrintWriter
import java.util.*

class ImapClient {

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
        val props = Properties().apply {
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.host", account.imapHost)
            put("mail.imap.port", "993")
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "30000")
            put("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.imap.socketFactory.fallback", "false")
            put("mail.imap.socketFactory.port", "993")
        }

        val session = Session.getInstance(props, null)
        session.debug = false

        val store = session.getStore("imaps")
        store.connect(account.email, account.password)
        store.use { s ->
            val inbox = s.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            inbox.use { f ->
                val total = f.messageCount
                if (total == 0) return emptyList()

                val end = total - page * pageSize
                val start = maxOf(1, end - pageSize + 1)
                if (start > end) return emptyList()

                val msgs = f.getMessages(start, end)
                f.fetch(msgs, arrayOf(
                    FetchProfile.Item.FLAGS,
                    FetchProfile.Item.ENVELOPE,
                    FetchProfile.Item.CONTENT_INFO
                ))

                val emails = mutableListOf<Email>()
                for (msg in msgs.reversed()) {
                    try {
                        val body = try {
                            msg.content?.toString()?.take(2000) ?: ""
                        } catch (_: Exception) { "" }

                        emails.add(Email(
                            uid = msg.messageNumber.toLong(),
                            from = msg.from?.firstOrNull()?.toString() ?: "",
                            subject = msg.subject ?: "(无主题)",
                            bodyPlain = body,
                            bodyHtml = "",
                            receivedDate = msg.receivedDate?.time ?: msg.sentDate?.time ?: 0L,
                            isRead = msg.flags.contains(Flags.Flag.SEEN),
                            hasAttachments = false
                        ))
                    } catch (_: Exception) {}
                }
                return emails
            }
        }
    }
}
