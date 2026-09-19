package com.g2bridge

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Projects the world anchor into a compact, full-lens text frame.
 *
 * G2 image updates are too large for interactive head tracking. A native text
 * container, however, can be updated in-place with only a few BLE packets. The
 * grid deliberately uses firmware-safe ASCII because the G2 font is fixed and
 * is not guaranteed to contain arbitrary Unicode line-drawing glyphs.
 */
object TextWorldRenderer {
    const val COLUMNS = 48
    const val ROWS = 10
    const val DISPLAY_WIDTH = 576.0
    const val DISPLAY_HEIGHT = 288.0

    // Keep the same focal length as the 288 px / 13.75 degree phone preview.
    // Doubling both canvas dimensions therefore doubles the usable optical FOV.
    private const val HORIZONTAL_FOV_DEG = 27.5
    private val focalPx = DISPLAY_WIDTH / (2.0 * tan(Math.toRadians(HORIZONTAL_FOV_DEG) / 2.0))

    private val edges = arrayOf(
        0 to 1, 1 to 3, 3 to 2, 2 to 0,
        4 to 5, 5 to 7, 7 to 6, 6 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7,
    )
    private val worldAxes = arrayOf(
        Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, 1.0, 0.0),
        Vec3(0.0, 0.0, 1.0),
    )

    private data class GridPoint(val x: Double, val y: Double)

    data class TextPatch(val offset: Int, val replacedLength: Int, val content: String)

    /** Smallest contiguous edit that transforms [previous] into [next]. */
    fun diff(previous: String, next: String): TextPatch? {
        if (previous == next) return null
        var prefix = 0
        val commonLimit = minOf(previous.length, next.length)
        while (prefix < commonLimit && previous[prefix] == next[prefix]) prefix++

        var suffix = 0
        while (
            suffix < previous.length - prefix &&
            suffix < next.length - prefix &&
            previous[previous.lastIndex - suffix] == next[next.lastIndex - suffix]
        ) suffix++

        return TextPatch(
            offset = prefix,
            replacedLength = previous.length - prefix - suffix,
            content = next.substring(prefix, next.length - suffix),
        )
    }

    fun render(eye: Pose3, anchor: CubeAnchor): String {
        val cells = CharArray(COLUMNS * ROWS) { ' ' }
        val priority = IntArray(cells.size)

        val half = anchor.sizeMeters / 2.0
        val vertices = listOf(
            Vec3(-half, -half, -half), Vec3(half, -half, -half),
            Vec3(-half, half, -half), Vec3(half, half, -half),
            Vec3(-half, -half, half), Vec3(half, -half, half),
            Vec3(-half, half, half), Vec3(half, half, half),
        ).map { anchor.centerWorld + it }

        val points = vertices.map { project(eye.inverseTransform(it)) }
        val center = project(eye.inverseTransform(anchor.centerWorld))

        if (center != null) drawVanishingGuides(cells, priority, center, eye)
        for ((a, b) in edges) {
            val from = points[a] ?: continue
            val to = points[b] ?: continue
            drawLine(cells, priority, from, to, lineGlyph(from, to), 2)
        }
        if (center != null) {
            plot(cells, priority, center.x.roundToInt(), center.y.roundToInt(), 'o', 3)
            drawOffscreenLocator(cells, priority, center)
        }

        return (0 until ROWS).joinToString("\n") { row ->
            String(cells, row * COLUMNS, COLUMNS)
        }
    }

    /** Projects the piano runway and animated melody into the low-bandwidth G2 text surface. */
    fun renderPiano(
        eye: Pose3,
        piano: PianoCalibration,
        elapsedSeconds: Double,
        tempoBpm: Int,
    ): String {
        val cells = CharArray(COLUMNS * ROWS) { ' ' }
        val priority = IntArray(cells.size)
        val halfSpan = piano.laneSpacingMeters * 1.65

        // Perspective rails make the physical keyboard plane readable even
        // between notes. The inner rails separate G, A, and B.
        for (x in listOf(-halfSpan, -piano.laneSpacingMeters / 2.0, piano.laneSpacingMeters / 2.0, halfSpan)) {
            val near = project(eye.inverseTransform(piano.strikeCenterWorld + Vec3(x, 0.0, 0.0)))
            val far = project(
                eye.inverseTransform(piano.strikeCenterWorld + Vec3(x, 0.0, -piano.runwayLengthMeters)),
            )
            if (near != null && far != null) drawLine(cells, priority, near, far, '|', 1)
        }

        val frame = HotCrossBuns.frame(elapsedSeconds, tempoBpm, piano.runwayLengthMeters)
        for (distance in frame.beatMarkersMeters) {
            val left = project(
                eye.inverseTransform(piano.strikeCenterWorld + Vec3(-halfSpan, 0.0, -distance)),
            )
            val right = project(
                eye.inverseTransform(piano.strikeCenterWorld + Vec3(halfSpan, 0.0, -distance)),
            )
            if (left != null && right != null) drawLine(cells, priority, left, right, '.', 1)
        }

        val halfWidth = piano.laneSpacingMeters * 0.38
        for (block in frame.blocks.asReversed()) {
            val x = block.lane * piano.laneSpacingMeters
            val points = listOf(
                Vec3(x - halfWidth, 0.0, -block.nearMeters),
                Vec3(x + halfWidth, 0.0, -block.nearMeters),
                Vec3(x + halfWidth, 0.0, -block.farMeters),
                Vec3(x - halfWidth, 0.0, -block.farMeters),
            ).map { project(eye.inverseTransform(piano.strikeCenterWorld + it)) }
            if (points.any { it == null }) continue
            val p = points.filterNotNull()
            val glyph = if (block.active) '#' else '*'
            for (i in p.indices) drawLine(cells, priority, p[i], p[(i + 1) % p.size], glyph, 3)
            val center = project(
                eye.inverseTransform(
                    HotCrossBuns.laneCenter(piano, block.lane, (block.nearMeters + block.farMeters) / 2.0),
                ),
            )
            if (center != null) {
                plot(cells, priority, center.x.roundToInt(), center.y.roundToInt(), block.label, 4)
            }
        }

        val strikeLeft = project(eye.inverseTransform(piano.strikeCenterWorld + Vec3(-halfSpan, 0.0, 0.0)))
        val strikeRight = project(eye.inverseTransform(piano.strikeCenterWorld + Vec3(halfSpan, 0.0, 0.0)))
        if (strikeLeft != null && strikeRight != null) {
            drawLine(cells, priority, strikeLeft, strikeRight, '=', 5)
        }

        return (0 until ROWS).joinToString("\n") { row ->
            String(cells, row * COLUMNS, COLUMNS)
        }
    }

    private fun project(point: Vec3): GridPoint? {
        if (point.z <= 0.04) return null
        val px = DISPLAY_WIDTH / 2.0 + focalPx * point.x / point.z
        val py = DISPLAY_HEIGHT / 2.0 + focalPx * point.y / point.z
        return GridPoint(
            px / DISPLAY_WIDTH * (COLUMNS - 1),
            py / DISPLAY_HEIGHT * (ROWS - 1),
        )
    }

    private fun drawVanishingGuides(
        cells: CharArray,
        priority: IntArray,
        origin: GridPoint,
        eye: Pose3,
    ) {
        val worldToEye = eye.rotation.transpose()
        for (axis in worldAxes) {
            val direction = worldToEye * axis
            var dx = if (abs(direction.z) > 1e-4) focalPx * direction.x / direction.z else direction.x
            var dy = if (abs(direction.z) > 1e-4) focalPx * direction.y / direction.z else direction.y
            dx *= (COLUMNS - 1) / DISPLAY_WIDTH
            dy *= (ROWS - 1) / DISPLAY_HEIGHT
            val length = hypot(dx, dy)
            if (length < 1e-5) continue
            val reach = COLUMNS.toDouble() + ROWS
            val ux = dx / length * reach
            val uy = dy / length * reach
            drawLine(cells, priority, origin, GridPoint(origin.x + ux, origin.y + uy), '.', 1)
            drawLine(cells, priority, origin, GridPoint(origin.x - ux, origin.y - uy), '.', 1)
        }
    }

    private fun drawOffscreenLocator(cells: CharArray, priority: IntArray, target: GridPoint) {
        if (target.x in 0.0..(COLUMNS - 1).toDouble() && target.y in 0.0..(ROWS - 1).toDouble()) return
        val center = GridPoint((COLUMNS - 1) / 2.0, (ROWS - 1) / 2.0)
        drawLine(cells, priority, center, target, '.', 1)
        val edge = clipLine(center, target)?.second ?: return
        val dx = target.x - center.x
        val dy = target.y - center.y
        val arrow = if (abs(dx / COLUMNS) >= abs(dy / ROWS)) {
            if (dx >= 0.0) '>' else '<'
        } else {
            if (dy >= 0.0) 'v' else '^'
        }
        plot(cells, priority, edge.x.roundToInt(), edge.y.roundToInt(), arrow, 4)
    }

    private fun lineGlyph(from: GridPoint, to: GridPoint): Char {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return when {
            abs(dy) < 0.35 * abs(dx) -> '-'
            abs(dx) < 0.65 * abs(dy) -> '|'
            dx * dy >= 0.0 -> '\\'
            else -> '/'
        }
    }

    private fun drawLine(
        cells: CharArray,
        priority: IntArray,
        rawFrom: GridPoint,
        rawTo: GridPoint,
        glyph: Char,
        level: Int,
    ) {
        val clipped = clipLine(rawFrom, rawTo) ?: return
        var x0 = clipped.first.x.roundToInt()
        var y0 = clipped.first.y.roundToInt()
        val x1 = clipped.second.x.roundToInt()
        val y1 = clipped.second.y.roundToInt()
        val dx = abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy
        while (true) {
            plot(cells, priority, x0, y0, glyph, level)
            if (x0 == x1 && y0 == y1) break
            val twice = 2 * error
            if (twice >= dy) { error += dy; x0 += sx }
            if (twice <= dx) { error += dx; y0 += sy }
        }
    }

    /** Liang-Barsky clipping keeps extreme offscreen projections bounded. */
    private fun clipLine(from: GridPoint, to: GridPoint): Pair<GridPoint, GridPoint>? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        var enter = 0.0
        var leave = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (abs(p) < 1e-12) return q >= 0.0
            val r = q / p
            if (p < 0.0) {
                if (r > leave) return false
                if (r > enter) enter = r
            } else {
                if (r < enter) return false
                if (r < leave) leave = r
            }
            return true
        }
        if (!clip(-dx, from.x)) return null
        if (!clip(dx, (COLUMNS - 1) - from.x)) return null
        if (!clip(-dy, from.y)) return null
        if (!clip(dy, (ROWS - 1) - from.y)) return null
        return GridPoint(from.x + enter * dx, from.y + enter * dy) to
            GridPoint(from.x + leave * dx, from.y + leave * dy)
    }

    private fun plot(cells: CharArray, priority: IntArray, x: Int, y: Int, glyph: Char, level: Int) {
        if (x !in 0 until COLUMNS || y !in 0 until ROWS) return
        val index = y * COLUMNS + x
        if (level < priority[index]) return
        cells[index] = if (level == priority[index] && level >= 2 && cells[index] != glyph) '+' else glyph
        priority[index] = level
    }
}
