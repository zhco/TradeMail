package com.trademail.app.data

import com.trademail.app.model.Account
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * SMTP 发件服务。
 */
class SmtpService {

    suspend fun send(
        account: Account,
        to: String,
        subject: String,
        body: String,
        isHtml: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val props = Properties().apply {
                put("mail.smtp.host", account.smtpHost)
                put("mail.smtp.port", account.smtpPort.toString())
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "15000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(account.email, account.password)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(account.email, account.displayName.ifBlank { account.email }))
                setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(to)))
                setSubject(subject, "UTF-8")
                if (isHtml) {
                    setContent(body, "text/html; charset=UTF-8")
                } else {
                    setText(body, "UTF-8")
                }
                sentDate = Date()
            }

            Transport.send(message)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
