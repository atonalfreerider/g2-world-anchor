package com.g2bridge

import java.util.concurrent.atomic.AtomicLong

data class BridgeFramePayload(
    val frame: String,
    val tracking: Boolean,
    val calibrated: Boolean,
    val playing: Boolean,
    val tempoBpm: Int,
    val songSeconds: Double,
)

data class BridgeFrameSnapshot(
    val sequence: Long,
    val generatedAtMs: Long,
    val payload: BridgeFramePayload,
)

/** Thread-safe newest-frame exchange between camera tracking and localhost HTTP. */
class FrameBridge {
    private val sequence = AtomicLong(0)

    @Volatile
    private var latest = BridgeFrameSnapshot(
        sequence = 0,
        generatedAtMs = System.currentTimeMillis(),
        payload = BridgeFramePayload(
            frame = diagnosticFrame("START PIANO TRACKER", "CALIBRATE STRIKE LINE"),
            tracking = false,
            calibrated = false,
            playing = false,
            tempoBpm = 92,
            songSeconds = 0.0,
        ),
    )

    @Synchronized
    fun publish(payload: BridgeFramePayload) {
        if (payload == latest.payload) return
        latest = BridgeFrameSnapshot(
            sequence = sequence.incrementAndGet(),
            generatedAtMs = System.currentTimeMillis(),
            payload = payload,
        )
    }

    fun snapshot(): BridgeFrameSnapshot = latest

    companion object {
        fun diagnosticFrame(vararg lines: String): String {
            val rows = MutableList(TextWorldRenderer.ROWS) { "" }
            val visible = lines.take(rows.size)
            val firstRow = (rows.size - visible.size) / 2
            visible.forEachIndexed { index, line -> rows[index + firstRow] = line }
            return rows.joinToString("\n") { it.take(TextWorldRenderer.COLUMNS).padEnd(TextWorldRenderer.COLUMNS) }
        }
    }
}
