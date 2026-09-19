// Foreground service that owns tracking + the phone-local frame server.
//
// The Even Hub package, not this service, owns the normal G2 connection. A
// direct BLE connection remains available only as an explicitly enabled debug
// path. Camera tracking continues while the activity is backgrounded.

package com.g2bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BridgeService : Service(), LifecycleOwner {

    companion object {
        private const val CHANNEL_ID = "g2bridge"
        private const val NOTIF_ID = 1
        const val HTTP_PORT = 8080
    }

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    private val binder = LocalBinder()
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    lateinit var connection: G2Connection
        private set
    lateinit var controller: WorldAnchorController
        private set
    private val frameBridge = FrameBridge()
    private var cameraTracker: FrontCameraTracker? = null
    private var httpServer: HttpServer? = null

    val state: StateFlow<G2State> get() = connection.state

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        connection = G2Connection(applicationContext)
        controller = WorldAnchorController(scope, { connection }, frameBridge::publish)
        createChannel()
        startForegroundCompat(buildNotification("Starting tracker…"))

        // Update the notification as the connection status changes.
        stateJob = scope.launch {
            state.collect { s -> updateNotification(notifText(s)) }
        }

        // This endpoint is intentionally loopback-only. The Even app WebView
        // and this native service run on the same phone.
        httpServer = HttpServer(HTTP_PORT, connection, scope, frameBridge, ::handleControl).also {
            runCatching { it.start() }.onFailure { e ->
                updateNotification("HTTP server failed: ${e.message}")
            }
        }
        startTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    fun connectGlasses() = connection.connect()
    fun disconnectGlasses() = connection.disconnect()

    fun startTracking() {
        if (cameraTracker != null) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            controller.onTrackerStatus(TrackerStatus(message = "camera permission required"))
            return
        }
        try {
            FrontCameraTracker(applicationContext, this, controller::onTrackerStatus).also {
                cameraTracker = it
                it.start()
            }
        } catch (t: Throwable) {
            cameraTracker = null
            controller.onTrackerStatus(
                TrackerStatus(message = "camera startup failed: ${t.message ?: t.javaClass.simpleName}"),
            )
        }
    }

    private fun handleControl(action: String): Boolean {
        when (action) {
            "toggle" -> controller.setPlaying(!controller.state.value.playing)
            "play" -> controller.setPlaying(true)
            "pause" -> controller.setPlaying(false)
            "restart" -> controller.restartSong()
            "set-strike" -> controller.setStrikeLine()
            "left" -> controller.nudgePiano(-0.01, 0.0, 0.0)
            "right" -> controller.nudgePiano(0.01, 0.0, 0.0)
            "up" -> controller.nudgePiano(0.0, -0.01, 0.0)
            "down" -> controller.nudgePiano(0.0, 0.01, 0.0)
            "away" -> controller.nudgePiano(0.0, 0.0, -0.02)
            "closer" -> controller.nudgePiano(0.0, 0.0, 0.02)
            "keys-down" -> controller.adjustLaneSpacing(-0.002)
            "keys-up" -> controller.adjustLaneSpacing(0.002)
            "depth-down" -> controller.adjustRunwayLength(-0.05)
            "depth-up" -> controller.adjustRunwayLength(0.05)
            "tempo-down" -> controller.adjustTempo(-4)
            "tempo-up" -> controller.adjustTempo(4)
            else -> return false
        }
        return true
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        cameraTracker?.stop()
        cameraTracker = null
        controller.setStreaming(false)
        httpServer?.stop()
        connection.shutdown()
        stateJob?.cancel()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------
    private fun notifText(s: G2State): String = when (s.status) {
        G2Status.READY -> "Tracker active · direct G2 debug connected"
        else -> "Tracker active · Even Hub bridge on 127.0.0.1:$HTTP_PORT"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Piano Tracker", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps piano head tracking and the Even Hub bridge running."
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("G2 Piano Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }
}
