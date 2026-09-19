package com.g2bridge

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ExperimentState(
    val tracker: TrackerStatus = TrackerStatus(),
    val filteredEye: Pose3? = null,
    val anchor: CubeAnchor? = null,
    val preview: Bitmap? = null,
    val streaming: Boolean = false,
    val framesSent: Long = 0,
    val displayMessage: String = "not streaming",
)

class WorldAnchorController(
    private val scope: CoroutineScope,
    private val connection: () -> G2Connection?,
) {
    private val filter = PoseFilter()
    private val _state = MutableStateFlow(ExperimentState())
    val state: StateFlow<ExperimentState> = _state.asStateFlow()
    private var streamJob: Job? = null
    private var lastPoseAt = 0L

    fun onTrackerStatus(status: TrackerStatus) {
        val eye = status.pose?.let { filter.update(it).eyeFromForeheadTag() }
        if (eye != null) lastPoseAt = System.nanoTime()
        _state.value = _state.value.copy(tracker = status, filteredEye = eye ?: _state.value.filteredEye)
    }

    fun recenter() {
        val eye = _state.value.filteredEye ?: return
        _state.value = _state.value.copy(anchor = CubeRenderer.anchorInFrontOf(eye))
    }

    fun setStreaming(enabled: Boolean) {
        if (!enabled) {
            streamJob?.cancel(); streamJob = null
            _state.value = _state.value.copy(streaming = false, displayMessage = "paused")
            return
        }
        if (streamJob?.isActive == true) return
        _state.value = _state.value.copy(streaming = true)
        streamJob = scope.launch {
            while (isActive) {
                val s = _state.value
                val ageMs = if (lastPoseAt == 0L) Long.MAX_VALUE else (System.nanoTime() - lastPoseAt) / 1_000_000
                val eye = s.filteredEye
                val anchor = s.anchor
                if (eye == null || anchor == null || ageMs > 350) {
                    _state.value = s.copy(displayMessage = if (anchor == null) "tap Recenter" else "tracking lost")
                    delay(80)
                    continue
                }
                // Approximately one camera interval + one small-container BLE paint.
                val predicted = filter.predict(eye, latencyMs = 145.0)
                val frame = CubeRenderer.render(predicted, anchor)
                _state.value = _state.value.copy(preview = frame, displayMessage = "rendering")
                val g2 = connection()
                if (g2?.state?.value?.status == G2Status.READY) {
                    g2.displayImage(frame)
                    _state.value = _state.value.copy(
                        framesSent = _state.value.framesSent + 1,
                        displayMessage = "sent to G2",
                    )
                } else {
                    _state.value = _state.value.copy(displayMessage = "phone preview; G2 not connected")
                    delay(80)
                }
            }
        }
    }
}
