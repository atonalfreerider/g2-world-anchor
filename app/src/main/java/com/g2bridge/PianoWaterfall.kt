package com.g2bridge

import kotlin.math.ceil
import kotlin.math.floor

data class PianoCalibration(
    val strikeCenterWorld: Vec3,
    val laneSpacingMeters: Double = 0.024,
    val runwayLengthMeters: Double = 0.60,
)

data class PianoSettings(
    val aimDistanceMeters: Double = 0.75,
    val laneSpacingMeters: Double = 0.024,
    val runwayLengthMeters: Double = 0.60,
    val tempoBpm: Int = 92,
)

data class SongNote(
    val label: Char,
    val lane: Int,
    val startBeat: Double,
    val durationBeats: Double,
)

data class WaterfallBlock(
    val label: Char,
    val lane: Int,
    val nearMeters: Double,
    val farMeters: Double,
    val active: Boolean,
)

data class WaterfallFrame(
    val blocks: List<WaterfallBlock>,
    val beatMarkersMeters: List<Double>,
    val playheadBeat: Double,
)

object PianoSpace {
    /** Captures a phone-world strike point from the current head-forward ray. */
    fun fromGaze(eye: Pose3, settings: PianoSettings): PianoCalibration {
        val forward = eye.rotation * Vec3(0.0, 0.0, 1.0)
        return PianoCalibration(
            strikeCenterWorld = eye.position + forward * settings.aimDistanceMeters,
            laneSpacingMeters = settings.laneSpacingMeters,
            runwayLengthMeters = settings.runwayLengthMeters,
        )
    }
}

/** A compact two-bar arrangement of the traditional melody. */
object HotCrossBuns {
    const val CYCLE_BEATS = 16.0
    const val LOOKAHEAD_BEATS = 8.0

    val notes = listOf(
        SongNote('B', 1, 0.0, 1.0),
        SongNote('A', 0, 1.0, 1.0),
        SongNote('G', -1, 2.0, 2.0),
        SongNote('B', 1, 4.0, 1.0),
        SongNote('A', 0, 5.0, 1.0),
        SongNote('G', -1, 6.0, 2.0),
        SongNote('G', -1, 8.0, 0.5),
        SongNote('G', -1, 8.5, 0.5),
        SongNote('G', -1, 9.0, 0.5),
        SongNote('G', -1, 9.5, 0.5),
        SongNote('A', 0, 10.0, 0.5),
        SongNote('A', 0, 10.5, 0.5),
        SongNote('A', 0, 11.0, 0.5),
        SongNote('A', 0, 11.5, 0.5),
        SongNote('B', 1, 12.0, 1.0),
        SongNote('A', 0, 13.0, 1.0),
        SongNote('G', -1, 14.0, 2.0),
    )

    fun frame(elapsedSeconds: Double, tempoBpm: Int, runwayLengthMeters: Double): WaterfallFrame {
        val secondsPerBeat = 60.0 / tempoBpm.coerceIn(40, 220)
        val playheadBeat = elapsedSeconds.coerceAtLeast(0.0) / secondsPerBeat
        val cycle = floor(playheadBeat / CYCLE_BEATS).toInt()
        val blocks = buildList {
            for (cycleOffset in -1..1) {
                val cycleStart = (cycle + cycleOffset) * CYCLE_BEATS
                for (note in notes) {
                    val untilStart = cycleStart + note.startBeat - playheadBeat
                    val untilEnd = untilStart + note.durationBeats
                    if (untilEnd <= 0.0 || untilStart >= LOOKAHEAD_BEATS) continue
                    val near = untilStart.coerceAtLeast(0.0) / LOOKAHEAD_BEATS * runwayLengthMeters
                    val far = untilEnd.coerceAtMost(LOOKAHEAD_BEATS) / LOOKAHEAD_BEATS * runwayLengthMeters
                    if (far - near > 1e-5) {
                        add(WaterfallBlock(note.label, note.lane, near, far, untilStart <= 0.0))
                    }
                }
            }
        }

        val firstMarker = ceil(playheadBeat).toInt()
        val markers = (firstMarker..firstMarker + LOOKAHEAD_BEATS.toInt()).map { beat ->
            (beat - playheadBeat) / LOOKAHEAD_BEATS * runwayLengthMeters
        }.filter { it in 0.0..runwayLengthMeters }

        return WaterfallFrame(blocks, markers, playheadBeat)
    }

    fun laneCenter(calibration: PianoCalibration, lane: Int, depthMeters: Double): Vec3 =
        calibration.strikeCenterWorld +
            Vec3(lane * calibration.laneSpacingMeters, 0.0, -depthMeters)
}
