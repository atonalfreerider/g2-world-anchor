// Tiny local HTTP server (NanoHTTPD) exposing the G2 bridge on the LAN.
//
// Endpoints (mirror the iOS bridge):
//   POST /text   JSON {"text": "..."}    → teleprompter text on both arms
//   POST /image  raw PNG/JPEG body       → BitmapFactory → displayImage (2×2 grid)
//   GET  /status                          → JSON connection status
//   GET  /                                → human-readable help page
//
// Binds to 0.0.0.0 so anything on the network can POST to http://<wifi-ip>:8080.
// No auth - intended for a trusted local network only.

package com.g2bridge

import android.graphics.BitmapFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class HttpServer(
    port: Int,
    private val connection: G2Connection,
    private val scope: CoroutineScope,
) : NanoHTTPD(port) {

    private val httpPort = port

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/" -> help()
                session.method == Method.GET && session.uri == "/status" -> status()
                session.method == Method.POST && session.uri == "/text" -> postText(session)
                session.method == Method.POST && session.uri == "/image" -> postImage(session)
                else -> json(Response.Status.NOT_FOUND, """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, """{"error":${quote(e.message ?: "error")}}""")
        }
    }

    // ---- handlers -----------------------------------------------------------
    private fun postText(session: IHTTPSession): Response {
        val body = readBody(session)
        val text = extractText(body)
            ?: return json(Response.Status.BAD_REQUEST, """{"error":"missing 'text'"}""")
        if (connection.state.value.status != G2Status.READY) {
            return json(Response.Status.SERVICE_UNAVAILABLE, """{"error":"glasses not connected"}""")
        }
        scope.launch { connection.displayText(text) }
        return json(Response.Status.OK, """{"ok":true,"sent":${quote(text)}}""")
    }

    private fun postImage(session: IHTTPSession): Response {
        val data = readBinaryBody(session)
            ?: return json(Response.Status.BAD_REQUEST, """{"error":"empty image body"}""")
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: return json(Response.Status.BAD_REQUEST, """{"error":"not a PNG/JPEG image"}""")
        if (connection.state.value.status != G2Status.READY) {
            return json(Response.Status.SERVICE_UNAVAILABLE, """{"error":"glasses not connected"}""")
        }
        scope.launch { connection.displayImage(bitmap) }
        return json(Response.Status.OK, """{"ok":true,"w":${bitmap.width},"h":${bitmap.height}}""")
    }

    private fun status(): Response {
        val s = connection.state.value
        return json(
            Response.Status.OK,
            """{"status":${quote(s.status.name.lowercase())},"detail":${quote(s.detail)}}"""
        )
    }

    private fun help(): Response {
        val html = """
            <html><head><title>g2-bridge</title>
            <style>body{font-family:monospace;max-width:640px;margin:2em auto;line-height:1.5}</style>
            </head><body>
            <h2>g2-bridge - Even Realities G2 over BLE</h2>
            <p>Status: <b>${connection.state.value.status.name.lowercase()}</b></p>
            <pre>
POST /text    {"text": "hello"}      push a line of text to the lens
POST /image   (raw PNG/JPEG body)    push an image (scaled to 576x288)
GET  /status                         JSON connection status
            </pre>
            <p>Example:</p>
            <pre>curl -X POST http://&lt;this-ip&gt;:$httpPort/text \
     -H "Content-Type: application/json" \
     -d '{"text":"hello from curl"}'</pre>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    // ---- helpers ------------------------------------------------------------
    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        // NanoHTTPD puts the raw body under "postData" for non-form content types.
        return map["postData"] ?: session.queryParameterString ?: ""
    }

    private fun readBinaryBody(session: IHTTPSession): ByteArray? {
        // NanoHTTPD reliably spools a non-form POST body to a temp file: parseBody()
        // puts that temp file's path under the key "content" (and a small body's
        // string under "postData"). Reading the temp file avoids the chunked /
        // SocketTimeout pitfalls of touching getInputStream() directly.
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }

        files["content"]?.let { path ->
            val f = java.io.File(path)
            if (f.exists() && f.length() > 0) {
                return runCatching { f.readBytes() }.getOrNull()
            }
        }
        files["postData"]?.let { s ->
            if (s.isNotEmpty()) return s.toByteArray(Charsets.ISO_8859_1)
        }

        // Fallback: read Content-Length bytes off the input stream.
        val len = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (len <= 0) return null
        val buf = ByteArray(len)
        var read = 0
        val input = session.inputStream
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return if (read == len) buf else buf.copyOf(read)
    }

    /** Pull a "text" value out of a JSON object body, falling back to raw body. */
    private fun extractText(body: String): String? {
        if (body.isBlank()) return null
        // Minimal JSON-string extraction for {"text": "..."} (avoids a JSON dep).
        val key = "\"text\""
        val ki = body.indexOf(key)
        if (ki >= 0) {
            var i = body.indexOf(':', ki + key.length)
            if (i < 0) return null
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            if (i < body.length && body[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < body.length) {
                    val c = body[i]
                    if (c == '\\' && i + 1 < body.length) {
                        when (val n = body[i + 1]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'u' -> {
                                if (i + 5 < body.length) {
                                    val hex = body.substring(i + 2, i + 6)
                                    runCatching { sb.append(hex.toInt(16).toChar()) }
                                    i += 4
                                }
                            }
                            else -> sb.append(n)
                        }
                        i += 2
                    } else if (c == '"') {
                        return sb.toString()
                    } else {
                        sb.append(c); i++
                    }
                }
                return sb.toString()
            }
        }
        // Not JSON - treat the whole body as the text.
        return body.trim()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)
}
