// Tiny loopback-only server exposing the newest tracked text frame to Even Hub.
//
// Endpoints (mirror the iOS bridge):
//   POST /text   JSON {"text": "..."}    → teleprompter text on both arms
//   POST /image  raw PNG/JPEG body       → BitmapFactory → displayImage (2×2 grid)
//   GET  /status                          → JSON connection status
//   GET  /                                → human-readable help page
//
// It never listens on Wi-Fi. The native tracker and Even app WebView communicate
// entirely inside the phone, with no laptop or external server at runtime.

package com.g2bridge

import android.graphics.BitmapFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class HttpServer(
    port: Int,
    private val connection: G2Connection,
    private val scope: CoroutineScope,
    private val frames: FrameBridge,
    private val onControl: (String) -> Boolean,
) : NanoHTTPD("127.0.0.1", port) {

    private val httpPort = port

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            when {
                session.method == Method.OPTIONS -> json(Response.Status.OK, """{"ok":true}""")
                session.method == Method.GET && session.uri == "/" -> help()
                session.method == Method.GET && session.uri == "/frame" -> frame()
                session.method == Method.GET && session.uri == "/status" -> status()
                session.method == Method.POST && session.uri == "/control" -> control(session)
                session.method == Method.POST && session.uri == "/text" -> postText(session)
                session.method == Method.POST && session.uri == "/image" -> postImage(session)
                else -> json(Response.Status.NOT_FOUND, """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, """{"error":${quote(e.message ?: "error")}}""")
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Cache-Control", "no-store, max-age=0")
        return response
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
        val snapshot = frames.snapshot()
        return json(
            Response.Status.OK,
            """{"tracker_bridge":"ready","sequence":${snapshot.sequence},"direct_g2":${quote(s.status.name.lowercase())},"detail":${quote(s.detail)}}"""
        )
    }

    private fun frame(): Response {
        val snapshot = frames.snapshot()
        val payload = snapshot.payload
        return json(
            Response.Status.OK,
            """{"sequence":${snapshot.sequence},"generated_at_ms":${snapshot.generatedAtMs},"frame":${quote(payload.frame)},"tracking":${payload.tracking},"calibrated":${payload.calibrated},"playing":${payload.playing},"tempo_bpm":${payload.tempoBpm},"song_seconds":${payload.songSeconds}}""",
        )
    }

    private fun control(session: IHTTPSession): Response {
        val body = readBody(session).trim()
        val action = extractJsonString(body, "action") ?: body
        if (action.isBlank()) return json(Response.Status.BAD_REQUEST, """{"error":"missing action"}""")
        if (!onControl(action)) return json(Response.Status.BAD_REQUEST, """{"error":"unknown action"}""")
        return json(Response.Status.OK, """{"ok":true,"action":${quote(action)}}""")
    }

    private fun help(): Response {
        val html = """
            <html><head><title>G2 Piano Tracker</title>
            <style>body{font-family:monospace;max-width:640px;margin:2em auto;line-height:1.5}</style>
            </head><body>
            <h2>G2 Piano Tracker - phone-local Even Hub bridge</h2>
            <p>The Even Hub eHPK reads this service only from 127.0.0.1.</p>
            <pre>
GET  /frame          newest 48x10 tracked waterfall frame
POST /control        plain-text action (toggle, restart, left...)
GET  /status         bridge and optional direct-G2 debug status
            </pre>
            <p>Loopback endpoint: http://127.0.0.1:$httpPort/frame</p>
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

    private fun extractJsonString(body: String, field: String): String? {
        if (body.isBlank()) return null
        val key = "\"$field\""
        val keyIndex = body.indexOf(key)
        if (keyIndex < 0) return null
        var index = body.indexOf(':', keyIndex + key.length)
        if (index < 0) return null
        index++
        while (index < body.length && body[index].isWhitespace()) index++
        if (index >= body.length || body[index] != '"') return null
        index++
        val value = StringBuilder()
        while (index < body.length) {
            val char = body[index]
            if (char == '"') return value.toString()
            if (char == '\\' && index + 1 < body.length) {
                value.append(body[index + 1])
                index += 2
            } else {
                value.append(char)
                index++
            }
        }
        return null
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
