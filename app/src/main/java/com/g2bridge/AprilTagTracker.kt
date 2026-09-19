package com.g2bridge

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.math.tan

data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
) {
    fun matrix(): Mat = Mat.eye(3, 3, CvType.CV_64F).also {
        it.put(0, 0, fx); it.put(1, 1, fy); it.put(0, 2, cx); it.put(1, 2, cy)
    }

    companion object {
        /** Pixel 10 selfie camera is advertised around 95° diagonal; ~84° horizontal at 4:3. */
        fun pixel10Estimate(width: Int, height: Int, horizontalFovDeg: Double = 84.0): CameraIntrinsics {
            val f = width / (2.0 * tan(Math.toRadians(horizontalFovDeg) / 2.0))
            return CameraIntrinsics(f, f, (width - 1) / 2.0, (height - 1) / 2.0)
        }
    }
}

data class TrackerStatus(
    val pose: Pose3? = null,
    val fps: Double = 0.0,
    val message: String = "camera stopped",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
)

/**
 * Detects AprilTag 36h11 id 0 and solves a metric pose with IPPE_SQUARE.
 * The tag's black detection square must measure [tagSizeMeters] edge-to-edge.
 */
class AprilTagPoseEstimator(private val tagSizeMeters: Double = 0.045) {
    private val detector: ArucoDetector
    private val distortion = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        val parameters = DetectorParameters().apply {
            set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
            set_cornerRefinementWinSize(5)
            set_cornerRefinementMaxIterations(40)
            set_cornerRefinementMinAccuracy(0.01)
            set_aprilTagQuadDecimate(1.0f)
        }
        detector = ArucoDetector(
            Objdetect.getPredefinedDictionary(Objdetect.DICT_APRILTAG_36h11),
            parameters,
        )
    }

    fun estimate(gray: Mat, intrinsics: CameraIntrinsics, timestampNanos: Long): Pose3? {
        val corners = mutableListOf<Mat>()
        val ids = Mat()
        detector.detectMarkers(gray, corners, ids)
        if (ids.empty()) return null

        var selected = -1
        for (i in 0 until ids.rows()) {
            if (ids.get(i, 0)?.firstOrNull()?.toInt() == 0) { selected = i; break }
        }
        if (selected < 0 || selected >= corners.size) return null

        val half = tagSizeMeters / 2.0
        // Required IPPE_SQUARE order: top-left, top-right, bottom-right, bottom-left.
        val objectPoints = MatOfPoint3f(
            Point3(-half, half, 0.0), Point3(half, half, 0.0),
            Point3(half, -half, 0.0), Point3(-half, -half, 0.0),
        )
        val imagePoints = MatOfPoint2f(corners[selected])
        val camera = intrinsics.matrix()
        val rvec = Mat()
        val tvec = Mat()
        val ok = Calib3d.solvePnP(
            objectPoints, imagePoints, camera, distortion, rvec, tvec,
            false, Calib3d.SOLVEPNP_IPPE_SQUARE,
        )
        if (!ok) return null

        val rotation = Mat()
        Calib3d.Rodrigues(rvec, rotation)
        val projected = MatOfPoint2f()
        Calib3d.projectPoints(objectPoints, rvec, tvec, camera, distortion, projected)
        val observed = imagePoints.toArray()
        val expected = projected.toArray()
        val rms = sqrt(observed.indices.sumOf { i ->
            val dx = observed[i].x - expected[i].x
            val dy = observed[i].y - expected[i].y
            dx * dx + dy * dy
        } / observed.size.coerceAtLeast(1))

        val t = Vec3(tvec.get(0, 0)[0], tvec.get(1, 0)[0], tvec.get(2, 0)[0])
        if (t.z !in 0.15..3.0 || rms > 4.0) return null
        val r = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) r[row * 3 + col] = rotation.get(row, col)[0]
        return Pose3(t, Mat3(r), timestampNanos, rms)
    }
}

class FrontCameraTracker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onStatus: (TrackerStatus) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val estimator = AprilTagPoseEstimator()
    private var frameCount = 0
    private var fpsWindowStart = System.nanoTime()
    private var fps = 0.0

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            onStatus(TrackerStatus(message = "camera active; show tag 0"))
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() { executor.shutdownNow() }

    private fun analyze(image: ImageProxy) {
        try {
            val rgba = rgbaMat(image)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val upright = rotate(gray, image.imageInfo.rotationDegrees)
            val intrinsics = CameraIntrinsics.pixel10Estimate(upright.cols(), upright.rows())
            val pose = estimator.estimate(upright, intrinsics, image.imageInfo.timestamp)

            frameCount++
            val now = System.nanoTime()
            val elapsed = (now - fpsWindowStart) / 1e9
            if (elapsed >= 1.0) {
                fps = frameCount / elapsed
                frameCount = 0
                fpsWindowStart = now
            }
            onStatus(
                TrackerStatus(
                    pose = pose,
                    fps = fps,
                    message = if (pose == null) "tag not visible" else "tracking tag 0",
                    frameWidth = upright.cols(),
                    frameHeight = upright.rows(),
                ),
            )
            if (upright !== gray) upright.release()
            gray.release(); rgba.release()
        } catch (t: Throwable) {
            onStatus(TrackerStatus(message = "tracker error: ${t.message ?: t.javaClass.simpleName}"))
        } finally {
            image.close()
        }
    }

    private fun rgbaMat(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowBytes = width * 4
        val packed = ByteArray(rowBytes * height)
        val row = ByteArray(plane.rowStride)
        val buffer = plane.buffer
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            val count = minOf(plane.rowStride, buffer.remaining())
            buffer.get(row, 0, count)
            if (plane.pixelStride == 4) {
                System.arraycopy(row, 0, packed, y * rowBytes, rowBytes)
            } else {
                for (x in 0 until width) for (c in 0..3) {
                    packed[y * rowBytes + x * 4 + c] = row[x * plane.pixelStride + c]
                }
            }
        }
        return Mat(height, width, CvType.CV_8UC4).also { it.put(0, 0, packed) }
    }

    private fun rotate(src: Mat, degrees: Int): Mat {
        if (degrees == 0) return src
        val dst = Mat()
        when (degrees) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src
        }
        return dst
    }
}
