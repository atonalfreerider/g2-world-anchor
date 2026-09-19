package com.g2bridge

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var service: BridgeService? = null
    private val g2State = MutableStateFlow(G2State(G2Status.DISCONNECTED, "service not bound"))
    private var stateCollectJob: Job? = null
    private lateinit var anchorController: WorldAnchorController
    private var cameraTracker: FrontCameraTracker? = null
    private var cameraStarted = false
    private var serviceBound = false
    private var connectWhenBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? BridgeService.LocalBinder)?.service
            service = svc
            if (svc != null) {
                stateCollectJob?.cancel()
                stateCollectJob = lifecycleScope.launch { svc.state.collect { g2State.value = it } }
                startCamera()
                if (connectWhenBound) {
                    connectWhenBound = false
                    svc.connectGlasses()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraTracker?.stop()
            cameraTracker = null
            cameraStarted = false
            service = null
            stateCollectJob?.cancel()
            g2State.value = G2State(G2Status.DISCONNECTED, "service disconnected")
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                ensureBridgeService(connect = connectWhenBound)
            }
            else g2State.value = G2State(G2Status.ERROR, "camera/Bluetooth permissions denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        anchorController = WorldAnchorController(lifecycleScope) { service?.connection }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ExperimentScreen(
                        glasses = g2State.collectAsState().value,
                        experiment = anchorController.state.collectAsState().value,
                        onConnect = { ensurePermissionsThen(::startBridgeAndConnect) },
                        onDisconnect = { service?.disconnectGlasses() },
                        onRecenter = anchorController::recenter,
                        onStreaming = anchorController::setStreaming,
                    )
                }
            }
        }

        if (hasAllPermissions()) ensureBridgeService(connect = false)
        else permissionLauncher.launch(requiredPermissions())
    }

    override fun onDestroy() {
        anchorController.setStreaming(false)
        cameraTracker?.stop()
        stateCollectJob?.cancel()
        if (serviceBound) runCatching { unbindService(serviceConnection) }
        super.onDestroy()
    }

    private fun startCamera() {
        if (cameraStarted) return
        val owner = service ?: return
        try {
            // The foreground service remains RESUMED while another activity is
            // visible, so CameraX and face pose do not freeze on app switches.
            val tracker = FrontCameraTracker(applicationContext, owner, anchorController::onTrackerStatus)
            cameraTracker = tracker
            tracker.start()
            cameraStarted = true
            anchorController.setStreaming(true)
        } catch (t: Throwable) {
            anchorController.onTrackerStatus(
                TrackerStatus(message = "camera startup failed: ${t.message ?: t.javaClass.simpleName}"),
            )
        }
    }

    private fun startBridgeAndConnect() {
        ensureBridgeService(connect = true)
    }

    private fun ensureBridgeService(connect: Boolean) {
        if (connect) connectWhenBound = true
        service?.let {
            startCamera()
            if (connectWhenBound) {
                connectWhenBound = false
                it.connectGlasses()
            }
            return
        }
        if (serviceBound) return
        try {
            val intent = Intent(this, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) {
                if (connect) connectWhenBound = false
                g2State.value = G2State(G2Status.ERROR, "could not bind glasses service")
            }
        } catch (t: Throwable) {
            if (connect) connectWhenBound = false
            g2State.value = G2State(G2Status.ERROR, "glasses service failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    private fun hasAllPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThen(onGranted: () -> Unit) {
        if (hasAllPermissions()) onGranted() else permissionLauncher.launch(requiredPermissions())
    }
}

@Composable
private fun ExperimentScreen(
    glasses: G2State,
    experiment: ExperimentState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRecenter: () -> Unit,
    onStreaming: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("G2 World Anchor", style = MaterialTheme.typography.headlineMedium)
        Text("On-device face detection · no marker required", style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Tracking", style = MaterialTheme.typography.labelMedium)
                Text(experiment.tracker.message, style = MaterialTheme.typography.titleMedium)
                val eye = experiment.filteredEye
                if (eye != null) {
                    Text(
                        String.format(
                            Locale.US,
                            "x %.3f  y %.3f  z %.3f m · pitch %.1f°  yaw %.1f°  roll %.1f° · %.1f fps",
                            eye.position.x, eye.position.y, eye.position.z,
                            experiment.tracker.pitchDeg, experiment.tracker.yawDeg,
                            experiment.tracker.rollDeg, experiment.tracker.fps,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (experiment.tracker.scaleSource.isNotBlank()) {
                        Text("Depth scale: ${experiment.tracker.scaleSource}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Keep your face visible to the front camera.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lens preview", style = MaterialTheme.typography.labelMedium)
                Text("G2 output: full-screen 576×288 fast line mode", style = MaterialTheme.typography.bodySmall)
                val preview = experiment.preview
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "G2 cube frame",
                        modifier = Modifier.width(288.dp).height(144.dp).background(Color.Black),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Text("No frame yet — face the camera, then tap Recenter.")
                }
                Text(
                    String.format(
                        Locale.US,
                        "%s · phone %.1f fps (%d) · G2 %.1f fps / %d ms (%d)",
                        experiment.displayMessage,
                        experiment.previewFps,
                        experiment.framesRendered,
                        experiment.g2Fps,
                        experiment.g2TransferMs,
                        experiment.framesSent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRecenter, enabled = experiment.filteredEye != null) { Text("Recenter") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = experiment.streaming, onCheckedChange = onStreaming)
                Text(" G2 stream", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Glasses", style = MaterialTheme.typography.labelMedium)
                Text(glasses.status.name.lowercase(), style = MaterialTheme.typography.titleMedium)
                if (glasses.detail.isNotBlank()) Text(glasses.detail, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onConnect, enabled = glasses.status != G2Status.READY) { Text("Connect G2") }
                    Button(onClick = onDisconnect, enabled = glasses.status != G2Status.DISCONNECTED) { Text("Disconnect") }
                }
            }
        }

        Text(
            "Setup: phone fixed in portrait orientation, front camera at eye height, 0.4–1.5 m away. " +
                "Use even frontal lighting and keep both eyes visible for the best translation estimate.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
