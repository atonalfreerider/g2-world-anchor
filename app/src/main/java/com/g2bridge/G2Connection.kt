// Even Realities G2 dual-arm BLE connection manager (Android BluetoothGatt).
//
// Port of the reference G2 connection layer. The glasses are TWO independent BLE
// peripherals (left + right arm). Text/teleprompter is mirrored to BOTH arms;
// EvenHub traffic (native containers, images, mic) goes to the RIGHT ARM ONLY.
//
// BLE PACING (the key Android adaptation):
//   CoreBluetooth exposes `canSendWriteWithoutResponse` so iOS can drain at the
//   radio's pace. Android's BluetoothGatt has no such signal; instead it requires
//   that you SERIALIZE writes - only ONE write may be outstanding at a time, and
//   the next must wait for `onCharacteristicWrite`. We therefore funnel EVERY GATT
//   write (both arms) through a single-threaded, suspending command queue keyed on
//   `onCharacteristicWrite`/`onCharacteristicWriteRequest` completion, with a short
//   timeout fallback. This both satisfies Android's one-outstanding-write rule and
//   gives us the same backpressure that prevented the iOS reassembly aborts.

package com.g2bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class G2Status { DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, READY, ERROR }

data class G2State(val status: G2Status, val detail: String = "")

@SuppressLint("MissingPermission") // permissions are checked/requested in MainActivity
class G2Connection(private val appContext: Context) {

    companion object {
        private const val TAG = "G2"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _state = MutableStateFlow(G2State(G2Status.DISCONNECTED))
    val state: StateFlow<G2State> = _state.asStateFlow()

    // Optional event callbacks (native container taps / page lifecycle).
    var onListTap: ((name: String, index: Int, itemName: String) -> Unit)? = null
    var onTextTap: ((name: String) -> Unit)? = null
    var onPageEvent: ((name: String, type: Int) -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- per-arm state ------------------------------------------------------
    private inner class Arm(val side: String) {
        var device: BluetoothDevice? = null
        var gatt: BluetoothGatt? = null
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null
        var displayWrite: BluetoothGattCharacteristic? = null
        var micNotify: BluetoothGattCharacteristic? = null
        var ready = false

        /** Resolves when the *current* outstanding write for THIS arm completes. */
        @Volatile var writeAck: CompletableDeferred<Unit>? = null

        val connected: Boolean get() = ready && gatt != null && write != null
    }

    private val left = Arm("L")
    private val right = Arm("R")
    private val arms get() = listOf(left, right)

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var userDisconnected = false
    private var heartbeatJob: Job? = null
    private var displayRefreshJob: Job? = null
    private var lastText: String? = null

    private fun log(m: String) = Log.d(TAG, m)
    private fun setStatus(s: G2Status, d: String = "") { _state.value = G2State(s, d) }

    private fun armFor(gatt: BluetoothGatt): Arm? = when (gatt) {
        left.gatt -> left
        right.gatt -> right
        else -> null
    }

    // =========================================================================
    //  CONNECT / SCAN
    // =========================================================================
    fun connect() {
        val a = adapter
        if (a == null || !a.isEnabled) { setStatus(G2Status.ERROR, "Bluetooth is off"); return }
        if (_state.value.status != G2Status.DISCONNECTED && _state.value.status != G2Status.ERROR) return
        userDisconnected = false
        // Adopt any G2 the OS is already bonded/connected to first; scan for the rest.
        for (arm in arms) resetArm(arm)
        setStatus(G2Status.SCANNING)
        adoptConnected()
        if (left.device != null && right.device != null) {
            log("both arms already connected - adopted, no scan needed")
            return
        }
        beginScan()
    }

    /** Adopt arms the OS already holds a connection to (scan won't surface those). */
    private fun adoptConnected() {
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val connected = mgr.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
        for (d in connected) {
            val name = safeName(d) ?: continue
            if (!name.contains("Even")) continue
            val arm = slotForName(name) ?: continue
            log("adopting already-connected ${arm.side}: '$name'")
            arm.device = d
            setStatus(G2Status.CONNECTING, "L:${left.device != null} R:${right.device != null}")
            arm.gatt = connectGatt(d)
        }
    }

    private fun beginScan() {
        val s = adapter?.bluetoothLeScanner
        if (s == null) { setStatus(G2Status.ERROR, "No BLE scanner"); return }
        scanner = s
        log("scanning for remaining arm(s)… (have L:${left.device != null} R:${right.device != null})")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onDiscovered(result)
            override fun onScanFailed(errorCode: Int) {
                log("scan failed: $errorCode")
                setStatus(G2Status.ERROR, "Scan failed ($errorCode)")
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No service filter - some firmwares advertise without the NUS UUID; we
        // classify by name (like the iOS reference) and stop once both arms found.
        s.startScan(null, settings, cb)

        scope.launch {
            delay(20_000)
            if (!(left.ready && right.ready)) {
                stopScan()
                if (_state.value.status != G2Status.READY) {
                    setStatus(G2Status.ERROR, "Scan timeout - L:${left.device != null} R:${right.device != null}")
                }
            }
        }
    }

    private fun stopScan() {
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        scanCallback = null
    }

    private fun onDiscovered(result: ScanResult) {
        val dev = result.device
        val name = result.scanRecord?.deviceName ?: safeName(dev) ?: return
        if (!name.contains("Even")) return
        val arm = classify(name)
        if (arm.device != null) return  // already have this side
        log("discovered ${arm.side}: '$name' rssi=${result.rssi}")
        arm.device = dev
        setStatus(G2Status.CONNECTING, "L:${left.device != null} R:${right.device != null}")
        arm.gatt = connectGatt(dev)
        if (left.device != null && right.device != null) {
            stopScan()
            log("both arms found - stopping scan")
        }
    }

    /** Classify L/R by advertised name; fall back to whichever slot is empty. */
    private fun classify(name: String): Arm {
        val u = name.uppercase()
        return when {
            u.contains("_L_") || u.contains("LEFT") -> left
            u.contains("_R_") || u.contains("RIGHT") -> right
            left.device == null -> left
            else -> right
        }
    }

    /** Like classify but returns null if the resolved slot is already filled. */
    private fun slotForName(name: String): Arm? {
        val u = name.uppercase()
        return when {
            u.contains("_L_") || u.contains("LEFT") -> if (left.device == null) left else null
            u.contains("_R_") || u.contains("RIGHT") -> if (right.device == null) right else null
            left.device == null -> left
            right.device == null -> right
            else -> null
        }
    }

    private fun safeName(d: BluetoothDevice): String? = runCatching { d.name }.getOrNull()

    private fun connectGatt(device: BluetoothDevice): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private fun resetArm(arm: Arm) {
        arm.write = null; arm.notify = null; arm.displayWrite = null; arm.micNotify = null
        arm.ready = false
        arm.writeAck?.let { if (!it.isCompleted) it.complete(Unit) }
        arm.writeAck = null
    }

    // =========================================================================
    //  GATT CALLBACK
    // =========================================================================
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val arm = armFor(gatt) ?: return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("${arm.side} connected - requesting MTU then discovering services")
                // Request a large MTU so EvenHub fragments fit in one ATT write.
                if (!gatt.requestMtu(247)) gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                onArmDisconnected(arm, status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val arm = armFor(gatt) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS && arm === right) lastMtu = mtu
            log("${arm.side} MTU=$mtu (status $status) - discovering services")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val arm = armFor(gatt) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("${arm.side} service discovery failed: $status")
                setStatus(G2Status.ERROR, "Service discovery failed")
                return
            }
            mapGatt(arm, gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            // Completes the outstanding write for this arm so the queue can advance.
            armFor(gatt)?.writeAck?.let { if (!it.isCompleted) it.complete(Unit) }
        }

        // Pre-Android 13 notify path.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleNotify(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        // Android 13+ notify path (value delivered explicitly).
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleNotify(gatt, characteristic, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            // CCCD write completed (notify enable). Nothing to do - we enable all
            // notify chars during mapGatt and proceed once write/notify exist.
        }
    }

    private fun onArmDisconnected(arm: Arm, status: Int) {
        log("${arm.side} disconnected (status $status)")
        runCatching { arm.gatt?.close() }
        resetArm(arm)
        heartbeatJob?.cancel(); heartbeatJob = null
        // Reset EvenHub session state - re-prime on reconnect.
        resetEvenHubState()
        if (userDisconnected) {
            arm.gatt = null; arm.device = null
            if (!left.ready && !right.ready) setStatus(G2Status.DISCONNECTED)
            return
        }
        setStatus(G2Status.CONNECTING, "reconnecting ${arm.side}…")
        val d = arm.device
        if (d != null) {
            arm.gatt = connectGatt(d)
        }
    }

    private fun mapGatt(arm: Arm, gatt: BluetoothGatt) {
        for (svc in gatt.services) {
            for (ch in svc.characteristics) {
                val u = ch.uuid.toString().lowercase()
                val props = ch.properties
                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                if (canNotify) {
                    enableNotify(gatt, ch)
                    when {
                        u.endsWith("2e5402") -> arm.notify = ch
                        u.endsWith("2e6402") -> arm.micNotify = ch
                        arm.notify == null -> arm.notify = ch
                    }
                }
                if (canWrite) {
                    when {
                        u.endsWith("2e5401") -> arm.write = ch
                        u.endsWith("2e6401") -> arm.displayWrite = ch
                        arm.write == null -> arm.write = ch
                    }
                }
            }
        }
        arm.ready = arm.write != null
        log("${arm.side} GATT mapped - write=${arm.write != null} notify=${arm.notify != null}")

        if (left.ready && right.ready) {
            log("BOTH ARMS READY - authenticating")
            setStatus(G2Status.AUTHENTICATING, "both arms")
            scope.launch { authenticate() }
        } else if (arm.ready) {
            setStatus(G2Status.CONNECTING, "waiting for ${if (left.ready) "R" else "L"} arm")
        } else {
            setStatus(G2Status.ERROR, "${arm.side}: no writable characteristic")
        }
    }

    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
        val value = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    // =========================================================================
    //  NOTIFICATIONS
    // =========================================================================
    private fun handleNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val arm = armFor(gatt)
        val u = ch.uuid.toString().lowercase()
        if (u.endsWith("2e6402")) {
            // Mic LC3 stream - not used by v1; ignore.
            return
        }
        val bytes = value
        if (bytes.size >= 9 && (bytes[0].toInt() and 0xFF) == 0xAA && (bytes[6].toInt() and 0xFF) == EvenHub.SID) {
            val pb = bytes.copyOfRange(8, bytes.size)
            val flag = bytes[7].toInt() and 0xFF
            if (flag == 0x01) {
                val ar = EvenHub.parseAudioRes(pb)
                if (ar != null) {
                    log("EvenHub AudioCtrRes stat=${ar.stat} magic=${ar.magic}")
                    return
                }
            }
            if (flag == 0x01 || flag == 0x06) {
                val ev = EvenHub.parseEvent(pb)
                if (ev != null) { dispatchEvent(ev); return }
            }
            val r = EvenHub.parseResponse(pb)
            log("◀ ${arm?.side ?: "?"} ${EvenHub.describeResponse(pb)}")
            resolveAck(r.magic, r.result)
        }
    }

    // ---- native-event dispatch (debounced) ----------------------------------
    private var lastTapAt = 0L
    private var lastBackAt = 0L
    private fun tapGate(): Boolean {
        val n = System.currentTimeMillis()
        if (n - lastTapAt < 350) return false
        lastTapAt = n; return true
    }
    private fun backGate(): Boolean {
        val n = System.currentTimeMillis()
        if (n - lastBackAt < 450) return false
        lastBackAt = n; return true
    }

    private fun dispatchEvent(ev: EvenHub.InEvent) {
        val isDouble = when (ev) {
            is EvenHub.InEvent.ListClick -> ev.type == 3
            is EvenHub.InEvent.TextClick -> ev.type == 3
            is EvenHub.InEvent.SysEvent -> ev.type == 3
            else -> false
        }
        if (isDouble) { if (backGate()) onDoubleTap?.invoke(); return }
        when (ev) {
            is EvenHub.InEvent.ListClick ->
                if (ev.type == 0) { if (tapGate()) onListTap?.invoke(ev.name, ev.index, ev.itemName) }
                else onPageEvent?.invoke(ev.name, ev.type)
            is EvenHub.InEvent.TextClick ->
                if (ev.type == 0) { if (tapGate()) onTextTap?.invoke(ev.name) }
                else onPageEvent?.invoke(ev.name, ev.type)
            is EvenHub.InEvent.SysEvent -> {
                if (ev.type == 5 || ev.type == 6 || ev.type == 7) {
                    // Page torn down - re-prime before the next paint.
                    evenHubPrimed = false; nativeCreated = false
                    lastListRows.clear(); lastTextContent.clear(); nativeShape = NativeShape.NONE
                    heartbeatJob?.cancel(); heartbeatJob = null
                }
                onPageEvent?.invoke("", ev.type)
            }
            is EvenHub.InEvent.PrivateEvent -> onPageEvent?.invoke(ev.name, ev.eventData)
            EvenHub.InEvent.Other -> {}
        }
    }

    // =========================================================================
    //  WRITE QUEUE (serialized - Android's one-outstanding-write rule)
    // =========================================================================
    // A single command loop drains writes one at a time. Each write suspends the
    // loop until onCharacteristicWrite (writeAck) or a short timeout. This both
    // satisfies the GATT "one in flight" rule and applies the backpressure that
    // keeps the firmware's reassembly buffers from overflowing.
    private class WriteCmd(val arm: Arm, val data: ByteArray, val done: CompletableDeferred<Unit>)
    private val writeQueue = Channel<WriteCmd>(capacity = Channel.UNLIMITED)

    init {
        scope.launch { writeLoop() }
    }

    private suspend fun writeLoop() {
        for (cmd in writeQueue) {
            performWrite(cmd.arm, cmd.data)
            cmd.done.complete(Unit)
        }
    }

    /** Actually perform one GATT write and wait for its completion (or timeout). */
    private suspend fun performWrite(arm: Arm, data: ByteArray) {
        val gatt = arm.gatt ?: return
        val ch = arm.write ?: return
        val ack = CompletableDeferred<Unit>()
        arm.writeAck = ack
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = data
                gatt.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            // Write couldn't be queued (busy/disconnected) - brief backoff so we
            // don't hot-spin, then move on (writeWithoutResponse is best-effort).
            delay(8)
            ack.complete(Unit)
            return
        }
        // writeWithoutResponse still fires onCharacteristicWrite on Android; cap the
        // wait so a missed callback can't stall the whole queue.
        withTimeoutOrNull(120) { ack.await() }
        if (!ack.isCompleted) ack.complete(Unit)
    }

    /** Enqueue one write to a single arm; suspends until the write is flushed. */
    private suspend fun writeTo(arm: Arm, data: ByteArray) {
        if (arm.gatt == null || arm.write == null) return
        val done = CompletableDeferred<Unit>()
        writeQueue.send(WriteCmd(arm, data, done))
        done.await()
    }

    /** Mirror a packet to both arms (left, brief pause, right) - text path. */
    private suspend fun sendBoth(data: ByteArray) {
        writeTo(left, data)
        delay(18)
        writeTo(right, data)
        delay(12)
    }

    // =========================================================================
    //  AUTHENTICATION
    // =========================================================================
    private suspend fun authenticate() {
        val ts = (System.currentTimeMillis() / 1000L).toInt()
        for (pkt in Protocol.authPackets(ts)) {
            sendBoth(pkt)
            delay(100)
        }
        delay(500)
        setStatus(G2Status.READY, "ready")
        log("authenticated - READY")
        startHeartbeat()
    }

    // =========================================================================
    //  TEXT DISPLAY (teleprompter)
    // =========================================================================
    /** Render [text] and keep it on the lens (the teleprompter view self-times-out). */
    suspend fun displayText(text: String) {
        imageMode = false
        lastText = text
        sendTeleprompter(text)
        startDisplayRefresh()
    }

    fun clearDisplay() {
        lastText = null
        displayRefreshJob?.cancel(); displayRefreshJob = null
    }

    private fun startDisplayRefresh() {
        displayRefreshJob?.cancel()
        displayRefreshJob = scope.launch {
            while (true) {
                delay(11_000)
                val t = lastText ?: continue
                if (!left.ready || !right.ready) continue
                log("display refresh")
                sendTeleprompter(t)
            }
        }
    }

    private val teleMutex = Mutex()

    private suspend fun sendTeleprompter(text: String) = teleMutex.withLock {
        if (!left.ready || !right.ready) { log("displayText: arms not ready"); return@withLock }
        if (imageMode) { log("sendTeleprompter skipped - image mode"); return@withLock }

        heartbeatJob?.cancel(); heartbeatJob = null
        val pages = Protocol.formatText(text)
        val totalLines = Protocol.inputLineCount(text)
        log("displayText: ${pages.size} pages, $totalLines input lines")

        var seq = 0x08
        var msgId = 0x14

        sendBoth(Protocol.displayConfig(seq, msgId)); seq = (seq + 1) and 0xFF; msgId++
        delay(150)
        sendBoth(Protocol.teleprompterInit(seq, msgId, totalLines)); seq = (seq + 1) and 0xFF; msgId++
        delay(300)

        for (i in 0 until minOf(10, pages.size)) {
            sendBoth(Protocol.contentPage(seq, msgId, i, pages[i])); seq = (seq + 1) and 0xFF; msgId++
            delay(45)
        }
        sendBoth(Protocol.marker(seq, msgId)); seq = (seq + 1) and 0xFF; msgId++
        delay(45)
        if (pages.size > 10) {
            for (i in 10 until minOf(12, pages.size)) {
                sendBoth(Protocol.contentPage(seq, msgId, i, pages[i])); seq = (seq + 1) and 0xFF; msgId++
                delay(100)
            }
        }
        sendBoth(Protocol.sync(seq, msgId)); seq = (seq + 1) and 0xFF; msgId++
        delay(45)
        if (pages.size > 12) {
            for (i in 12 until pages.size) {
                sendBoth(Protocol.contentPage(seq, msgId, i, pages[i])); seq = (seq + 1) and 0xFF; msgId++
                delay(100)
            }
        }
        log("displayText: done")
        startHeartbeat()
    }

    // =========================================================================
    //  EVENHUB - image streaming + native containers (RIGHT ARM ONLY)
    // =========================================================================
    private var evenHubPrimed = false
    private var imageMode = false
    private var nativeMode = false
    private var warmedUp = false

    private val lastListRows = HashMap<String, List<String>>()
    private val lastTextContent = HashMap<String, String>()
    private enum class NativeShape { NONE, LIST, TEXT }
    private var nativeShape = NativeShape.NONE
    private val nativeName = "hud"  // single full-lens container, list↔text in place
    private var nativeCreated = false

    private var ehSeq = 0
    private var ehMagic = 1
    private var ehSession = 1
    private var ehNeedsJump = false
    private var ehAckMisses = 0
    private val ehWindow = 4
    private val tileFP = HashMap<Int, Int>()
    private var lastRenderFP = 0

    // One centered maximum-size image container. Four tiles fill the entire
    // logical canvas, but they make every animation frame four serial BLE
    // transactions. One 288x144 surface is the practical low-latency path.
    private val grid = listOf(
        EvenHub.ImageContainer(10, "cube", 144, 72, 288, 144),
    )

    private fun nextEhSeq(): Int { val s = ehSeq; ehSeq = (ehSeq + 1) and 0xFF; return s }
    private fun nextMagic(): Int { val m = ehMagic; ehMagic = if (ehMagic >= 120) 1 else ehMagic + 1; return m }
    private fun nextSession(): Int {
        val step = if (ehNeedsJump) 2 else 1; ehNeedsJump = false
        ehSession += step
        if (ehSession >= 250) ehSession = 2
        return ehSession
    }
    private fun ehOK(r: Int?): Boolean = (r ?: 1) % 2 == 0

    /** Chunk size for EvenHub fragments: min(232, MTU-8). */
    private val ehChunk: Int
        get() {
            // Default ATT MTU 23 → 20 payload; we requested 247. Conservatively
            // cap at 232 (the iOS reference cap). 8 = envelope header.
            val mtu = lastMtu
            return maxOf(20, minOf(232, mtu - 3 - 8))
        }
    @Volatile private var lastMtu = 247

    // ---- ack tracking -------------------------------------------------------
    private inner class AckBox {
        private var resolved = false
        private var value: Int? = null
        private val deferred = CompletableDeferred<Int?>()
        fun resolve(r: Int?) { if (!resolved) { resolved = true; value = r; deferred.complete(r) } }
        suspend fun await(): Int? = deferred.await()
    }
    private val pendingAcks = HashMap<Int, AckBox>()
    private val ackLock = Mutex()

    private fun resolveAck(magic: Int, result: Int?) {
        scope.launch {
            ackLock.withLock { pendingAcks.remove(magic) }?.resolve(result)
        }
    }

    private fun resetEvenHubState() {
        evenHubPrimed = false; imageMode = false; nativeMode = false; warmedUp = false
        nativeCreated = false; nativeShape = NativeShape.NONE
        lastListRows.clear(); lastTextContent.clear()
        ehSession = 1; ehSeq = 0; ehMagic = 1; ehNeedsJump = false; ehAckMisses = 0
        tileFP.clear(); lastRenderFP = 0
        scope.launch { ackLock.withLock { pendingAcks.values.forEach { it.resolve(null) }; pendingAcks.clear() } }
    }

    /** Fire one EvenHub message (right arm) and return its ack box WITHOUT awaiting. */
    private suspend fun fireEvenHub(pb: ByteArray, magic: Int, timeoutMs: Long): AckBox {
        val box = AckBox()
        ackLock.withLock { pendingAcks[magic] = box }
        val frames = Protocol.framePb(nextEhSeq(), EvenHub.SID, EvenHub.FLAG_REQUEST, pb, ehChunk)
        for (f in frames) writeTo(right, f)
        scope.launch {
            delay(timeoutMs)
            val b = ackLock.withLock { if (pendingAcks[magic] === box) pendingAcks.remove(magic) else null }
            b?.resolve(null)
        }
        return box
    }

    private suspend fun sendEvenHubAck(pb: ByteArray, magic: Int, timeoutMs: Long = 3000): Int? =
        fireEvenHub(pb, magic, timeoutMs).await()

    /** Prelude + create the 4-tile image page. Primes the plugin task. */
    private suspend fun primeEvenHub() {
        if (nativeCreated) {
            val m = nextMagic()
            fireEvenHub(EvenHub.shutDown(magic = m), m, 1500).await()
            nativeCreated = false; nativeMode = false; evenHubPrimed = false
            lastListRows.clear(); lastTextContent.clear(); nativeShape = NativeShape.NONE
            delay(150)
        }
        if (evenHubPrimed) return
        log("EvenHub prime: prelude + create centered cube surface (chunk $ehChunk)")
        writeTo(right, EvenHub.PRELUDE)   // sid 0x01 app-launch, right arm only
        delay(500)
        val cr = sendEvenHubAck(EvenHub.createStartupPageImages(grid), 201)
        log("create ack: ${cr?.let { if (it == 0) "createOK" else "result=$it" } ?: "no-ack(createOK)"}")
        evenHubPrimed = true
        imageMode = true
        delay(200)
    }

    private val streamMutex = Mutex()

    // ---- public image API ---------------------------------------------------
    /** Display a [bitmap] in the centered 288x144 low-latency image surface. */
    suspend fun displayImage(bitmap: android.graphics.Bitmap) = streamMutex.withLock {
        if (!left.ready || !right.ready) { log("displayImage: not ready"); return@withLock }
        heartbeatJob?.cancel(); heartbeatJob = null
        imageMode = true; nativeMode = false
        clearDisplay()
        primeEvenHub()

        val rendered = EvenHub.renderBitmapTiles(bitmap, 288, 144, 288, 144)
        val tiles = rendered.mapIndexedNotNull { i, t -> if (i < grid.size) Pair(grid[i], t.bmp) else null }

        var fp = 1
        for ((_, b) in tiles) fp = fp * 31 + b.contentHashCode()
        if (fp == lastRenderFP && warmedUp) {
            log("displayImage: deduped (unchanged frame)"); startHeartbeat(); return@withLock
        }

        if (!warmedUp && tiles.isNotEmpty() && streamWarmup(grid[0], tiles[0].second)) warmedUp = true
        var ok = streamTiles(tiles)
        if (!ok) {
            recoverImageSession()
            if (!warmedUp && tiles.isNotEmpty() && streamWarmup(grid[0], tiles[0].second)) warmedUp = true
            ok = streamTiles(tiles)
        }
        lastRenderFP = if (ok) fp else 0
        startHeartbeat()
        log("displayImage: ${if (ok) "ok" else "FAILED"} (${tiles.size} tiles, $ehAckMisses lifetime ack-misses)")
    }

    /** Sacrificial first stream - the firmware drops the first Cmd=3 burst after a CREATE. */
    private suspend fun streamWarmup(c: EvenHub.ImageContainer, bmp: ByteArray): Boolean {
        val sid = nextSession()
        var idx = 0; var off = 0
        while (off < bmp.size) {
            val end = minOf(off + 3800, bmp.size)
            val magic = nextMagic()
            val pb = EvenHub.imageRawData(c.id, c.name, sid, bmp.size, idx, bmp.copyOfRange(off, end), magic)
            if (fireEvenHub(pb, magic, 10000).await() == null) {
                log("warmup ${c.name} frag $idx: no ack - abort"); return false
            }
            idx++; off = end
        }
        log("warmup ${c.name}: ok"); return true
    }

    /** Stream tiles with a sliding window + per-tile dedup + ack-miss tolerance. */
    private suspend fun streamTiles(tiles: List<Pair<EvenHub.ImageContainer, ByteArray>>): Boolean {
        val inFlight = ArrayDeque<AckBox>()
        var consecMiss = 0
        var aborted = false

        suspend fun drainOne() {
            if (inFlight.isEmpty()) return
            val r = inFlight.removeFirst().await()
            if (r == 4) consecMiss = 0
            else { consecMiss++; ehAckMisses++; if (consecMiss > 3) aborted = true }
        }

        val streamed = ArrayList<Pair<Int, Int>>()  // (containerId, fp)
        outer@ for ((c, bmp) in tiles) {
            val f = bmp.contentHashCode()
            if (tileFP[c.id] == f) { log("tile ${c.name}: deduped"); continue }
            sendImageHeartbeatInline()
            val sid = nextSession()
            var idx = 0; var off = 0
            while (off < bmp.size) {
                if (aborted) break@outer
                while (inFlight.size >= ehWindow) { drainOne(); if (aborted) break@outer }
                val end = minOf(off + 3800, bmp.size)
                val magic = nextMagic()
                val pb = EvenHub.imageRawData(c.id, c.name, sid, bmp.size, idx, bmp.copyOfRange(off, end), magic)
                inFlight.addLast(fireEvenHub(pb, magic, 1000))
                idx++; off = end
            }
            if (!aborted) streamed.add(Pair(c.id, f))
        }
        while (inFlight.isNotEmpty() && !aborted) drainOne()
        while (inFlight.isNotEmpty()) inFlight.removeFirst().await()

        if (aborted) { log("streamTiles: wedged (>3 consecutive ack misses)"); return false }
        for ((id, f) in streamed) tileFP[id] = f
        return true
    }

    /** One Cmd=12 heartbeat between messages (safe point) for the plugin watchdog. */
    private suspend fun sendImageHeartbeatInline() {
        val frames = Protocol.framePb(nextEhSeq(), EvenHub.SID, EvenHub.FLAG_REQUEST, EvenHub.heartbeat(nextMagic()), ehChunk)
        for (f in frames) writeTo(right, f)
    }

    private suspend fun recoverImageSession() {
        log("recovering image session (session jump +2 + re-prime)")
        ehNeedsJump = true
        warmedUp = false
        tileFP.clear()
        lastRenderFP = 0
        ehAckMisses = 0
        evenHubPrimed = false
        heartbeatJob?.cancel(); heartbeatJob = null
        primeEvenHub()
        heartbeatJob?.cancel(); heartbeatJob = null
    }

    // ---- native list / text HUD --------------------------------------------
    private suspend fun primeNative() {
        if (nativeCreated) return
        log("EvenHub prime (native): prelude + create '$nativeName'")
        if (!evenHubPrimed) {
            writeTo(right, EvenHub.PRELUDE)
            delay(500)
        }
        val pb = EvenHub.createList(nativeName, listOf("…"), magic = 201)
        val cr = sendEvenHubAck(pb, 201)
        log("native create ack: ${cr?.let { if (it == 0) "createOK" else "result=$it" } ?: "no-ack(createOK)"}")
        evenHubPrimed = true
        nativeMode = true
        imageMode = false
        nativeCreated = true
        nativeShape = NativeShape.LIST
        delay(200)
    }

    /** Show / update a list container (Cmd=7 rebuild). Deduped when foreground. */
    suspend fun displayList(name: String, rows: List<String>) = streamMutex.withLock {
        if (!right.ready) { log("displayList: right arm not ready"); return@withLock }
        heartbeatJob?.cancel(); heartbeatJob = null
        primeNative()
        if (nativeShape == NativeShape.LIST && lastListRows[name] == rows) {
            log("displayList $name: deduped"); startHeartbeat(); return@withLock
        }
        val magic = nextMagic()
        val r = fireEvenHub(EvenHub.rebuildList(name, rows, magic = magic), magic, 3000).await()
        if (ehOK(r)) { lastListRows[name] = rows; lastTextContent.remove(name); nativeShape = NativeShape.LIST }
        log("displayList $name (${rows.size} rows): result=${r ?: "no-ack"}")
        startHeartbeat()
    }

    /** Switch the container to a TEXT shape with [content] (Cmd=7). */
    suspend fun showText(name: String, content: String) = streamMutex.withLock {
        if (!right.ready) { log("showText: right arm not ready"); return@withLock }
        heartbeatJob?.cancel(); heartbeatJob = null
        primeNative()
        val magic = nextMagic()
        val r = fireEvenHub(EvenHub.rebuildText(name, content, captureEvents = true, magic = magic), magic, 3000).await()
        if (ehOK(r)) { lastTextContent[name] = content; lastListRows.remove(name); nativeShape = NativeShape.TEXT }
        log("showText $name (${content.toByteArray().size}B): result=${r ?: "no-ack"}")
        startHeartbeat()
    }

    /** Cheap in-place text update (Cmd=5). Promotes to TEXT shape if needed. Deduped. */
    suspend fun updateText(name: String, content: String) {
        if (!right.ready) return
        if (nativeShape != NativeShape.TEXT) { showText(name, content); return }
        streamMutex.withLock {
            heartbeatJob?.cancel(); heartbeatJob = null
            primeNative()
            if (lastTextContent[name] == content) { log("updateText $name: deduped"); startHeartbeat(); return@withLock }
            val magic = nextMagic()
            val r = fireEvenHub(EvenHub.textUpgrade(name = name, content = content, magic = magic), magic, 3000).await()
            if (ehOK(r)) lastTextContent[name] = content
            log("updateText $name (${content.toByteArray().size}B): result=${r ?: "no-ack"}")
            startHeartbeat()
        }
    }

    // =========================================================================
    //  HEARTBEAT
    // =========================================================================
    private var hbSeq = 0xC0
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                sendHeartbeat()
                delay(1_500)
            }
        }
    }

    private suspend fun sendHeartbeat() {
        if (nativeMode) {
            if (!right.ready || !evenHubPrimed) return
            val frames = Protocol.framePb(nextEhSeq(), EvenHub.SID, EvenHub.FLAG_REQUEST, EvenHub.heartbeat(nextMagic()), ehChunk)
            for (f in frames) writeTo(right, f)
            return
        }
        if (!left.ready || !right.ready) return
        if (imageMode) {
            if (!evenHubPrimed) return
            val frames = Protocol.framePb(nextEhSeq(), EvenHub.SID, EvenHub.FLAG_REQUEST, EvenHub.heartbeat(nextMagic()), ehChunk)
            for (f in frames) writeTo(right, f)
        } else {
            val hb = Protocol.heartbeat(hbSeq)
            hbSeq = (hbSeq + 1) and 0xFF
            if (hbSeq == 0) hbSeq = 0xC0
            writeTo(left, hb); writeTo(right, hb)
        }
    }

    // =========================================================================
    //  DISCONNECT / TEARDOWN
    // =========================================================================
    fun disconnect() {
        userDisconnected = true
        displayRefreshJob?.cancel(); displayRefreshJob = null
        lastText = null
        heartbeatJob?.cancel(); heartbeatJob = null
        stopScan()
        for (arm in arms) {
            runCatching { arm.gatt?.disconnect() }
            runCatching { arm.gatt?.close() }
            arm.gatt = null; arm.device = null
            resetArm(arm)
        }
        resetEvenHubState()
        setStatus(G2Status.DISCONNECTED)
    }

    /** Update the cached MTU (called from MainActivity/Service if needed). */
    fun noteMtu(mtu: Int) { lastMtu = mtu }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }
}
