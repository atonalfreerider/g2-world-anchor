package com.g2bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
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
    private val worldAxes = arrayOf(
        Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, 1.0, 0.0),
        Vec3(0.0, 0.0, 1.0),
    )

    fun anchorInFrontOf(eye: Pose3, distanceMeters: Double = 0.70, sizeMeters: Double = 0.16): CubeAnchor {
        val forward = eye.rotation * Vec3(0.0, 0.0, 1.0)
        return CubeAnchor(eye.position + forward * distanceMeters, sizeMeters)
    }

    fun render(
        eye: Pose3,
        anchor: CubeAnchor,
        calibration: DisplayCalibration = DisplayCalibration(),
        target: Bitmap? = null,
    ): Bitmap {
        val bitmap = target?.takeIf {
            it.isMutable && it.width == calibration.widthPx && it.height == calibration.heightPx
        } ?: Bitmap.createBitmap(calibration.widthPx, calibration.heightPx, Bitmap.Config.ARGB_8888)
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
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(92, 92, 92)
            strokeWidth = 1.15f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
        }

        val h = anchor.sizeMeters / 2.0
        val vertices = listOf(
            Vec3(-h, -h, -h), Vec3(h, -h, -h), Vec3(-h, h, -h), Vec3(h, h, -h),
            Vec3(-h, -h, h), Vec3(h, -h, h), Vec3(-h, h, h), Vec3(h, h, h),
        ).map { anchor.centerWorld + it }

        val points = vertices.map { project(eye.inverseTransform(it), calibration) }
        val centerPoint = project(eye.inverseTransform(anchor.centerWorld), calibration)

        // These three lines remain tied to the cube's fixed world axes. Their
        // vanishing directions therefore keep pointing back to the anchor even
        // when the cube itself leaves the narrow glasses display.
        if (centerPoint != null) drawVanishingGuides(canvas, centerPoint, eye, calibration, guide)

        val path = Path()
        for ((a, b) in edges) {
            val pa = points[a] ?: continue
            val pb = points[b] ?: continue
            path.moveTo(pa.x, pa.y)
            path.lineTo(pb.x, pb.y)
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, line)

        if (centerPoint != null) {
            canvas.drawCircle(centerPoint.x, centerPoint.y, 2.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawOffscreenLocator(canvas, centerPoint, calibration)
        }
        return bitmap
    }

    private fun project(point: Vec3, calibration: DisplayCalibration): PointF? {
        if (point.z <= 0.04) return null
        return PointF(
            (calibration.centerX + calibration.focalPx * point.x / point.z).toFloat(),
            (calibration.centerY + calibration.focalPx * point.y / point.z).toFloat(),
        )
    }

    private fun drawVanishingGuides(
        canvas: Canvas,
        origin: PointF,
        eye: Pose3,
        calibration: DisplayCalibration,
        paint: Paint,
    ) {
        val worldToEye = eye.rotation.transpose()
        for (axis in worldAxes) {
            val direction = worldToEye * axis
            val dx: Float
            val dy: Float
            if (abs(direction.z) > 1e-4) {
                dx = (calibration.focalPx * direction.x / direction.z).toFloat()
                dy = (calibration.focalPx * direction.y / direction.z).toFloat()
            } else {
                dx = direction.x.toFloat()
                dy = direction.y.toFloat()
            }
            val length = hypot(dx, dy).coerceAtLeast(1e-4f)
            val reach = hypot(calibration.widthPx.toFloat(), calibration.heightPx.toFloat()) * 2.0f
            val endX = origin.x + dx / length * reach
            val endY = origin.y + dy / length * reach
            canvas.drawLine(origin.x, origin.y, endX, endY, paint)
            canvas.drawLine(origin.x, origin.y, origin.x - dx / length * reach, origin.y - dy / length * reach, paint)
        }
    }

    private fun drawOffscreenLocator(canvas: Canvas, target: PointF, calibration: DisplayCalibration) {
        if (target.x in 0f..calibration.widthPx.toFloat() && target.y in 0f..calibration.heightPx.toFloat()) return
        val cx = calibration.centerX.toFloat()
        val cy = calibration.centerY.toFloat()
        val dx = target.x - cx
        val dy = target.y - cy
        val halfW = calibration.widthPx / 2f - 7f
        val halfH = calibration.heightPx / 2f - 7f
        val scale = minOf(
            if (abs(dx) < 1e-4f) Float.MAX_VALUE else halfW / abs(dx),
            if (abs(dy) < 1e-4f) Float.MAX_VALUE else halfH / abs(dy),
        )
        val edgeX = cx + dx * scale
        val edgeY = cy + dy * scale
        val locator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.8f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(cx, cy, edgeX, edgeY, locator)
        canvas.drawCircle(edgeX, edgeY, 5f, locator)
    }
}
