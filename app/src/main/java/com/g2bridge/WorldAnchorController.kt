package com.g2bridge

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ExperimentState(
    val tracker: TrackerStatus = TrackerStatus(),
    val filteredEye: Pose3? = null,
    val anchor: CubeAnchor? = null,
    val preview: Bitmap? = null,
    val streaming: Boolean = false,
    val framesRendered: Long = 0,
    val previewFps: Double = 0.0,
    val framesSent: Long = 0,
    val g2Fps: Double = 0.0,
    val g2TransferMs: Long = 0,
    val displayMessage: String = "tap Recenter",
)

private data class G2RenderRequest(val predictedEye: Pose3, val anchor: CubeAnchor)

class WorldAnchorController(
    private val scope: CoroutineScope,
    private val connection: () -> G2Connection?,
) {
    private val filter = PoseFilter()
    private val _state = MutableStateFlow(ExperimentState())
    val state: StateFlow<ExperimentState> = _state.asStateFlow()

    // BLE can take far longer than one camera frame. A conflated channel keeps
    // exactly one pending image, so latency stays bounded instead of building a
    // stale-frame queue behind the radio.
    private val g2Frames = Channel<G2RenderRequest>(Channel.CONFLATED)
    private var streamJob: Job? = null
    private var latestG2Request: G2RenderRequest? = null
    private var lastPoseAt = 0L
    private var previewWindowStartedAt = 0L
    private var previewWindowFrames = 0
    private var previewFps = 0.0
    private var g2WindowStartedAt = 0L
    private var g2WindowFrames = 0
    private var g2Fps = 0.0
    private val renderLock = Any()
    private val renderBuffers = arrayOfNulls<Bitmap>(6)
    private var nextRenderBuffer = 0

    /** Called once for each completed face inference; it is the phone render clock. */
    fun onTrackerStatus(status: TrackerStatus) {
        val now = System.nanoTime()
        val eye = status.pose?.let(filter::update)
        if (eye != null) lastPoseAt = now
        val retainedEye = eye ?: _state.value.filteredEye
        val anchor = _state.value.anchor
        val frame = if (eye != null && anchor != null) {
            // Phone presentation is immediate. BLE latency compensation belongs
            // only on the slower G2 path, not in this preview path.
            renderFrame(eye, anchor)
        } else {
            null
        }
        val g2Request = if (eye != null && anchor != null) {
            val measuredLatency = _state.value.g2TransferMs.toDouble().takeIf { it > 0.0 } ?: 80.0
            G2RenderRequest(filter.predict(eye, measuredLatency), anchor)
        } else {
            null
        }

        if (frame != null) {
            previewWindowFrames++
            if (previewWindowStartedAt == 0L) previewWindowStartedAt = now
            val elapsed = now - previewWindowStartedAt
            if (elapsed >= 1_000_000_000L) {
                previewFps = previewWindowFrames * 1_000_000_000.0 / elapsed
                previewWindowFrames = 0
                previewWindowStartedAt = now
            }
        }
        if (g2Request != null) latestG2Request = g2Request

        _state.update { old ->
            val message = when {
                anchor == null -> "tap Recenter"
                eye == null -> "tracking lost"
                !old.streaming -> "phone preview; G2 stream paused"
                connection()?.state?.value?.status != G2Status.READY -> "phone preview; G2 not connected"
                else -> "rendering live; sending newest frame"
            }
            old.copy(
                tracker = status,
                filteredEye = retainedEye,
                preview = frame ?: old.preview,
                framesRendered = old.framesRendered + if (frame != null) 1 else 0,
                previewFps = previewFps,
                displayMessage = message,
            )
        }

        if (g2Request != null && _state.value.streaming) g2Frames.trySend(g2Request)
    }

    fun recenter() {
        val eye = _state.value.filteredEye ?: return
        val anchor = CubeRenderer.anchorInFrontOf(eye)
        val frame = renderFrame(eye, anchor)
        val measuredLatency = _state.value.g2TransferMs.toDouble().takeIf { it > 0.0 } ?: 80.0
        val g2Request = G2RenderRequest(filter.predict(eye, measuredLatency), anchor)
        latestG2Request = g2Request
        _state.update {
            it.copy(
                anchor = anchor,
                preview = frame,
                framesRendered = it.framesRendered + 1,
                displayMessage = if (it.streaming) "rendering live; sending newest frame" else "phone preview; G2 stream paused",
            )
        }
        if (_state.value.streaming) g2Frames.trySend(g2Request)
    }

    fun setStreaming(enabled: Boolean) {
        if (!enabled) {
            streamJob?.cancel()
            streamJob = null
            while (g2Frames.tryReceive().isSuccess) Unit
            _state.update { it.copy(streaming = false, displayMessage = "phone preview; G2 stream paused") }
            return
        }
        if (streamJob?.isActive == true) return
        _state.update { it.copy(streaming = true, displayMessage = "G2 stream enabled") }
        streamJob = scope.launch(Dispatchers.Default) { sendNewestFrames() }
        latestG2Request?.let { g2Frames.trySend(it) }
    }

    private suspend fun sendNewestFrames() {
        while (currentCoroutineContext().isActive) {
            val request = g2Frames.receive()
            try {
                val s = _state.value
                val ageMs = if (lastPoseAt == 0L) Long.MAX_VALUE else (System.nanoTime() - lastPoseAt) / 1_000_000
                if (s.anchor == null || ageMs > 350) continue
                val g2 = connection()
                if (g2?.state?.value?.status != G2Status.READY) continue

                val started = System.nanoTime()
                val frame = TextWorldRenderer.render(request.predictedEye, request.anchor)
                val sent = g2.displayFastFrame(frame)
                val finished = System.nanoTime()
                if (!sent) continue

                g2WindowFrames++
                if (g2WindowStartedAt == 0L) g2WindowStartedAt = started
                val elapsed = finished - g2WindowStartedAt
                if (elapsed >= 1_000_000_000L) {
                    g2Fps = g2WindowFrames * 1_000_000_000.0 / elapsed
                    g2WindowFrames = 0
                    g2WindowStartedAt = finished
                }
                _state.update {
                    it.copy(
                        framesSent = it.framesSent + 1,
                        g2Fps = g2Fps,
                        g2TransferMs = (finished - started) / 1_000_000,
                        displayMessage = "G2 full-screen fast line mode",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e("WorldAnchor", "G2 frame send failed", error)
                _state.update {
                    it.copy(displayMessage = "G2 send recovered: ${error.javaClass.simpleName}")
                }
                delay(100)
            }
        }
    }

    /** Small ring buffer removes per-frame 288x144 bitmap allocation and GC stalls. */
    private fun renderFrame(eye: Pose3, anchor: CubeAnchor): Bitmap = synchronized(renderLock) {
        val index = nextRenderBuffer
        nextRenderBuffer = (nextRenderBuffer + 1) % renderBuffers.size
        val target = renderBuffers[index]
            ?: Bitmap.createBitmap(288, 144, Bitmap.Config.ARGB_8888).also { renderBuffers[index] = it }
        CubeRenderer.render(eye, anchor, target = target)
    }
}
