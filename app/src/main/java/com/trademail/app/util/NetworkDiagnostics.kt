package com.trademail.app.util

import java.io.*
import java.net.Socket

object NetworkDiagnostics {
    data class DiagResult(
        val test: String,
        val success: Boolean,
        val detail: String
    )

    fun runAll(host: String): List<DiagResult> {
        val results = mutableListOf<DiagResult>()

        // Test 1: Plain TCP to port 993 (should receive TLS ServerHello)
        results.add(testTcp(host, 993, "TCP:993"))

        // Test 2: Plain TCP to port 143 (should receive IMAP greeting)
        results.add(testTcp(host, 143, "TCP:143"))

        // Test 3: STARTTLS on port 143
        results.add(testStartTls(host))

        return results
    }

    private fun testTcp(host: String, port: Int, label: String): DiagResult {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 10000
            s.connect(java.net.InetSocketAddress(host, port), 10000)
            val buf = ByteArray(4096)
            val len = s.getInputStream().read(buf)
            s.close()
            if (len > 0) {
                val hex = buf.copyOf(len).joinToString(" ") { "%02x".format(it) }
                val txt = String(buf, 0, minOf(len, 200), Charsets.UTF_8)
                    .replace("\r", "\\r").replace("\n", "\\n")
                DiagResult(label, true, "got ${len}B. hex: $hex | text: $txt")
            } else {
                DiagResult(label, true, "connected but no data (len=$len)")
            }
        } catch (e: Exception) {
            DiagResult(label, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun testStartTls(host: String): DiagResult {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 10000
            s.connect(java.net.InetSocketAddress(host, 143), 10000)
            val inp = s.getInputStream()
            val out = s.getOutputStream()

            val gbuf = ByteArray(512)
            val glen = inp.read(gbuf)
            val greeting = if (glen > 0) String(gbuf, 0, glen).trim() else "(empty)"

            out.write("A0 STARTTLS\r\n".toByteArray())
            out.flush()
            val sbuf = ByteArray(512)
            val slen = inp.read(sbuf)
            val resp = if (slen > 0) String(sbuf, 0, slen).trim() else "(empty)"

            s.close()
            DiagResult("STARTTLS:143", resp.contains("OK"),
                "greeting=$greeting | STARTTLS response=$resp")
        } catch (e: Exception) {
            DiagResult("STARTTLS:143", false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
