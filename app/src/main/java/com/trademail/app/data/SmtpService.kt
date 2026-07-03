package com.trademail.app.data

import com.trademail.app.model.Account
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SmtpService {

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
                put("mail.smtp.ssl.socketFactory", trustAllFactory)
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "20000")
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
                if (isHtml) setContent(body, "text/html; charset=UTF-8")
                else setText(body, "UTF-8")
                sentDate = Date()
            }

            Transport.send(message)
            Result.success(Unit)
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val chain = generateSequence(e) { it.cause }.joinToString(" <- ") {
                "${it.javaClass.simpleName}: ${it.message}"
            }
            Result.failure(RuntimeException("smtps<-$chain\n\n${sw.toString().take(800)}"))
        }
    }
}
