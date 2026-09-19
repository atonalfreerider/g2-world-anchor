package com.g2bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun framePbHandlesEveryChunkBoundaryWithoutSkippingBytes() {
        val chunk = 232
        val sizes = listOf(
            0, 1, 229, 230, 231, 232, 233,
            460, 461, 462, 463, 464, 465,
            692, 693, 694, 695, 696, 697,
        )
        for (size in sizes) {
            val payload = ByteArray(size) { ((it * 37 + 11) and 0xff).toByte() }
            val frames = Protocol.framePb(7, 0xe0, 0x20, payload, chunk)
            assertTrue("size=$size", frames.isNotEmpty())
            assertEquals("size=$size", frames.size, frames.first()[4].toInt() and 0xff)

            val body = ArrayList<Byte>()
            frames.forEachIndexed { index, frame ->
                assertTrue("size=$size frame=$index", frame.size <= chunk + 8)
                assertEquals(frame.size - 8, frame[3].toInt() and 0xff)
                assertEquals(index + 1, frame[5].toInt() and 0xff)
                frame.copyOfRange(8, frame.size).forEach(body::add)
            }
            val joined = body.toByteArray()
            assertTrue("last frame must contain CRC", frames.last().size >= 10)
            assertArrayEquals("size=$size", payload, joined.copyOfRange(0, joined.size - 2))
            val expectedCrc = Crc16.ccitt(payload)
            assertEquals(expectedCrc and 0xff, joined[joined.size - 2].toInt() and 0xff)
            assertEquals((expectedCrc ushr 8) and 0xff, joined.last().toInt() and 0xff)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun framePbRejectsChunkTooSmallForCrc() {
        Protocol.framePb(1, 0xe0, 0x20, byteArrayOf(1), chunk = 2)
    }
}
