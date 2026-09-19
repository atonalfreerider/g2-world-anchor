package com.g2bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PianoWaterfallTest {
    @Test
    fun downwardGazeCapturesStrikePointBelowAndTowardPhone() {
        val eye = Pose3(
            Vec3(0.0, 0.0, 0.80),
            FacePoseEstimator.headToCameraRotation(-25.0, 0.0, 0.0),
            1L,
        )
        val piano = PianoSpace.fromGaze(eye, PianoSettings(aimDistanceMeters = 0.70))
        assertTrue(piano.strikeCenterWorld.y > eye.position.y)
        assertTrue(piano.strikeCenterWorld.z < eye.position.z)
        assertEquals(0.70, (piano.strikeCenterWorld - eye.position).norm(), 1e-9)
    }

    @Test
    fun melodyUsesExpectedPitchOrderAndTwoBarLength() {
        assertEquals(listOf('B', 'A', 'G', 'B', 'A', 'G'), HotCrossBuns.notes.take(6).map { it.label })
        assertEquals(17, HotCrossBuns.notes.size)
        assertEquals(16.0, HotCrossBuns.CYCLE_BEATS, 0.0)
        assertEquals(16.0, HotCrossBuns.notes.maxOf { it.startBeat + it.durationBeats }, 0.0)
    }

    @Test
    fun noteLeadingEdgeReachesStrikeLineAtItsOnset() {
        val frame = HotCrossBuns.frame(elapsedSeconds = 0.0, tempoBpm = 60, runwayLengthMeters = 0.80)
        val firstB = frame.blocks.first { it.label == 'B' && it.active }
        assertEquals(0.0, firstB.nearMeters, 1e-9)
        assertEquals(0.10, firstB.farMeters, 1e-9)
    }

    @Test
    fun melodyLoopsWithoutAnEmptyWaterfall() {
        val beforeLoop = HotCrossBuns.frame(elapsedSeconds = 15.9, tempoBpm = 60, runwayLengthMeters = 0.80)
        assertTrue(beforeLoop.blocks.any { it.label == 'B' && it.nearMeters > 0.0 })
        assertTrue(beforeLoop.blocks.any { it.label == 'G' && it.active })
    }

    @Test
    fun pianoTextFrameIsFixedSizeAsciiAndContainsMusicGeometry() {
        val eye = Pose3(Vec3(0.0, 0.0, 0.0), Mat3.IDENTITY, 1L)
        val piano = PianoCalibration(Vec3(0.0, 0.0, 0.85), laneSpacingMeters = 0.06, runwayLengthMeters = 0.45)
        val frame = TextWorldRenderer.renderPiano(eye, piano, elapsedSeconds = 0.0, tempoBpm = 60)
        val rows = frame.split('\n')
        assertEquals(TextWorldRenderer.ROWS, rows.size)
        assertTrue(rows.all { it.length == TextWorldRenderer.COLUMNS })
        assertTrue(frame.contains('='))
        assertTrue(frame.any { it == 'B' || it == 'A' || it == 'G' })
        assertTrue(frame.all { it == '\n' || it.code in 32..126 })
    }
}
