// Foreground service that owns the single G2Connection + the local HTTP server.
//
// Keeping the BLE link in a foreground service (type connectedDevice) means the
// glasses stay connected and the HTTP endpoint stays reachable while the app is
// backgrounded. MainActivity binds to read the shared connection state.

package com.g2bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
    private var httpServer: HttpServer? = null

    val state: StateFlow<G2State> get() = connection.state

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        connection = G2Connection(applicationContext)
        createChannel()
        startForegroundCompat(buildNotification("Starting…"))

        // Update the notification as the connection status changes.
        stateJob = scope.launch {
            state.collect { s -> updateNotification(notifText(s)) }
        }

        // Start the local HTTP server (binds to all interfaces on HTTP_PORT).
        httpServer = HttpServer(HTTP_PORT, connection, scope).also {
            runCatching { it.start() }.onFailure { e ->
                updateNotification("HTTP server failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    fun connectGlasses() = connection.connect()
    fun disconnectGlasses() = connection.disconnect()

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        httpServer?.stop()
        connection.shutdown()
        stateJob?.cancel()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------
    private fun notifText(s: G2State): String = when (s.status) {
        G2Status.READY -> "Glasses connected · http://<wifi-ip>:$HTTP_PORT"
        else -> "${s.status.name.lowercase().replaceFirstChar { it.uppercase() }}${if (s.detail.isNotEmpty()) " · ${s.detail}" else ""}"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "g2-bridge", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps the G2 glasses connected and the HTTP bridge running."
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
            .setContentTitle("g2-bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
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
