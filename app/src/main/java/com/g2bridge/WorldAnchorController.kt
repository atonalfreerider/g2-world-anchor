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
    val piano: PianoCalibration? = null,
    val settings: PianoSettings = PianoSettings(),
    val preview: Bitmap? = null,
    val playing: Boolean = false,
    val songSeconds: Double = 0.0,
    val streaming: Boolean = false,
    val framesRendered: Long = 0,
    val previewFps: Double = 0.0,
    val framesSent: Long = 0,
    val g2Fps: Double = 0.0,
    val g2TransferMs: Long = 0,
    val displayMessage: String = "look at middle A key and set strike line",
)

private data class G2RenderRequest(
    val predictedEye: Pose3,
    val piano: PianoCalibration,
    val songSeconds: Double,
    val tempoBpm: Int,
)

class WorldAnchorController(
    private val scope: CoroutineScope,
    private val connection: () -> G2Connection?,
) {
    private val filter = PoseFilter()
    private val _state = MutableStateFlow(ExperimentState())
    val state: StateFlow<ExperimentState> = _state.asStateFlow()

    // BLE can take far longer than one camera frame. Keep only the newest
    // projected song frame; old animation frames are never useful.
    private val g2Frames = Channel<G2RenderRequest>(Channel.CONFLATED)
    private var streamJob: Job? = null
    private var latestG2Request: G2RenderRequest? = null
    @Volatile private var lastPoseAt = 0L
    private var previewWindowStartedAt = 0L
    private var previewWindowFrames = 0
    private var previewFps = 0.0
    private var g2WindowStartedAt = 0L
    private var g2WindowFrames = 0
    private var g2Fps = 0.0
    private val renderLock = Any()
    private val renderBuffers = arrayOfNulls<Bitmap>(6)
    private var nextRenderBuffer = 0

    private val playbackLock = Any()
    private var playbackRunning = false
    private var playbackStartedAt = 0L
    private var accumulatedSongSeconds = 0.0

    init {
        // Animation is display-clocked rather than detector-clocked. Head pose
        // updates whenever ML Kit finishes, while note motion and the phone
        // preview continue at approximately 60 Hz from the latest pose.
        scope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                renderTick()
                delay(16)
            }
        }
    }

    /** Called once for each completed face inference; animation has its own clock. */
    fun onTrackerStatus(status: TrackerStatus) {
        val now = System.nanoTime()
        val eye = status.pose?.let(filter::update)
        if (eye != null) lastPoseAt = now
        val retainedEye = eye ?: _state.value.filteredEye
        val piano = _state.value.piano

        _state.update { old ->
            val message = when {
                piano == null -> "look at middle A key and set strike line"
                eye == null -> "tracking lost; waterfall paused in last pose"
                !old.streaming -> "phone waterfall; G2 stream paused"
                connection()?.state?.value?.status != G2Status.READY -> "phone waterfall; G2 not connected"
                else -> "Hot Cross Buns; sending newest text frame"
            }
            old.copy(
                tracker = status,
                filteredEye = retainedEye,
                displayMessage = message,
            )
        }
    }

    /** Fixes the middle-key strike point along the current head-forward ray. */
    fun setStrikeLine() {
        val current = _state.value
        val eye = current.filteredEye ?: return
        val settings = current.settings
        val piano = PianoSpace.fromGaze(eye, settings)
        _state.update { it.copy(piano = piano, displayMessage = "strike line fixed; fine-tune then play") }
        refreshFrame()
    }

    fun adjustAimDistance(deltaMeters: Double) {
        _state.update { state ->
            state.copy(settings = state.settings.copy(
                aimDistanceMeters = (state.settings.aimDistanceMeters + deltaMeters).coerceIn(0.35, 1.50),
            ))
        }
    }

    fun adjustLaneSpacing(deltaMeters: Double) = updateGeometry { settings ->
        settings.copy(laneSpacingMeters = (settings.laneSpacingMeters + deltaMeters).coerceIn(0.016, 0.040))
    }

    fun adjustRunwayLength(deltaMeters: Double) = updateGeometry { settings ->
        settings.copy(runwayLengthMeters = (settings.runwayLengthMeters + deltaMeters).coerceIn(0.25, 1.20))
    }

    fun adjustTempo(deltaBpm: Int) {
        _state.update { state ->
            state.copy(settings = state.settings.copy(
                tempoBpm = (state.settings.tempoBpm + deltaBpm).coerceIn(40, 180),
            ))
        }
        refreshFrame()
    }

    /** Nudges the fixed strike point in the stationary phone-camera frame. */
    fun nudgePiano(dxMeters: Double, dyMeters: Double, dzMeters: Double) {
        _state.update { state ->
            val piano = state.piano ?: return@update state
            state.copy(piano = piano.copy(
                strikeCenterWorld = piano.strikeCenterWorld + Vec3(dxMeters, dyMeters, dzMeters),
            ))
        }
        refreshFrame()
    }

    fun setPlaying(enabled: Boolean) {
        val now = System.nanoTime()
        synchronized(playbackLock) {
            if (enabled == playbackRunning) return
            if (enabled) {
                playbackStartedAt = now
            } else {
                accumulatedSongSeconds += (now - playbackStartedAt) / 1e9
            }
            playbackRunning = enabled
        }
        _state.update { it.copy(playing = enabled, songSeconds = playbackSeconds(now)) }
        refreshFrame()
    }

    fun restartSong() {
        val now = System.nanoTime()
        synchronized(playbackLock) {
            accumulatedSongSeconds = 0.0
            playbackStartedAt = now
        }
        _state.update { it.copy(songSeconds = 0.0) }
        refreshFrame()
    }

    fun setStreaming(enabled: Boolean) {
        if (!enabled) {
            streamJob?.cancel()
            streamJob = null
            while (g2Frames.tryReceive().isSuccess) Unit
            _state.update { it.copy(streaming = false, displayMessage = "phone waterfall; G2 stream paused") }
            return
        }
        if (streamJob?.isActive == true) return
        _state.update { it.copy(streaming = true, displayMessage = "G2 stream enabled") }
        streamJob = scope.launch(Dispatchers.Default) { sendNewestFrames() }
        latestG2Request?.let { g2Frames.trySend(it) }
    }

    private fun updateGeometry(change: (PianoSettings) -> PianoSettings) {
        _state.update { state ->
            val settings = change(state.settings)
            state.copy(
                settings = settings,
                piano = state.piano?.copy(
                    laneSpacingMeters = settings.laneSpacingMeters,
                    runwayLengthMeters = settings.runwayLengthMeters,
                ),
            )
        }
        refreshFrame()
    }

    private fun playbackSeconds(now: Long = System.nanoTime()): Double = synchronized(playbackLock) {
        accumulatedSongSeconds + if (playbackRunning) (now - playbackStartedAt) / 1e9 else 0.0
    }

    private fun refreshFrame() {
        renderTick()
    }

    private fun renderTick() {
        val now = System.nanoTime()
        val state = _state.value
        val eye = state.filteredEye ?: return
        val piano = state.piano ?: return
        val seconds = playbackSeconds(now)
        val frame = renderFrame(eye, piano, seconds, state.settings.tempoBpm)
        val measuredLatency = state.g2TransferMs.toDouble().takeIf { it > 0.0 } ?: 80.0
        val request = G2RenderRequest(
            filter.predict(eye, measuredLatency),
            piano,
            seconds,
            state.settings.tempoBpm,
        )
        latestG2Request = request

        previewWindowFrames++
        if (previewWindowStartedAt == 0L) previewWindowStartedAt = now
        val elapsed = now - previewWindowStartedAt
        if (elapsed >= 1_000_000_000L) {
            previewFps = previewWindowFrames * 1_000_000_000.0 / elapsed
            previewWindowFrames = 0
            previewWindowStartedAt = now
        }
        _state.update {
            it.copy(
                preview = frame,
                songSeconds = seconds,
                framesRendered = it.framesRendered + 1,
                previewFps = previewFps,
            )
        }
        if (state.streaming) g2Frames.trySend(request)
    }

    private suspend fun sendNewestFrames() {
        while (currentCoroutineContext().isActive) {
            val request = g2Frames.receive()
            try {
                val ageMs = if (lastPoseAt == 0L) Long.MAX_VALUE else
                    (System.nanoTime() - lastPoseAt) / 1_000_000
                if (_state.value.piano == null || ageMs > 1_500) continue
                val g2 = connection()
                if (g2?.state?.value?.status != G2Status.READY) continue

                val started = System.nanoTime()
                val frame = TextWorldRenderer.renderPiano(
                    request.predictedEye,
                    request.piano,
                    request.songSeconds,
                    request.tempoBpm,
                )
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
                        displayMessage = "G2 piano waterfall; newest text frame",
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
    private fun renderFrame(
        eye: Pose3,
        piano: PianoCalibration,
        songSeconds: Double,
        tempoBpm: Int,
    ): Bitmap = synchronized(renderLock) {
        val index = nextRenderBuffer
        nextRenderBuffer = (nextRenderBuffer + 1) % renderBuffers.size
        val target = renderBuffers[index]
            ?: Bitmap.createBitmap(288, 144, Bitmap.Config.ARGB_8888).also { renderBuffers[index] = it }
        PianoWaterfallRenderer.render(eye, piano, songSeconds, tempoBpm, target)
    }
}
