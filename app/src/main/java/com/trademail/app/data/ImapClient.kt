package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ImapClient {

    companion object {
        private const val RELAY_BASE = "https://150.158.160.124:8443"

        private val unsafeClient: OkHttpClient by lazy {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, trustAll, SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class RelaySession(val id: String)

    private fun post(path: String, body: String): String {
        val req = Request.Builder()
            .url("$RELAY_BASE$path")
            .post(body.toRequestBody(JSON))
            .build()
        val resp = unsafeClient.newCall(req).execute()
        return resp.body?.string() ?: "{}"
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

    private var tagIdx = 0
    private fun nextTag(): String = "A${tagIdx++}"

    private fun relayCmd(session: String, cmd: String): String {
        val j = JSONObject(post("/cmd", JSONObject().apply {
            put("session", session)
            put("command", cmd)
        }.toString()))
        if (!j.optBoolean("ok")) throw IOException("Relay error: ${j.optString("error")}")
        return j.getString("response")
    }

    private fun batchCmds(session: String, cmds: List<String>): List<String> {
        val all = StringBuilder()
        cmds.forEach { relayCmd(session, it).also { r -> all.append(r) } }
        return listOf(all.toString())
    }

    suspend fun fetchInbox(account: Account, page: Int = 0, pageSize: Int = 20): Result<List<Email>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(fetch(account, page, pageSize))
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message}\n${sw.toString().take(600)}"))
            }
        }

    private fun fetch(account: Account, page: Int, pageSize: Int): List<Email> {
        tagIdx = 0

        // Connect
        val cj = JSONObject(post("/connect", "{}"))
        if (!cj.optBoolean("ok")) throw IOException("Connect: ${cj.optString("error")}")
        val session = cj.getString("session")
        val greeting = cj.getString("greeting")
        if (!greeting.startsWith("* OK")) throw IOException("Bad greeting: ${greeting.take(80)}")

        try {
            // CAPABILITY + STARTTLS
            relayCmd(session, nextTag() + " CAPABILITY")
            val stlsResp = relayCmd(session, nextTag() + " STARTTLS")
            if (!stlsResp.contains("OK")) throw IOException("STARTTLS: ${stlsResp.take(80)}")

            // TLS upgrade
            val tj = JSONObject(post("/starttls", JSONObject().apply {
                put("session", session)
            }.toString()))
            if (!tj.optBoolean("ok", false)) throw IOException("TLS: ${tj.optString("error")}")
            tagIdx = 0 // reset after TLS

            // LOGIN
            val loginResp = relayCmd(session, nextTag() + " LOGIN ${account.email} ${account.password}")
            if (!loginResp.contains("OK")) throw IOException("Login: ${loginResp.take(200)}")

            // SELECT
            val selResp = relayCmd(session, nextTag() + " SELECT INBOX")
            if (!selResp.contains("OK")) throw IOException("Select: ${selResp.take(200)}")

            val total = Regex("""\* (\d+) EXISTS""").find(selResp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (total == 0) return emptyList()

            val end = total - page * pageSize
            val start = maxOf(1, end - pageSize + 1)
            if (start > end) return emptyList()

            // FETCH headers
            val hdrResp = relayCmd(session, nextTag() + " FETCH $start:$end (FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE)])")

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
                val bodyResp = relayCmd(session, nextTag() + " FETCH $s BODY.PEEK[TEXT]")
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

            // LOGOUT
            relayCmd(session, nextTag() + " LOGOUT")

            return emails.reversed()
        } finally {
            // Close session
            try { post("/close", JSONObject().apply { put("session", session) }.toString()) } catch (_: Exception) {}
        }
    }
}
