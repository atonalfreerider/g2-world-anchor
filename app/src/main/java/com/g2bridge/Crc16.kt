// CRC-16/CCITT-FALSE for G2 packet framing.
// Polynomial 0x1021, init 0xFFFF, NO final XOR, MSB-first.
//
// Direct port of the reference CRC16 (itself ported from the React Native
// reference). Result is appended to packets little-endian: [crc & 0xFF, crc >> 8].

package com.g2bridge

object Crc16 {

    // Precomputed lookup table (built once).
    private val table: IntArray = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var crc = (i shl 8) and 0xFFFF
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
            t[i] = crc
        }
    }

    /** CRC-16/CCITT-FALSE over [data]. Returns a 16-bit value (0..0xFFFF). */
    fun ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            val idx = ((crc shr 8) xor (b.toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor table[idx]) and 0xFFFF
        }
        return crc
    }
}
