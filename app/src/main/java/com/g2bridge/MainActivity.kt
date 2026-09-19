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
import androidx.compose.foundation.layout.statusBarsPadding
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
    private val experimentState = MutableStateFlow(ExperimentState())
    private var stateCollectJob: Job? = null
    private var experimentCollectJob: Job? = null
    private var serviceBound = false
    private var connectWhenBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? BridgeService.LocalBinder)?.service
            service = svc
            if (svc != null) {
                stateCollectJob?.cancel()
                stateCollectJob = lifecycleScope.launch { svc.state.collect { g2State.value = it } }
                experimentCollectJob?.cancel()
                experimentCollectJob = lifecycleScope.launch {
                    svc.controller.state.collect { experimentState.value = it }
                }
                svc.startTracking()
                if (connectWhenBound) {
                    connectWhenBound = false
                    svc.connectGlasses()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stateCollectJob?.cancel()
            experimentCollectJob?.cancel()
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ExperimentScreen(
                        glasses = g2State.collectAsState().value,
                        experiment = experimentState.collectAsState().value,
                        onConnect = { ensurePermissionsThen(::startBridgeAndConnect) },
                        onDisconnect = { service?.disconnectGlasses() },
                        onSetStrikeLine = { service?.controller?.setStrikeLine() },
                        onAimDistance = { service?.controller?.adjustAimDistance(it) },
                        onNudge = { x, y, z -> service?.controller?.nudgePiano(x, y, z) },
                        onLaneSpacing = { service?.controller?.adjustLaneSpacing(it) },
                        onRunwayLength = { service?.controller?.adjustRunwayLength(it) },
                        onTempo = { service?.controller?.adjustTempo(it) },
                        onPlaying = { service?.controller?.setPlaying(it) },
                        onRestart = { service?.controller?.restartSong() },
                        onStreaming = { service?.controller?.setStreaming(it) },
                    )
                }
            }
        }

        if (hasTrackingPermission()) ensureBridgeService(connect = false)
        else permissionLauncher.launch(trackingPermissions())
    }

    override fun onDestroy() {
        stateCollectJob?.cancel()
        experimentCollectJob?.cancel()
        if (serviceBound) runCatching { unbindService(serviceConnection) }
        super.onDestroy()
    }

    private fun startBridgeAndConnect() {
        ensureBridgeService(connect = true)
    }

    private fun ensureBridgeService(connect: Boolean) {
        if (connect) connectWhenBound = true
        service?.let {
            it.startTracking()
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

    private fun trackingPermissions(): Array<String> = arrayOf(Manifest.permission.CAMERA)

    private fun directG2Permissions(): Array<String> = buildList {
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

    private fun hasTrackingPermission() = trackingPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasDirectG2Permissions() = directG2Permissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThen(onGranted: () -> Unit) {
        if (hasDirectG2Permissions()) {
            onGranted()
        } else {
            connectWhenBound = true
            permissionLauncher.launch(directG2Permissions())
        }
    }
}

@Composable
private fun ExperimentScreen(
    glasses: G2State,
    experiment: ExperimentState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetStrikeLine: () -> Unit,
    onAimDistance: (Double) -> Unit,
    onNudge: (Double, Double, Double) -> Unit,
    onLaneSpacing: (Double) -> Unit,
    onRunwayLength: (Double) -> Unit,
    onTempo: (Int) -> Unit,
    onPlaying: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onStreaming: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("G2 Piano Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("Hot Cross Buns · phone-only runtime · no marker", style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Even Hub mode", style = MaterialTheme.typography.labelMedium)
                Text("Tracker bridge active", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Calibrate here, leave this tracker running, then open G2 Piano Waterfall in Even Hub. " +
                        "The eHPK owns the glasses connection; no laptop is used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

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
                Text("Piano calibration", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (experiment.piano == null) {
                        "Look at the middle A key at the playing edge, then set the strike line."
                    } else {
                        "Strike line fixed in the phone's world frame. Use 1–2 cm nudges to align it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    String.format(
                        Locale.US,
                        "aim %.2f m · keys %.1f mm · runway %.2f m · %d BPM",
                        experiment.settings.aimDistanceMeters,
                        experiment.settings.laneSpacingMeters * 1000.0,
                        experiment.settings.runwayLengthMeters,
                        experiment.settings.tempoBpm,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onAimDistance(-0.05) }, modifier = Modifier.weight(1f)) { Text("−5 cm") }
                    Button(
                        onClick = onSetStrikeLine,
                        enabled = experiment.filteredEye != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Set strike") }
                    Button(onClick = { onAimDistance(0.05) }, modifier = Modifier.weight(1f)) { Text("+5 cm") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onNudge(-0.01, 0.0, 0.0) }, enabled = experiment.piano != null, modifier = Modifier.weight(1f)) { Text("←") }
                    Button(onClick = { onNudge(0.01, 0.0, 0.0) }, enabled = experiment.piano != null, modifier = Modifier.weight(1f)) { Text("→") }
                    Button(onClick = { onNudge(0.0, -0.01, 0.0) }, enabled = experiment.piano != null, modifier = Modifier.weight(1f)) { Text("↑") }
                    Button(onClick = { onNudge(0.0, 0.01, 0.0) }, enabled = experiment.piano != null, modifier = Modifier.weight(1f)) { Text("↓") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onNudge(0.0, 0.0, -0.02) }, enabled = experiment.piano != null, modifier = Modifier.weight(1f)) { Text("Away") }
                    Button(onClick = { onNudge(0.0, 0.0, 0.02) }, enabled = experiment.piano != null, modifier = Modifier.weight(1f)) { Text("Closer") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onLaneSpacing(-0.002) }, modifier = Modifier.weight(1f)) { Text("Keys −") }
                    Button(onClick = { onLaneSpacing(0.002) }, modifier = Modifier.weight(1f)) { Text("Keys +") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onRunwayLength(-0.05) }, modifier = Modifier.weight(1f)) { Text("Depth −") }
                    Button(onClick = { onRunwayLength(0.05) }, modifier = Modifier.weight(1f)) { Text("Depth +") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onTempo(-4) }, modifier = Modifier.weight(1f)) { Text("BPM −") }
                    Button(onClick = { onTempo(4) }, modifier = Modifier.weight(1f)) { Text("BPM +") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lens preview", style = MaterialTheme.typography.labelMedium)
                Text("eHPK output: 48×10 full-screen fast text waterfall", style = MaterialTheme.typography.bodySmall)
                val preview = experiment.preview
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Piano note waterfall preview",
                        modifier = Modifier.width(288.dp).height(144.dp).background(Color.Black),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Text("No frame yet — keep your face visible and set the strike line.")
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
            Button(
                onClick = { onPlaying(!experiment.playing) },
                enabled = experiment.piano != null,
            ) { Text(if (experiment.playing) "Pause" else "Play") }
            Button(onClick = onRestart, enabled = experiment.piano != null) { Text("Restart") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = experiment.streaming, onCheckedChange = onStreaming)
                Text(" Direct G2 debug", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Optional direct-BLE debug", style = MaterialTheme.typography.labelMedium)
                Text("Leave disconnected when using the Even Hub eHPK.", style = MaterialTheme.typography.bodySmall)
                Text(glasses.status.name.lowercase(), style = MaterialTheme.typography.titleMedium)
                if (glasses.detail.isNotBlank()) Text(glasses.detail, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onConnect, enabled = glasses.status != G2Status.READY) { Text("Connect G2") }
                    Button(onClick = onDisconnect, enabled = glasses.status != G2Status.DISCONNECTED) { Text("Disconnect") }
                }
            }
        }

        Text(
            "Setup: phone fixed vertically on the music stand with the front camera seeing your face. " +
                "Calibration uses your downward head direction plus the simulated aim distance; the runway then stays fixed to the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
