package com.g2bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.tan

data class DisplayCalibration(
    val widthPx: Int = 288,
    val heightPx: Int = 144,
    val horizontalFovDeg: Double = 13.75,
    val centerX: Double = 144.0,
    val centerY: Double = 72.0,
) {
    val focalPx: Double get() = widthPx / (2.0 * tan(Math.toRadians(horizontalFovDeg) / 2.0))
}

data class CubeAnchor(val centerWorld: Vec3, val sizeMeters: Double = 0.16)

object CubeRenderer {
    private val edges = arrayOf(
        0 to 1, 1 to 3, 3 to 2, 2 to 0,
        4 to 5, 5 to 7, 7 to 6, 6 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7,
    )

    fun anchorInFrontOf(eye: Pose3, distanceMeters: Double = 0.70, sizeMeters: Double = 0.16): CubeAnchor {
        val forward = eye.rotation * Vec3(0.0, 0.0, 1.0)
        return CubeAnchor(eye.position + forward * distanceMeters, sizeMeters)
    }

    fun render(eye: Pose3, anchor: CubeAnchor, calibration: DisplayCalibration = DisplayCalibration()): Bitmap {
        val bitmap = Bitmap.createBitmap(calibration.widthPx, calibration.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2.2f
            style = Paint.Style.STROKE
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 110, 110)
            strokeWidth = 5.5f
            style = Paint.Style.STROKE
        }

        val h = anchor.sizeMeters / 2.0
        val vertices = listOf(
            Vec3(-h, -h, -h), Vec3(h, -h, -h), Vec3(-h, h, -h), Vec3(h, h, -h),
            Vec3(-h, -h, h), Vec3(h, -h, h), Vec3(-h, h, h), Vec3(h, h, h),
        ).map { anchor.centerWorld + it }

        val points = vertices.map { world ->
            val p = eye.inverseTransform(world)
            if (p.z <= 0.04) null else Pair(
                (calibration.centerX + calibration.focalPx * p.x / p.z).toFloat(),
                (calibration.centerY + calibration.focalPx * p.y / p.z).toFloat(),
            )
        }

        val path = Path()
        for ((a, b) in edges) {
            val pa = points[a] ?: continue
            val pb = points[b] ?: continue
            path.moveTo(pa.first, pa.second)
            path.lineTo(pb.first, pb.second)
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, line)

        val center = eye.inverseTransform(anchor.centerWorld)
        if (center.z > 0.04) {
            val cx = (calibration.centerX + calibration.focalPx * center.x / center.z).toFloat()
            val cy = (calibration.centerY + calibration.focalPx * center.y / center.z).toFloat()
            canvas.drawCircle(cx, cy, 2.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        return bitmap
    }
}
