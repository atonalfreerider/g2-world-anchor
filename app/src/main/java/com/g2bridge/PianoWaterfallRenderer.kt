package com.g2bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.tan

object PianoWaterfallRenderer {
    private val calibration = DisplayCalibration()

    fun render(
        eye: Pose3,
        piano: PianoCalibration,
        elapsedSeconds: Double,
        tempoBpm: Int,
        target: Bitmap? = null,
    ): Bitmap {
        val bitmap = target?.takeIf {
            it.isMutable && it.width == calibration.widthPx && it.height == calibration.heightPx
        } ?: Bitmap.createBitmap(calibration.widthPx, calibration.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 90, 90)
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(170, 170, 170)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2.2f
            style = Paint.Style.STROKE
        }
        val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f
            textAlign = Paint.Align.CENTER
        }

        val halfSpan = piano.laneSpacingMeters * 1.65
        for (x in listOf(-halfSpan, -piano.laneSpacingMeters / 2.0, piano.laneSpacingMeters / 2.0, halfSpan)) {
            drawWorldLine(
                canvas,
                eye,
                piano.strikeCenterWorld + Vec3(x, 0.0, 0.0),
                piano.strikeCenterWorld + Vec3(x, 0.0, -piano.runwayLengthMeters),
                rail,
            )
        }

        val frame = HotCrossBuns.frame(elapsedSeconds, tempoBpm, piano.runwayLengthMeters)
        for (distance in frame.beatMarkersMeters) {
            drawWorldLine(
                canvas,
                eye,
                piano.strikeCenterWorld + Vec3(-halfSpan, 0.0, -distance),
                piano.strikeCenterWorld + Vec3(halfSpan, 0.0, -distance),
                guide,
            )
        }
        drawWorldLine(
            canvas,
            eye,
            piano.strikeCenterWorld + Vec3(-halfSpan, 0.0, 0.0),
            piano.strikeCenterWorld + Vec3(halfSpan, 0.0, 0.0),
            note,
        )

        val halfWidth = piano.laneSpacingMeters * 0.38
        for (block in frame.blocks.asReversed()) {
            val x = block.lane * piano.laneSpacingMeters
            val corners = listOf(
                piano.strikeCenterWorld + Vec3(x - halfWidth, 0.0, -block.nearMeters),
                piano.strikeCenterWorld + Vec3(x + halfWidth, 0.0, -block.nearMeters),
                piano.strikeCenterWorld + Vec3(x + halfWidth, 0.0, -block.farMeters),
                piano.strikeCenterWorld + Vec3(x - halfWidth, 0.0, -block.farMeters),
            ).map { project(eye.inverseTransform(it)) }
            if (corners.any { it == null }) continue
            val points = corners.filterNotNull()
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1..3) lineTo(points[i].x, points[i].y)
                close()
            }
            canvas.drawPath(path, if (block.active) active else note)
            val center = project(
                eye.inverseTransform(
                    HotCrossBuns.laneCenter(piano, block.lane, (block.nearMeters + block.farMeters) / 2.0),
                ),
            )
            if (!block.active && center != null) {
                canvas.drawText(block.label.toString(), center.x, center.y + 4f, label)
            }
        }
        return bitmap
    }

    private fun drawWorldLine(canvas: Canvas, eye: Pose3, a: Vec3, b: Vec3, paint: Paint) {
        val pa = project(eye.inverseTransform(a)) ?: return
        val pb = project(eye.inverseTransform(b)) ?: return
        canvas.drawLine(pa.x, pa.y, pb.x, pb.y, paint)
    }

    private fun project(point: Vec3): PointF? {
        if (point.z <= 0.04) return null
        val focal = calibration.widthPx /
            (2.0 * tan(Math.toRadians(calibration.horizontalFovDeg) / 2.0))
        return PointF(
            (calibration.centerX + focal * point.x / point.z).toFloat(),
            (calibration.centerY + focal * point.y / point.z).toFloat(),
        )
    }
}
