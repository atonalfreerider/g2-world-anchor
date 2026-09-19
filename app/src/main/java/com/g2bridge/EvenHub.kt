// EvenHub native-container / image / mic protocol for Even G2.
//
// Port of the reference EvenHub layer, cross-checked against PROTOCOL.md §9.
// EvenHub messages are an inner proto3 wrapper sent via Protocol.framePb with
// sid=0xE0, flag=0x20, on the RIGHT ARM ONLY (left wedges the plugin task).
//
// Wrapper: field 1 = Cmd (varint), field 2 = magic (varint correlation id),
// plus one nested message field per command (see Cmd table / §9).
//
// proto3 wire rules (match protobuf-es): omit zero/empty scalars; nested
// messages are always emitted. Container names must be ≤14 bytes; never send a
// zero-length list item / text content (substitute "·").

package com.g2bridge

import android.graphics.Bitmap

object EvenHub {

    const val SID = 0xE0
    const val FLAG_REQUEST = 0x20

    // Cmd values. proto3 omits Cmd=0 (create) on the wire when it is the only
    // varint, but here field 1 is followed by field 2 so create is encoded
    // implicitly by NOT emitting field 1 - uintF(1, 0) returns empty, matching
    // the Swift reference and protobuf-es.
    object Cmd {
        const val CREATE = 0
        const val EVENT = 2          // RX
        const val UPDATE_IMAGE = 3
        const val UPDATE_TEXT = 5
        const val REBUILD = 7
        const val SHUTDOWN = 9
        const val PRIVATE_EVENT = 11 // RX
        const val HEARTBEAT = 12
        const val AUDIO_CTRL = 15
        const val AUDIO_RES = 16     // RX
    }

    /** Container names must be ≤14 bytes or the firmware silently rejects them. */
    fun validName(name: String): String {
        require(name.toByteArray(Charsets.UTF_8).size <= 14) {
            "EvenHub container name '$name' exceeds 14 bytes"
        }
        return name
    }

    // App-launch prelude (sid 0x01) - send verbatim once per BLE session, right arm.
    val PRELUDE: ByteArray = intArrayOf(
        0xAA, 0x21, 0x92, 0x13, 0x01, 0x01, 0x01, 0x20, 0x08, 0x02, 0x10, 0x9C,
        0x01, 0x22, 0x0A, 0x1A, 0x08, 0x12, 0x06, 0x12, 0x04, 0x08, 0x00, 0x10,
        0x00, 0xA1, 0x42
    ).let { a -> ByteArray(a.size) { a[it].toByte() } }

    // ---- proto3 wire encoders (omit zero/empty scalars) ---------------------
    private fun varint(value: Int): ByteArray {
        var u = value.toLong() and 0xFFFFFFFFL
        val out = ArrayList<Byte>()
        do {
            var b = (u and 0x7F).toInt()
            u = u ushr 7
            if (u != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (u != 0L)
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): ByteArray = varint((field shl 3) or wire)
    private fun uintF(field: Int, v: Int): ByteArray = if (v == 0) ByteArray(0) else tag(field, 0) + varint(v)
    private fun strF(field: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return if (b.isEmpty()) ByteArray(0) else tag(field, 2) + varint(b.size) + b
    }
    private fun bytesF(field: Int, b: ByteArray): ByteArray =
        if (b.isEmpty()) ByteArray(0) else tag(field, 2) + varint(b.size) + b

    /** Nested message - always emitted (the field is "present"). */
    private fun msgF(field: Int, content: ByteArray): ByteArray =
        tag(field, 2) + varint(content.size) + content

    /** repeated string: one entry per item, in order. */
    private fun repStrF(field: Int, items: List<String>): ByteArray {
        var out = ByteArray(0)
        for (it in items) out += strF(field, it)
        return out
    }

    // ---- message builders (return the inner protobuf payload) ---------------

    /** Cmd=12 heartbeat. Keeps the EvenHub plugin task alive. */
    fun heartbeat(magic: Int = 205): ByteArray =
        uintF(1, Cmd.HEARTBEAT) + uintF(2, magic) + msgF(14, /* HeartBeatPacket{Cnt:0} */ ByteArray(0))

    data class ImageContainer(val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Cmd=0 create startup page declaring image container(s) directly - primes
     * the plugin task AND declares the image surface(s) (up to 4 = the 2×2 grid).
     */
    fun createStartupPageImages(containers: List<ImageContainer>, magic: Int = 201): ByteArray {
        var create = uintF(1, containers.size)        // ContainerTotalNum
        for (c in containers) {
            val obj = uintF(1, c.x) + uintF(2, c.y) + uintF(3, c.w) + uintF(4, c.h) +
                uintF(5, c.id) + strF(6, validName(c.name))
            create += msgF(4, obj)                    // ImageObject (repeated)
        }
        create += uintF(5, 10000)                     // widgetId
        return uintF(1, Cmd.CREATE) + uintF(2, magic) + msgF(3, create)
    }

    /** Cmd=3 one image-data fragment. CompressMode(5)=0 omitted. */
    fun imageRawData(
        containerId: Int, name: String, sessionId: Int, totalSize: Int,
        fragmentIndex: Int, data: ByteArray, magic: Int
    ): ByteArray {
        val img = uintF(1, containerId) + strF(2, name) + uintF(3, sessionId) + uintF(4, totalSize) +
            uintF(6, fragmentIndex) + uintF(7, data.size) + bytesF(8, data)
        return uintF(1, Cmd.UPDATE_IMAGE) + uintF(2, magic) + msgF(5, img)
    }

    // ---- native list / text container builders ------------------------------

    data class ListGeom(val x: Int = 0, val y: Int = 0, val w: Int = 576, val h: Int = 288)

    /** List_ItemContainerProperty payload. NEVER pass an empty-string row. */
    private fun listItemObj(items: List<String>, selectBorder: Boolean): ByteArray {
        val safe = items.map { if (it.isEmpty()) "·" else it }
        return uintF(1, safe.size) +                       // ItemCount
            uintF(3, if (selectBorder) 1 else 0) +         // IsItemSelectBorderEn
            repStrF(4, safe)                               // ItemName (repeated)
    }

    private fun listObj(
        id: Int, name: String, items: List<String>, capture: Boolean,
        selectBorder: Boolean, geom: ListGeom
    ): ByteArray {
        val item = listItemObj(items, selectBorder)
        return uintF(1, geom.x) + uintF(2, geom.y) + uintF(3, geom.w) + uintF(4, geom.h) +
            uintF(9, id) + strF(10, validName(name)) + msgF(11, item) +
            uintF(12, if (capture) 1 else 0)
    }

    private fun textObj(id: Int, name: String, content: String, capture: Boolean, geom: ListGeom): ByteArray {
        return uintF(1, geom.x) + uintF(2, geom.y) + uintF(3, geom.w) + uintF(4, geom.h) +
            uintF(9, id) + strF(10, validName(name)) + uintF(11, if (capture) 1 else 0) +
            strF(12, content)
    }

    /** Cmd=0 CREATE a startup page with ONE full-lens LIST container. */
    fun createList(
        name: String, items: List<String>, geom: ListGeom = ListGeom(),
        containerId: Int = 1, widgetId: Int = 10000,
        selectBorder: Boolean = true, captureEvents: Boolean = true, magic: Int = 201
    ): ByteArray {
        var create = uintF(1, 1)                       // ContainerTotalNum
        create += msgF(2, listObj(containerId, name, items, captureEvents, selectBorder, geom)) // ListObject
        create += uintF(5, widgetId)
        return uintF(1, Cmd.CREATE) + uintF(2, magic) + msgF(3, create)
    }

    /** Cmd=7 REBUILD an existing LIST container (same name) with new rows. */
    fun rebuildList(
        name: String, items: List<String>, geom: ListGeom = ListGeom(),
        containerId: Int = 1, selectBorder: Boolean = true, captureEvents: Boolean = true, magic: Int
    ): ByteArray {
        val obj = listObj(containerId, name, items, captureEvents, selectBorder, geom)
        val rebuild = uintF(1, 1) + msgF(2, obj)       // ContainerTotalNum, ListObject
        return uintF(1, Cmd.REBUILD) + uintF(2, magic) + msgF(7, rebuild)
    }

    /** Cmd=7 create/switch a TEXT container (same or new name) with content. */
    fun rebuildText(
        name: String, content: String, geom: ListGeom = ListGeom(),
        containerId: Int = 1, captureEvents: Boolean = true, magic: Int
    ): ByteArray {
        val obj = textObj(containerId, name, content, captureEvents, geom)
        val rebuild = uintF(1, 1) + msgF(3, obj)       // ContainerTotalNum, TextObject=3
        return uintF(1, Cmd.REBUILD) + uintF(2, magic) + msgF(7, rebuild)
    }

    /** Cmd=5 in-place TEXT upgrade (flicker-free). Full snapshot from offset 0. */
    fun textUpgrade(containerId: Int = 1, name: String, content: String, magic: Int): ByteArray {
        val bytes = (if (content.isEmpty()) "·" else content).toByteArray(Charsets.UTF_8)
        val up = uintF(1, containerId) + strF(2, validName(name)) + uintF(3, 0) +
            uintF(4, bytes.size) + bytesF(5, bytes)
        return uintF(1, Cmd.UPDATE_TEXT) + uintF(2, magic) + msgF(9, up)  // TextUpgrade=9
    }

    /** Cmd=9 ShutDown the foregrounded page (frees its containers). */
    fun shutDown(exitMode: Int = 0, magic: Int): ByteArray =
        uintF(1, Cmd.SHUTDOWN) + uintF(2, magic) + msgF(11, uintF(1, exitMode))  // ShutDownCmd=11

    // ---- audio (mic) control - Cmd=15 AudioCtrCmd / RX Cmd=16 AudioCtrRes ----
    /** Cmd=15 AudioCtrCmd. action = 1 start mic, 0 stop. Sub-field 18. */
    fun audioCtrl(action: Int, magic: Int): ByteArray {
        val inner = uintF(1, action)                   // AudoFuncEn
        return uintF(1, Cmd.AUDIO_CTRL) + uintF(2, magic) + msgF(18, inner)
    }

    // ---- response decoding --------------------------------------------------
    private fun dVarint(b: ByteArray, off: Int): Pair<Int, Int> {
        var v = 0
        var s = 0
        var i = off
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            i++
            v = v or ((c and 0x7F) shl s)
            if ((c and 0x80) == 0) break
            s += 7
        }
        return Pair(v, i)
    }

    data class Response(val cmd: Int, val magic: Int, val result: Int?)

    /** Parse an EvenHub (sid 0xE0) response payload into cmd / magic / result. */
    fun parseResponse(pb: ByteArray): Response {
        var cmd = -1
        var magic = -1
        var result: Int? = null
        var i = 0
        while (i < pb.size) {
            val key = pb[i].toInt() and 0xFF
            i++
            val field = key shr 3
            val wire = key and 7
            if (wire == 0) {
                val (v, n) = dVarint(pb, i); i = n
                if (field == 1) cmd = v else if (field == 2) magic = v
            } else if (wire == 2) {
                val (len, n) = dVarint(pb, i); i = n
                val endIdx = minOf(i + len, pb.size)
                val sub = pb.copyOfRange(i, endIdx); i = endIdx
                // Response messages put the error enum in field 1 (ResCmdMsg) or 8 (ErrorCode).
                var j = 0
                while (j < sub.size) {
                    val k = sub[j].toInt() and 0xFF; j++
                    val f = k shr 3; val w = k and 7
                    if (w == 0) {
                        val (vv, nn) = dVarint(sub, j); j = nn
                        if (f == 1 || f == 8) result = vv
                    } else if (w == 2) {
                        val (l, nn) = dVarint(sub, j); j = nn + l
                    } else break
                }
            } else break
        }
        return Response(cmd, magic, result)
    }

    fun describeResponse(pb: ByteArray): String {
        val r = parseResponse(pb)
        val cmdNames = mapOf(
            1 to "createResp", 2 to "event", 4 to "imageResp", 6 to "textResp",
            8 to "rebuildResp", 13 to "heartbeatResp"
        )
        val errNames = mapOf(
            0 to "createOK", 1 to "createInvalid", 2 to "createOversize", 3 to "createOOM",
            4 to "imageOK", 5 to "imageFAILED", 6 to "rebuildOK", 7 to "rebuildFAILED",
            8 to "textOK", 9 to "textFailed", 12 to "heartbeatOK"
        )
        val cn = cmdNames[r.cmd] ?: "cmd${r.cmd}"
        val rn = r.result?.let { "result=${errNames[it] ?: "err$it"}" } ?: ""
        return "EvenHub $cn magic=${r.magic} $rn"
    }

    // ---- incoming async event parser ----------------------------------------
    sealed class InEvent {
        data class ListClick(val name: String, val index: Int, val itemName: String, val type: Int) : InEvent()
        data class TextClick(val name: String, val type: Int) : InEvent()
        data class SysEvent(val type: Int, val exitReason: Int) : InEvent()
        data class PrivateEvent(val name: String, val eventId: Int, val eventData: Int) : InEvent()
        object Other : InEvent()
    }

    private fun readStr(b: ByteArray, off: Int, len: Int): String {
        val end = minOf(off + len, b.size)
        return String(b.copyOfRange(off, end), Charsets.UTF_8)
    }

    private fun parseListEvent(b: ByteArray): InEvent {
        var name = ""; var item = ""; var index = 0; var type = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(b, i); i = n
                if (f == 4) index = v else if (f == 5) type = v
            } else if (w == 2) {
                val (l, n) = dVarint(b, i); i = n
                if (f == 2) name = readStr(b, i, l) else if (f == 3) item = readStr(b, i, l)
                i += l
            } else break
        }
        return InEvent.ListClick(name, index, item, type)
    }

    private fun parseTextEvent(b: ByteArray): InEvent {
        var name = ""; var type = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) { val (v, n) = dVarint(b, i); i = n; if (f == 3) type = v }
            else if (w == 2) { val (l, n) = dVarint(b, i); i = n; if (f == 2) name = readStr(b, i, l); i += l }
            else break
        }
        return InEvent.TextClick(name, type)
    }

    private fun parseSysEvent(b: ByteArray): InEvent {
        var type = 0; var exitReason = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(b, i); i = n
                if (f == 1) type = v else if (f == 4) exitReason = v
            } else if (w == 2) { val (l, n) = dVarint(b, i); i = n + l }
            else break
        }
        return InEvent.SysEvent(type, exitReason)
    }

    private fun parseDevEvent(b: ByteArray): InEvent {
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 2) {
                val (l, n) = dVarint(b, i); i = n
                val sub = b.copyOfRange(i, minOf(i + l, b.size)); i += l
                when (f) {
                    1 -> return parseListEvent(sub)
                    2 -> return parseTextEvent(sub)
                    3 -> return parseSysEvent(sub)
                }
            } else if (w == 0) { val (_, n) = dVarint(b, i); i = n }
            else break
        }
        return InEvent.Other
    }

    private fun parsePrivate(b: ByteArray): InEvent {
        var name = ""; var eventId = 0; var eventData = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(b, i); i = n
                if (f == 3) eventId = v else if (f == 4) eventData = v
            } else if (w == 2) { val (l, n) = dVarint(b, i); i = n; if (f == 2) name = readStr(b, i, l); i += l }
            else break
        }
        return InEvent.PrivateEvent(name, eventId, eventData)
    }

    private fun parseEventInner(pb: ByteArray): InEvent? {
        var cmd = -1
        var devSub: ByteArray? = null
        var privSub: ByteArray? = null
        var i = 0
        while (i < pb.size) {
            val key = pb[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) { val (v, n) = dVarint(pb, i); i = n; if (f == 1) cmd = v }
            else if (w == 2) {
                val (l, n) = dVarint(pb, i); i = n
                val sub = pb.copyOfRange(i, minOf(i + l, pb.size)); i += l
                if (f == 13) devSub = sub else if (f == 16) privSub = sub
            } else break
        }
        if (cmd == Cmd.EVENT && devSub != null) return parseDevEvent(devSub)
        if (cmd == Cmd.PRIVATE_EVENT && privSub != null) return parsePrivate(privSub)
        return null
    }

    /** Parse an EvenHub async event body (retry without a trailing CRC). */
    fun parseEvent(pb: ByteArray): InEvent? {
        parseEventInner(pb)?.let { return it }
        if (pb.size > 2) return parseEventInner(pb.copyOfRange(0, pb.size - 2))
        return null
    }

    data class AudioRes(val magic: Int, val stat: Int)

    /** Parse a Cmd=19 AudioCtrRes payload. Returns null if not Cmd=16/19. */
    fun parseAudioRes(pb: ByteArray): AudioRes? {
        var cmd = -1; var magic = -1; var stat = 0
        var i = 0
        while (i < pb.size) {
            val key = pb[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(pb, i); i = n
                if (f == 1) cmd = v else if (f == 2) magic = v
            } else if (w == 2) {
                val (l, n) = dVarint(pb, i); i = n
                val endIdx = minOf(i + l, pb.size)
                val sub = pb.copyOfRange(i, endIdx); i = endIdx
                if (f == 19) {
                    var j = 0
                    while (j < sub.size) {
                        val k = sub[j].toInt() and 0xFF; j++
                        val sf = k shr 3; val sw = k and 7
                        if (sw == 0) { val (vv, nn) = dVarint(sub, j); j = nn; if (sf == 1) stat = vv }
                        else if (sw == 2) { val (ll, nn) = dVarint(sub, j); j = nn + ll }
                        else break
                    }
                }
            } else break
        }
        return if (cmd == Cmd.AUDIO_RES) AudioRes(magic, stat) else null
    }

    // ---- 4-bpp BMP builder (port of bmp4 / buildEvenHubBmp) ------------------
    /**
     * Build a 4-bits/pixel Windows BMP, bottom-up rows, 4-byte row stride, 16-entry
     * grayscale palette (entry i = (i*17,i*17,i*17)). [pixel] returns 0..15.
     */
    fun bmp4(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray {
        val bytesPerPixelRow = (width + 1) / 2
        val rowStride = (bytesPerPixelRow + 3) and 3.inv()
        val pixelDataSize = rowStride * height
        val pixelOffset = 14 + 40 + 64
        val fileSize = pixelOffset + pixelDataSize
        val b = ByteArray(fileSize)
        fun u16(off: Int, v: Int) { b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte() }
        fun u32(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte(); b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        b[0] = 0x42.toByte(); b[1] = 0x4D.toByte()        // "BM"
        u32(2, fileSize); u32(10, pixelOffset)
        u32(14, 40); u32(18, width); u32(22, height); u16(26, 1); u16(28, 4)
        u32(34, pixelDataSize); u32(46, 16)               // biClrUsed = 16
        for (i in 0 until 16) {
            val v = i * 17; val p = 54 + i * 4
            b[p] = v.toByte(); b[p + 1] = v.toByte(); b[p + 2] = v.toByte()
        }
        for (bmpRow in 0 until height) {
            val srcY = height - 1 - bmpRow
            val rowOff = pixelOffset + bmpRow * rowStride
            var x = 0
            while (x < width) {
                val hi = pixel(x, srcY) and 0x0F
                val lo = (if (x + 1 < width) pixel(x + 1, srcY) else 0) and 0x0F
                b[rowOff + (x shr 1)] = ((hi shl 4) or lo).toByte()
                x += 2
            }
        }
        return b
    }

    // ---- Bitmap → 576×288 grayscale → 2×2 tiles → bmp4 ----------------------
    data class Tile(val x: Int, val y: Int, val bmp: ByteArray)

    /** Per-pixel 4-bit gray (0..15) from an ARGB pixel, using luminance. */
    private fun gray4(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // Rec. 601 luma; >> 4 to compress 0..255 → 0..15.
        val luma = (r * 77 + g * 150 + b * 29) shr 8   // 0..255
        return luma shr 4                              // 0..15
    }

    /**
     * Scale [src] into a [fullW]×[fullH] grayscale frame, then slice into
     * [tileW]×[tileH] gray4 tiles in reading order (TL, TR, BL, BR) for the 2×2
     * lens grid. Returns the four tiles' 4-bpp BMP bytes.
     */
    fun renderBitmapTiles(
        src: Bitmap, fullW: Int = 576, fullH: Int = 288, tileW: Int = 288, tileH: Int = 144
    ): List<Tile> {
        // Scale to the lens resolution (preserve aspect: letterbox into the frame).
        val scaled = scaleToFrame(src, fullW, fullH)
        val px = IntArray(fullW * fullH)
        scaled.getPixels(px, 0, fullW, 0, 0, fullW, fullH)
        if (scaled !== src) scaled.recycle()

        val tiles = ArrayList<Tile>()
        var ty = 0
        while (ty < fullH) {
            var tx = 0
            while (tx < fullW) {
                val ox = tx; val oy = ty
                val bmp = bmp4(tileW, tileH) { lx, ly ->
                    val gx = ox + lx; val gy = oy + ly
                    if (gx < fullW && gy < fullH) gray4(px[gy * fullW + gx]) else 0
                }
                tiles.add(Tile(tx, ty, bmp))
                tx += tileW
            }
            ty += tileH
        }
        return tiles
    }

    /** Build a single 4-bpp BMP from a bitmap scaled to [w]×[h] (e.g. one tile). */
    fun renderBitmapSingle(src: Bitmap, w: Int = 288, h: Int = 144): ByteArray {
        val scaled = scaleToFrame(src, w, h)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        return bmp4(w, h) { x, y -> gray4(px[y * w + x]) }
    }

    /** Scale-to-fit into [w]×[h] with a black background (letterbox). */
    private fun scaleToFrame(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        val dst = android.graphics.RectF(left, top, left + dw, top + dh)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }
}
