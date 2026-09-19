package com.g2bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWorldRendererTest {
    private val eye = Pose3(Vec3(0.0, 0.0, 0.0), Mat3.IDENTITY, 1L)

    @Test
    fun centeredAnchorProducesFixedSizeAsciiFrame() {
        val frame = TextWorldRenderer.render(eye, CubeAnchor(Vec3(0.0, 0.0, 0.70)))
        val rows = frame.split('\n')
        assertEquals(TextWorldRenderer.ROWS, rows.size)
        assertTrue(rows.all { it.length == TextWorldRenderer.COLUMNS })
        assertTrue(frame.length < 600)
        assertTrue(frame.any { it == '-' || it == '|' || it == '/' || it == '\\' })
        assertTrue(frame.all { it == '\n' || it.code in 32..126 })
    }

    @Test
    fun offscreenAnchorProducesRightEdgeLocator() {
        val frame = TextWorldRenderer.render(eye, CubeAnchor(Vec3(1.5, 0.0, 0.70)))
        assertTrue(frame.contains('>'))
    }

    @Test
    fun diffReturnsOnlyChangedSpan() {
        val patch = TextWorldRenderer.diff("abc  123 xyz", "abc --123 xyz")!!
        assertEquals(4, patch.offset)
        assertEquals(1, patch.replacedLength)
        assertEquals("--", patch.content)
        assertEquals(null, TextWorldRenderer.diff("same", "same"))
    }
}
