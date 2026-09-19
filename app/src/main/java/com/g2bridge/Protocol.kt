// Even Realities G2 BLE protocol - framing, auth, and teleprompter text display.
//
// Byte-for-byte port of the reference protocol, cross-checked against
// g2-bridge/PROTOCOL.md (the source of truth). Everything little-endian.
//
// Single packet (buildPacket):
//   [0xAA][0x21][seq][len=payload+2][01][01][svcHi][svcLo][payload][crcLo][crcHi]
//   CRC-16/CCITT-FALSE over the PAYLOAD only, appended LE.
//
// Multi-fragment envelope (framePb): one CRC over the whole payload, appended LE
// to the LAST fragment; all fragments share `seq` (the firmware's reassembly key).

package com.g2bridge

object Protocol {

    // ---- GATT UUIDs (lower-case; Android compares case-insensitively) -------
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"   // scan filter / retrieveConnected
    const val WRITE_UUID = "00002760-08c2-11e1-9073-0e8ac72e5401"     // control/teleprompter writeNR
    const val NOTIFY_UUID = "00002760-08c2-11e1-9073-0e8ac72e5402"    // acks + events notify
    const val DISPLAY_WRITE_UUID = "00002760-08c2-11e1-9073-0e8ac72e6401" // optional binary write
    const val MIC_NOTIFY_UUID = "00002760-08c2-11e1-9073-0e8ac72e6402"    // mic LC3 stream notify

    // ---- varint -------------------------------------------------------------
    /** Standard protobuf base-128 varint (LSB group first, high bit = continuation). */
    fun encodeVarint(value: Int): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (v > 0x7F) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add((v and 0x7F).toByte())
        return out.toByteArray()
    }

    // ---- framing ------------------------------------------------------------

    /**
     * Multi-fragment envelope for payloads > one BLE chunk (e.g. EvenHub images).
     * CRC-16 over the whole payload, appended LE to the LAST fragment; all
     * fragments share [seq]. Frames larger than the negotiated MTU are silently
     * dropped by the firmware → reassembly abort, so size [chunk] to the MTU.
     */
    fun framePb(seq: Int, sid: Int, flag: Int, pb: ByteArray, chunk: Int = 232): List<ByteArray> {
        require(chunk in 3..255) { "BLE payload chunk must be 3..255 bytes" }
        val crcv = Crc16.ccitt(pb)
        val crc = byteArrayOf((crcv and 0xFF).toByte(), ((crcv shr 8) and 0xFF).toByte())
        val totalFrags = maxOf(1, (pb.size + 2 + chunk - 1) / chunk)
        require(totalFrags <= 255) { "payload needs $totalFrags fragments; protocol limit is 255" }
        val frames = ArrayList<ByteArray>(totalFrags)
        var off = 0
        for (i in 0 until totalFrags) {
            val isLast = i == totalFrags - 1
            val chunkBytes: ByteArray = if (isLast) {
                pb.copyOfRange(off, pb.size) + crc
            } else {
                // Balance payload across the remaining packets while reserving
                // two bytes for the CRC in the final packet. The old greedy
                // offset increment could advance past pb.size at boundaries
                // such as 463 bytes with a 232-byte chunk, crashing the app.
                val framesIncludingCurrent = totalFrags - i
                val framesAfter = framesIncludingCurrent - 1
                val capacityAfter = (framesAfter - 1) * chunk + (chunk - 2)
                val remaining = pb.size - off
                val minimumTake = maxOf(0, remaining - capacityAfter)
                val balancedTake = (remaining + framesIncludingCurrent - 1) / framesIncludingCurrent
                val take = maxOf(minimumTake, balancedTake).coerceAtMost(chunk)
                pb.copyOfRange(off, off + take)
            }
            off += if (isLast) pb.size - off else chunkBytes.size
            val header = byteArrayOf(
                0xAA.toByte(), 0x21.toByte(), seq.toByte(), (chunkBytes.size and 0xFF).toByte(),
                (totalFrags and 0xFF).toByte(), ((i + 1) and 0xFF).toByte(),
                (sid and 0xFF).toByte(), (flag and 0xFF).toByte()
            )
            frames.add(header + chunkBytes)
        }
        check(off == pb.size) { "framing consumed $off of ${pb.size} payload bytes" }
        return frames
    }

    /** Single-fragment control packet. CRC over [payload] only, appended LE. */
    fun buildPacket(seq: Int, svcHi: Int, svcLo: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            0xAA.toByte(), 0x21.toByte(), seq.toByte(), ((payload.size + 2) and 0xFF).toByte(),
            0x01, 0x01, (svcHi and 0xFF).toByte(), (svcLo and 0xFF).toByte()
        )
        val crc = Crc16.ccitt(payload)
        val tail = byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
        return header + payload + tail
    }

    private fun hexBytes(s: String): ByteArray {
        val clean = s.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i + 2 <= clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    // small helpers to build payloads from mixed int/byte literals
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ---- authentication (7 packets) -----------------------------------------
    // Embeds a varint UNIX timestamp (seconds). txid is fixed.
    fun authPackets(timestamp: Int): List<ByteArray> {
        val ts = encodeVarint(timestamp)
        val txid = bytes(0xE8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)
        return listOf(
            buildPacket(0x01, 0x80, 0x00, bytes(0x08, 0x04, 0x10, 0x0C, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04)),
            buildPacket(0x02, 0x80, 0x20, bytes(0x08, 0x05, 0x10, 0x0E, 0x22, 0x02, 0x08, 0x02)),
            buildPacket(0x03, 0x80, 0x20, bytes(0x08, 0x80, 0x01, 0x10, 0x0F, 0x82, 0x08, 0x11, 0x08) + ts + bytes(0x10) + txid),
            buildPacket(0x04, 0x80, 0x00, bytes(0x08, 0x04, 0x10, 0x10, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04)),
            buildPacket(0x05, 0x80, 0x00, bytes(0x08, 0x04, 0x10, 0x11, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04)),
            buildPacket(0x06, 0x80, 0x20, bytes(0x08, 0x05, 0x10, 0x12, 0x22, 0x02, 0x08, 0x01)),
            buildPacket(0x07, 0x80, 0x20, bytes(0x08, 0x80, 0x01, 0x10, 0x13, 0x82, 0x08, 0x11, 0x08) + ts + bytes(0x10) + txid),
        )
    }

    // ---- teleprompter packets -----------------------------------------------
    private val CONFIG_BLOB: ByteArray = hexBytes(
        "08011213080210904E1D00E0944425000000002800300012130803100D0F1D0040" +
        "8D442500000000280030001212080410001D000088422500000000280030001212" +
        "080510001D00009242250000A242280030001212080610001D0000C642250000C4" +
        "4228003000" + "1800"
    )

    fun displayConfig(seq: Int, msgId: Int): ByteArray {
        val payload = bytes(0x08, 0x02, 0x10) + encodeVarint(msgId) + bytes(0x22, 0x6A) + CONFIG_BLOB
        return buildPacket(seq, 0x0E, 0x20, payload)
    }

    fun teleprompterInit(seq: Int, msgId: Int, totalLines: Int, manual: Boolean = true): ByteArray {
        val mode = if (manual) 0x00 else 0x01
        val contentHeight = maxOf(1, (totalLines * 2665) / 140)
        var display = bytes(0x08, 0x01, 0x10, 0x00, 0x18, 0x00, 0x20, 0x8B, 0x02) // script idx, w=267
        display += bytes(0x28) + encodeVarint(contentHeight)                       // content height
        display += bytes(0x30, 0xE6, 0x01)                                         // line height 230
        display += bytes(0x38, 0x8E, 0x0A)                                         // viewport 1294
        display += bytes(0x40, 0x05, 0x48, mode)                                   // font 5 + scroll mode
        val settings = bytes(0x08, 0x01, 0x12, display.size) + display
        val payload = bytes(0x08, 0x01, 0x10) + encodeVarint(msgId) + bytes(0x1A, settings.size) + settings
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    fun contentPage(seq: Int, msgId: Int, pageNum: Int, text: String): ByteArray {
        val textBytes = ("\n" + text).toByteArray(Charsets.UTF_8)
        val inner = bytes(0x08) + encodeVarint(pageNum) + bytes(0x10, 0x0A) +
            bytes(0x1A) + encodeVarint(textBytes.size) + textBytes
        val content = bytes(0x2A) + encodeVarint(inner.size) + inner
        val payload = bytes(0x08, 0x03, 0x10) + encodeVarint(msgId) + content
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    fun marker(seq: Int, msgId: Int): ByteArray {
        val payload = bytes(0x08, 0xFF, 0x01, 0x10) + encodeVarint(msgId) + bytes(0x6A, 0x04, 0x08, 0x00, 0x10, 0x06)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    fun sync(seq: Int, msgId: Int): ByteArray {
        val payload = bytes(0x08, 0x0E, 0x10) + encodeVarint(msgId) + bytes(0x6A, 0x00)
        return buildPacket(seq, 0x80, 0x00, payload)
    }

    /** Teleprompter / text-mode keep-alive (sent to BOTH arms). */
    fun heartbeat(seq: Int): ByteArray = buildPacket(seq, 0x80, 0x00, bytes(0x08, 0x25))

    // ---- text formatting ----------------------------------------------------
    /** Wrap to ≤25-char lines, 10 lines/page, pad to ≥14 pages. Returns page strings. */
    fun formatText(input: String, charsPerLine: Int = 25, linesPerPage: Int = 10): List<String> {
        val text = input.replace("\\n", "\n")
        val wrapped = ArrayList<String>()
        for (line in text.split("\n")) {
            if (line.trim().isEmpty()) {
                wrapped.add("")
                continue
            }
            var current = ""
            for (word in line.split(" ").filter { it.isNotEmpty() }) {
                if (current.length + word.length + 1 > charsPerLine) {
                    val t = current.trim()
                    if (t.isNotEmpty()) wrapped.add(t)
                    current = "$word "
                } else {
                    current += "$word "
                }
            }
            val t = current.trim()
            if (t.isNotEmpty()) wrapped.add(t)
        }
        if (wrapped.isEmpty()) wrapped.add(text)
        while (wrapped.size < linesPerPage) wrapped.add(" ")

        val pages = ArrayList<String>()
        var i = 0
        while (i < wrapped.size) {
            val pageLines = ArrayList(wrapped.subList(i, minOf(i + linesPerPage, wrapped.size)))
            while (pageLines.size < linesPerPage) pageLines.add(" ")
            pages.add(pageLines.joinToString("\n") + " \n")
            i += linesPerPage
        }
        while (pages.size < 14) {
            pages.add(List(linesPerPage) { " " }.joinToString("\n") + " \n")
        }
        return pages
    }

    fun inputLineCount(input: String): Int =
        input.replace("\\n", "\n").split("\n").size
}
