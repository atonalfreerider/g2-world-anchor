package com.g2bridge

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
) {
    companion object {
        /** Approximate Pixel front-camera FoV after CameraX rotates the image upright. */
        fun pixel10Estimate(width: Int, height: Int): CameraIntrinsics {
            val horizontalFovDeg = if (width < height) 66.0 else 84.0
            val f = width / (2.0 * tan(Math.toRadians(horizontalFovDeg) / 2.0))
            return CameraIntrinsics(f, f, (width - 1) / 2.0, (height - 1) / 2.0)
        }
    }
}

data class FacePoseSample(
    val centerX: Double,
    val centerY: Double,
    val eyeDistancePx: Double?,
    val faceWidthPx: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    val rollDeg: Double,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestampNanos: Long,
)

data class FacePoseEstimate(val pose: Pose3, val scaleSource: String)

/** Converts ML Kit face geometry into an approximate cyclopean-eye pose. */
object FacePoseEstimator {
    private const val AVERAGE_IPD_METERS = 0.063
    private const val AVERAGE_FACE_WIDTH_METERS = 0.145

    fun estimate(sample: FacePoseSample): FacePoseEstimate? {
        if (sample.frameWidth <= 0 || sample.frameHeight <= 0 || sample.faceWidthPx < 20.0) return null
        val intrinsics = CameraIntrinsics.pixel10Estimate(sample.frameWidth, sample.frameHeight)
        val yawForeshortening = cos(Math.toRadians(sample.yawDeg)).coerceAtLeast(0.45)
        val usableEyes = sample.eyeDistancePx?.takeIf { it >= 8.0 }
        val depth = if (usableEyes != null) {
            intrinsics.fx * AVERAGE_IPD_METERS * yawForeshortening / usableEyes
        } else {
            intrinsics.fx * AVERAGE_FACE_WIDTH_METERS * yawForeshortening / sample.faceWidthPx
        }.coerceIn(0.25, 2.5)

        val position = Vec3(
            (sample.centerX - intrinsics.cx) * depth / intrinsics.fx,
            (sample.centerY - intrinsics.cy) * depth / intrinsics.fy,
            depth,
        )
        return FacePoseEstimate(
            pose = Pose3(
                position = position,
                rotation = headToCameraRotation(sample.pitchDeg, sample.yawDeg, sample.rollDeg),
                timestampNanos = sample.timestampNanos,
            ),
            scaleSource = if (usableEyes != null) "eye landmarks" else "face width",
        )
    }

    /** Local +Z is gaze-forward; neutral faces toward the phone along camera -Z. */
    fun headToCameraRotation(pitchDeg: Double, yawDeg: Double, rollDeg: Double): Mat3 {
        val pitch = Math.toRadians(-pitchDeg)
        val yaw = Math.toRadians(-yawDeg)
        val roll = Math.toRadians(-rollDeg)
        val neutral = Mat3(doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0))
        return rotationZ(roll) * rotationX(pitch) * rotationY(yaw) * neutral
    }

    private fun rotationX(a: Double) = Mat3(doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, cos(a), -sin(a),
        0.0, sin(a), cos(a),
    ))

    private fun rotationY(a: Double) = Mat3(doubleArrayOf(
        cos(a), 0.0, sin(a),
        0.0, 1.0, 0.0,
        -sin(a), 0.0, cos(a),
    ))

    private fun rotationZ(a: Double) = Mat3(doubleArrayOf(
        cos(a), -sin(a), 0.0,
        sin(a), cos(a), 0.0,
        0.0, 0.0, 1.0,
    ))
}

data class TrackerStatus(
    val pose: Pose3? = null,
    val fps: Double = 0.0,
    val message: String = "camera stopped",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val scaleSource: String = "",
)

class FrontCameraTracker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onStatus: (TrackerStatus) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build(),
    )
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var activeTrackingId: Int? = null
    private var frameCount = 0
    private var fpsWindowStart = System.nanoTime()
    private var fps = 0.0

    @SuppressLint("UnsafeOptInUsageError") // CameraX ImageProxy.image is required by ML Kit InputImage.
    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                provider = providerFuture.get()
                analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyze) }
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                onStatus(TrackerStatus(message = "camera active; face the phone"))
            } catch (t: Throwable) {
                onStatus(TrackerStatus(message = "camera startup failed: ${t.message ?: t.javaClass.simpleName}"))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        analysis?.let { provider?.unbind(it) }
        detector.close()
        executor.shutdownNow()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val uprightWidth = if (rotation == 90 || rotation == 270) image.height else image.width
        val uprightHeight = if (rotation == 90 || rotation == 270) image.width else image.height
        val timestamp = image.imageInfo.timestamp
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(input)
            .addOnSuccessListener(executor) { faces -> handleFaces(faces, uprightWidth, uprightHeight, timestamp) }
            .addOnFailureListener(executor) { error ->
                onStatus(TrackerStatus(message = "face detector error: ${error.message ?: error.javaClass.simpleName}"))
            }
            .addOnCompleteListener { image.close() }
    }

    private fun handleFaces(faces: List<Face>, width: Int, height: Int, timestamp: Long) {
        updateFps()
        val face = faces.firstOrNull { it.trackingId != null && it.trackingId == activeTrackingId }
            ?: faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face == null) {
            activeTrackingId = null
            onStatus(TrackerStatus(fps = fps, message = "face not visible", frameWidth = width, frameHeight = height))
            return
        }
        activeTrackingId = face.trackingId

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val eyeDistance = if (leftEye != null && rightEye != null) {
            hypot((leftEye.x - rightEye.x).toDouble(), (leftEye.y - rightEye.y).toDouble())
        } else null
        val centerX = if (leftEye != null && rightEye != null) {
            (leftEye.x + rightEye.x) / 2.0
        } else face.boundingBox.exactCenterX().toDouble()
        val centerY = if (leftEye != null && rightEye != null) {
            (leftEye.y + rightEye.y) / 2.0
        } else face.boundingBox.top + face.boundingBox.height() * 0.42

        val estimate = FacePoseEstimator.estimate(
            FacePoseSample(
                centerX = centerX,
                centerY = centerY,
                eyeDistancePx = eyeDistance,
                faceWidthPx = face.boundingBox.width().toDouble(),
                pitchDeg = face.headEulerAngleX.toDouble(),
                yawDeg = face.headEulerAngleY.toDouble(),
                rollDeg = face.headEulerAngleZ.toDouble(),
                frameWidth = width,
                frameHeight = height,
                timestampNanos = timestamp,
            ),
        )
        onStatus(
            TrackerStatus(
                pose = estimate?.pose,
                fps = fps,
                message = if (estimate == null) "face pose unavailable" else "tracking face",
                frameWidth = width,
                frameHeight = height,
                pitchDeg = face.headEulerAngleX.toDouble(),
                yawDeg = face.headEulerAngleY.toDouble(),
                rollDeg = face.headEulerAngleZ.toDouble(),
                scaleSource = estimate?.scaleSource.orEmpty(),
            ),
        )
    }

    private fun updateFps() {
        frameCount += 1
        val now = System.nanoTime()
        val elapsed = (now - fpsWindowStart) / 1e9
        if (elapsed >= 1.0) {
            fps = frameCount / elapsed
            frameCount = 0
            fpsWindowStart = now
        }
    }
}
