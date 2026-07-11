package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.StringWriter
import java.io.PrintWriter
import java.util.concurrent.TimeUnit

class ImapClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val PROXY_URL = "https://你部署后填入.supabase.co/functions/v1/imap-proxy"
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
        val body = JSONObject().apply {
            put("action", "fetch_inbox")
            put("host", account.imapHost)
            put("port", 993)
            put("user", account.email)
            put("pass", account.password)
            put("page", page)
            put("pageSize", pageSize)
        }

        val req = Request.Builder()
            .url(PROXY_URL)
            .header("Authorization", "Bearer supabase_anon_key")
            .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")

        val json = JSONObject(resp.body?.string() ?: "{}")
        if (!json.optBoolean("ok", false)) throw RuntimeException(json.optString("error", "unknown"))

        val arr = json.getJSONArray("emails")
        val emails = mutableListOf<Email>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            emails.add(Email(
                uid = e.optLong("uid", 0),
                from = e.optString("from", ""),
                subject = e.optString("subject", "(无主题)"),
                bodyPlain = e.optString("bodyPlain", ""),
                bodyHtml = e.optString("bodyHtml", ""),
                receivedDate = e.optLong("receivedDate", 0),
                isRead = e.optBoolean("isRead", false),
                hasAttachments = e.optBoolean("hasAttachments", false)
            ))
        }
        return emails
    }
}
