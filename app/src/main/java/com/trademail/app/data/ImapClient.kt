package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ImapClient {

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val okHttpClient: OkHttpClient by lazy {
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val relayBase = "https://150.158.160.124:443"
    private val JSON_MEDIA = "application/json".toMediaType()

    private fun relayPost(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(relayBase + path)
            .post(RequestBody.create(JSON_MEDIA, body.toString()))
            .build()
        val resp = okHttpClient.newCall(req).execute()
        val txt = resp.body?.string() ?: "{}"
        return JSONObject(txt)
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
                sb.append(when (m.groupValues[2].uppercase()) {
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
                })
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
                val connectResp = relayPost("/connect", JSONObject().apply {
                    put("host", account.imapHost)
                })
                if (!connectResp.getBoolean("ok")) {
                    throw IOException("Connect failed: ${connectResp.optString("error")}")
                }
                val sid = connectResp.getString("session")

                try {
                    // LOGIN
                    val loginResp = relayPost("/cmd", JSONObject().apply {
                        put("session", sid)
                        put("command", "LOGIN ${account.email} ${account.password}")
                    })
                    if (!loginResp.getBoolean("ok")) {
                        throw IOException("Login: ${loginResp}")
                    }

                    // SELECT INBOX
                    val selResp = relayPost("/cmd", JSONObject().apply {
                        put("session", sid)
                        put("command", "SELECT INBOX")
                    })
                    if (!selResp.getBoolean("ok")) {
                        throw IOException("Select: ${selResp}")
                    }

                    val selData = selResp.getJSONArray("data")
                    var total = 0
                    for (i in 0 until selData.length()) {
                        val line = selData.getString(i)
                        val m = Regex("""(\d+) EXISTS""").find(line)
                        if (m != null) total = m.groupValues[1].toIntOrNull() ?: 0
                    }
                    if (total == 0) return@withContext Result.success(emptyList())

                    val end = total - page * pageSize
                    val start = maxOf(1, end - pageSize + 1)
                    if (start > end) return@withContext Result.success(emptyList())

                    // FETCH headers
                    val hdrResp = relayPost("/cmd", JSONObject().apply {
                        put("session", sid)
                        put("command", "FETCH $start:$end (FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE)])")
                    })
                    val hdrData = hdrResp.getJSONArray("data")
                    val hdrFull = (0 until hdrData.length()).joinToString("\n") { hdrData.getString(it) }

                    val hdrBlocks = mutableMapOf<Int, String>()
                    var cs = 0; val cb = StringBuilder()
                    for (line in hdrFull.split("\n")) {
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
                        val bodyResp = relayPost("/cmd", JSONObject().apply {
                            put("session", sid)
                            put("command", "FETCH $s BODY.PEEK[TEXT]")
                        })
                        val bodyData = bodyResp.getJSONArray("data")
                        val bodyFull = (0 until bodyData.length()).joinToString("") { bodyData.getString(it) }
                        val body = Regex("""\{(\d+)\}""").find(bodyFull)?.let { m ->
                            bodyFull.substringAfter("}").let {
                                if (it.startsWith("\r\n")) it.substring(2) else it
                            }.take(m.groupValues[1].toIntOrNull() ?: 0)
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

                    relayPost("/close", JSONObject().apply { put("session", sid) })
                    Result.success(emails.reversed())
                } catch (e: Exception) {
                    try { relayPost("/close", JSONObject().apply { put("session", sid) }) } catch (_: Exception) {}
                    throw e
                }
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message}\n${sw.toString().take(800)}"))
            }
        }
}
