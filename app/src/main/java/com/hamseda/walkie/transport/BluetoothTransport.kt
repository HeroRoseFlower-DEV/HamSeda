package com.hamseda.walkie.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.FramedSocket
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.util.AppLog
import com.hamseda.walkie.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Secondary transport: Bluetooth Classic RFCOMM.
 *
 * - Discovery via classic inquiry ([BluetoothAdapter.startDiscovery]);
 *   discovery is cancelled before connecting (required for reliable
 *   connection setup).
 * - Outgoing: [BluetoothDevice.createRfcommSocketToServiceRecord] with the
 *   fixed [Protocol.BLUETOOTH_SERVICE_UUID].
 * - Incoming: [BluetoothAdapter.listenUsingRfcommWithServiceRecord] accept
 *   loop on a dedicated thread; closing the server socket unblocks accept.
 * - Disconnect detection: RFCOMM delivers orderly shutdown as
 *   `InputStream.read() == -1` (EOF), which [FramedSocket] reports as
 *   [FramedSocket.ReadResult.Closed] — we do **not** rely on IOException
 *   alone.
 *
 * Bluetooth name/device-name is display-only, never identity.
 */
class BluetoothTransport(private val context: Context) : Transport {

    override val type = TransportType.BLUETOOTH

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _error = MutableStateFlow<TransportError?>(null)
    override val error: StateFlow<TransportError?> = _error.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _discoverable = MutableStateFlow(false)
    override val discoverable: StateFlow<Boolean> = _discoverable.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // Serializes scan starts with Stop/disconnect so a delayed retry cannot
    // resurrect discovery after the user has explicitly stopped it.
    private val discoveryLock = Any()
    private var discoveryGeneration = 0L

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var framed: FramedSocket? = null
    private var btSocket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var readerJob: Job? = null
    private var acceptThread: Thread? = null
    private var receiverRegistered = false
    private val found = mutableMapOf<String, PeerDevice>()
    private val bondedCache = mutableMapOf<String, PeerDevice>()

    /** Merges discovered + paired phones into the visible peer list. */
    private fun publishPeers() {
        _peers.value = found.values.toList() + bondedCache.values.filter {
            it.id !in found.keys
        }
    }

    /**
     * A bonded headset/speaker is not a HamSeda peer. Exclude devices that
     * advertise the Bluetooth Audio/Video major class from the candidate list
     * so the app does not try RFCOMM against ordinary audio accessories.
     *
     * Unknown classes are intentionally retained: Android/vendor stacks may
     * not expose a class for every phone, and the authenticated HamSeda
     * handshake remains the definitive protocol check.
     */
    @SuppressLint("MissingPermission")
    private fun isLikelyAudioAccessory(device: BluetoothDevice): Boolean = try {
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    } catch (_: SecurityException) {
        false
    }

    override fun availability(): Availability {
        val a = adapter ?: return Availability(false, false, "bluetooth_unsupported")
        // Register eagerly so the discoverability state is live as soon as
        // the Discovery screen opens (same reason as Wi-Fi Direct).
        registerReceiver()
        updateDiscoverable()
        return Availability(true, a.isEnabled, if (a.isEnabled) "" else "bluetooth_disabled")
    }

    override fun clearError() {
        _error.value = null
    }

    /**
     * Whether this phone is currently visible to other phones' scans.
     * Classic discovery only finds *discoverable* devices — without this,
     * two phones running the app scan forever and never see each other.
     */
    @SuppressLint("MissingPermission")
    private fun updateDiscoverable() {
        _discoverable.value = try {
            adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Lists already-paired phones as connectable peers. Discovery is not
     * required to open an RFCOMM socket to a bonded device, so this keeps
     * the app usable on devices where startDiscovery() misbehaves.
     */
    @SuppressLint("MissingPermission")
    private fun refreshBondedPeers() {
        val a = adapter ?: return
        try {
            if (!a.isEnabled) return
            val bonded = a.bondedDevices
                .filterNot { isLikelyAudioAccessory(it) }
                .map { d ->
                    val name = try {
                        d.name?.ifBlank { d.address } ?: d.address
                    } catch (_: SecurityException) {
                        d.address
                    }
                    PeerDevice(id = d.address, displayName = name, transport = TransportType.BLUETOOTH)
                }
            // HS-11: always reconcile. A successfully empty bonded set clears
            // the cache (stale peers must not linger); only a permission
            // failure leaves the old cache (unknown state, logged as such).
            bondedCache.clear()
            bonded.forEach { bondedCache[it.id] = it }
            if (bonded.isNotEmpty()) {
                AppLog.log(TAG, "found ${bonded.size} paired device(s)")
            } else {
                AppLog.log(TAG, "no paired devices")
            }
            publishPeers()
        } catch (e: SecurityException) {
            AppLog.log(TAG, "bonded-device list denied: ${e.message}")
        }
    }

    // ------------------------------------------------------------ discovery

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        val attempt = synchronized(discoveryLock) { ++discoveryGeneration }
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = true)
        if (missing.isNotEmpty()) {
            AppLog.log(TAG, "discovery blocked: missing permissions $missing")
            updateDiscoveryOutcome(attempt, error = TransportError.PermissionDenied(missing))
            return
        }
        val a = adapter ?: run {
            AppLog.log(TAG, "discovery failed: no adapter")
            updateDiscoveryOutcome(
                attempt,
                state = TransportState.UNAVAILABLE,
                error = TransportError.Unsupported("bluetooth_unsupported"),
            )
            return
        }
        if (!a.isEnabled) {
            AppLog.log(TAG, "discovery failed: radio disabled")
            updateDiscoveryOutcome(
                attempt,
                state = TransportState.UNAVAILABLE,
                error = TransportError.RadioDisabled(TransportType.BLUETOOTH),
            )
            return
        }
        if (!isCurrentDiscovery(attempt)) return
        registerReceiver()
        synchronized(discoveryLock) {
            if (attempt != discoveryGeneration) return
            found.clear()
            _peers.value = emptyList()
        }
        updateDiscoverable()
        // Paired phones are connectable without discovery — list them even
        // if the discovery scan itself fails on this device.
        refreshBondedPeers()

        when (tryStartDiscovery(a, attempt)) {
            DiscoveryStartResult.STARTED -> {
                AppLog.log(TAG, "discovery started")
                return
            }
            DiscoveryStartResult.ALREADY_ACTIVE -> {
                AppLog.log(TAG, "discovery already in progress; keeping it")
                return
            }
            DiscoveryStartResult.CANCELLED -> return
            DiscoveryStartResult.FAILED -> Unit
        }

        // Bluetooth service readiness can lag behind STATE_ON on some OEM
        // builds. Retry with bounded backoff, but abandon this generation if
        // the user stops scanning, disconnects, or starts a newer scan.
        val retryDelaysMs = longArrayOf(750L, 1_500L, 3_000L)
        for ((index, waitMs) in retryDelaysMs.withIndex()) {
            AppLog.log(
                TAG,
                "discovery start returned false; retry ${index + 1}/${retryDelaysMs.size} in ${waitMs}ms " +
                    "(adapterState=${a.state}, discovering=${a.isDiscovering})",
            )
            delay(waitMs)
            if (!isCurrentDiscovery(attempt)) {
                AppLog.log(TAG, "discovery retry cancelled because the request is no longer current")
                return
            }
            if (a.state != BluetoothAdapter.STATE_ON) {
                AppLog.log(TAG, "discovery retry stopped: adapter no longer STATE_ON (state=${a.state})")
                updateDiscoveryOutcome(
                    attempt,
                    state = TransportState.UNAVAILABLE,
                    error = TransportError.RadioDisabled(TransportType.BLUETOOTH),
                )
                return
            }
            when (tryStartDiscovery(a, attempt)) {
                DiscoveryStartResult.STARTED -> {
                    AppLog.log(TAG, "discovery started on retry ${index + 1}")
                    return
                }
                DiscoveryStartResult.ALREADY_ACTIVE -> {
                    AppLog.log(TAG, "discovery became active during retry wait")
                    return
                }
                DiscoveryStartResult.CANCELLED -> return
                DiscoveryStartResult.FAILED -> Unit
            }
        }
        if (!isCurrentDiscovery(attempt)) return
        val scanPermissionGranted = if (Build.VERSION.SDK_INT >= 31) {
            PermissionHelper.isGranted(context, android.Manifest.permission.BLUETOOTH_SCAN)
        } else null
        val fineLocationGranted = if (Build.VERSION.SDK_INT in 29..30) {
            PermissionHelper.isGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else null
        val coarseLocationGranted = if (Build.VERSION.SDK_INT < 29) {
            PermissionHelper.isGranted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        } else null
        AppLog.log(
            TAG,
            "discovery failed after bounded retries: startDiscovery() returned false " +
                "(sdk=${Build.VERSION.SDK_INT}, adapterState=${a.state}, discovering=${a.isDiscovering}, " +
                "scanPermissionGranted=$scanPermissionGranted, fineLocationGranted=$fineLocationGranted, " +
                "coarseLocationGranted=$coarseLocationGranted, scanMode=${runCatching { a.scanMode }.getOrNull()}, " +
                "bondedCandidates=${bondedCache.size})",
        )
        updateDiscoveryOutcome(
            attempt,
            state = TransportState.FAILED,
            error = TransportError.ConnectFailed(
                "bluetooth discovery failed to start; retry or restart Bluetooth",
            ),
        )
    }

    private enum class DiscoveryStartResult {
        STARTED,
        ALREADY_ACTIVE,
        FAILED,
        CANCELLED,
    }

    /** Start scan + commit the state atomically with Stop/disconnect. */
    @SuppressLint("MissingPermission")
    private fun tryStartDiscovery(
        adapter: BluetoothAdapter,
        attempt: Long,
    ): DiscoveryStartResult = synchronized(discoveryLock) {
        if (attempt != discoveryGeneration) return@synchronized DiscoveryStartResult.CANCELLED
        val alreadyActive = adapter.isDiscovering
        if (!alreadyActive && !adapter.startDiscovery()) {
            return@synchronized DiscoveryStartResult.FAILED
        }
        _state.value = TransportState.DISCOVERING
        _error.value = null
        if (alreadyActive) DiscoveryStartResult.ALREADY_ACTIVE else DiscoveryStartResult.STARTED
    }

    private fun isCurrentDiscovery(attempt: Long): Boolean =
        synchronized(discoveryLock) { attempt == discoveryGeneration }

    private fun updateDiscoveryOutcome(
        attempt: Long,
        state: TransportState? = null,
        error: TransportError? = null,
    ): Boolean = synchronized(discoveryLock) {
        if (attempt != discoveryGeneration) return@synchronized false
        state?.let { _state.value = it }
        _error.value = error
        true
    }

    override suspend fun stopDiscovery() {
        invalidateDiscovery()
    }

    /** Invalidates pending retries before cancelling the platform scan. */
    private fun invalidateDiscovery() {
        synchronized(discoveryLock) {
            discoveryGeneration++
            try {
                adapter?.cancelDiscovery()
            } catch (_: Exception) {}
            if (_state.value == TransportState.DISCOVERING) {
                _state.value = TransportState.IDLE
            }
        }
    }

    // ------------------------------------------------------------- connect

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: PeerDevice) {
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
        if (missing.isNotEmpty()) {
            AppLog.log(TAG, "connect blocked: missing permissions $missing")
            _error.value = TransportError.PermissionDenied(missing)
            return
        }
        mutex.withLock {
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.CONNECTING
            ) {
                AppLog.log(TAG, "connect ignored: already ${_state.value}")
                return
            }
            val a = adapter ?: run {
                _error.value = TransportError.Unsupported("bluetooth_unsupported")
                return
            }
            if (!a.isEnabled) {
                _error.value = TransportError.RadioDisabled(TransportType.BLUETOOTH)
                return
            }
            _state.value = TransportState.CONNECTING
            _error.value = null
            AppLog.log(TAG, "connecting to ${peer.displayName} (${peer.id})")
            try {
                a.cancelDiscovery()
            } catch (_: SecurityException) {}
            stopAcceptLocked()
            // HS-06: the blocking connect() runs on Dispatchers.IO, and the
            // deadline is enforced by a watchdog that CLOSES the socket —
            // closing unblocks the pending connect(), which withTimeout
            // alone cannot interrupt. The AtomicReference guarantees the
            // watchdog never closes a newer socket.
            scope.launch(Dispatchers.IO) {
                var socket: BluetoothSocket? = null
                val socketRef = AtomicReference<BluetoothSocket?>()
                val watchdog = launch {
                    delay(Protocol.CONNECT_TIMEOUT_MS.toLong())
                    socketRef.getAndSet(null)?.let { s ->
                        AppLog.log(TAG, "connect timed out; closing socket to unblock")
                        try { s.close() } catch (_: IOException) {}
                    }
                }
                try {
                    val device: BluetoothDevice = try {
                        a.getRemoteDevice(peer.id)
                    } catch (e: IllegalArgumentException) {
                        throw IOException("invalid device address: ${peer.id}")
                    }
                    socket = device.createRfcommSocketToServiceRecord(
                        Protocol.BLUETOOTH_SERVICE_UUID,
                    )
                    socketRef.set(socket)
                    socket.connect() // blocking; watchdog close unblocks it
                    watchdog.cancel()
                    socketRef.set(null)
                    if (!socket.isConnected) {
                        throw SocketTimeoutException("bluetooth connect timed out")
                    }
                    AppLog.log(TAG, "rfcomm connected to ${peer.id}")
                    onSocketReady(socket)
                    socket = null // owned by the transport now
                } catch (e: Exception) {
                    watchdog.cancel()
                    socketRef.set(null)
                    AppLog.log(TAG, "connect failed: ${e.javaClass.simpleName}: ${e.message}")
                    try {
                        socket?.close()
                    } catch (_: IOException) {}
                    mutex.withLock {
                        _error.value = TransportError.ConnectFailed(
                            "bluetooth connect failed: ${e.message}",
                        )
                        _state.value = TransportState.FAILED
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun listen() {
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
        if (missing.isNotEmpty()) {
            _error.value = TransportError.PermissionDenied(missing)
            return
        }
        mutex.withLock {
            val a = adapter ?: run {
                _state.value = TransportState.UNAVAILABLE
                return
            }
            if (!a.isEnabled) {
                _state.value = TransportState.UNAVAILABLE
                _error.value = TransportError.RadioDisabled(TransportType.BLUETOOTH)
                return
            }
            if (acceptThread?.isAlive == true) return
            updateDiscoverable()
            startAcceptLocked(a)
            Log.i(TAG, "listening for incoming RFCOMM connections")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAcceptLocked(a: BluetoothAdapter) {
        stopAcceptLocked()
        try {
            serverSocket = a.listenUsingRfcommWithServiceRecord(
                "HamSeda",
                Protocol.BLUETOOTH_SERVICE_UUID,
            )
            AppLog.log(TAG, "rfcomm server listening (uuid=${Protocol.BLUETOOTH_SERVICE_UUID})")
        } catch (e: IOException) {
            AppLog.log(TAG, "rfcomm listen failed: ${e.message}")
            _error.value = TransportError.ConnectFailed("rfcomm listen failed: ${e.message}")
            return
        } catch (e: SecurityException) {
            AppLog.log(TAG, "rfcomm listen denied: ${e.message}")
            _error.value = TransportError.PermissionDenied(listOf("bluetooth_connect"))
            return
        }
        val server = serverSocket ?: return
        acceptThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val socket: BluetoothSocket = server.accept()
                    Log.i(TAG, "incoming RFCOMM connection accepted")
                    scope.launch {
                        // Decide under the mutex, then release it BEFORE
                        // onSocketReady: it also locks the mutex and Mutex
                        // is not reentrant — holding it here deadlocks the
                        // listener forever (incoming connections could never
                        // complete).
                        val accept = mutex.withLock {
                            if (framed != null) {
                                try { socket.close() } catch (_: IOException) {}
                                false
                            } else {
                                try {
                                    adapter?.cancelDiscovery()
                                } catch (_: SecurityException) {}
                                true
                            }
                        }
                        if (accept) onSocketReady(socket)
                    }
                } catch (e: IOException) {
                    Log.i(TAG, "rfcomm accept ended: ${e.message}")
                    break
                }
            }
        }, "HamSeda-BtAccept").also { it.start() }
    }

    private fun stopAcceptLocked() {
        acceptThread?.interrupt()
        acceptThread = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    // --------------------------------------------------------------- socket

    private suspend fun onSocketReady(socket: BluetoothSocket) {
        mutex.withLock {
            closeSocketLocked()
            btSocket = socket
            val remote = try {
                socket.remoteDevice?.address ?: "unknown"
            } catch (_: SecurityException) {
                "unknown"
            }
            AppLog.log(TAG, "socket ready (peer=$remote)")
            framed = FramedSocket.fromStreams(socket.inputStream, socket.outputStream) {
                scope.launch { handleLost("peer closed the connection") }
            }
            _state.value = TransportState.CONNECTED
            _error.value = null
            startReaderLocked()
        }
    }

    private fun startReaderLocked() {
        readerJob?.cancel()
        val f = framed ?: return
        readerJob = scope.launch {
            while (_state.value == TransportState.CONNECTED) {
                // HS-04: readFrame() throws FrameException on protocol
                // violation — route it through the common disconnect path
                // instead of stranding the transport in CONNECTED.
                val r = try {
                    f.readFrame()
                } catch (e: FrameException) {
                    AppLog.log(TAG, "framing error: ${e.message}")
                    handleLost("framing error: ${e.message}")
                    return@launch
                } catch (e: CancellationException) {
                    throw e // normal shutdown; not an error
                } catch (e: Exception) {
                    AppLog.log(TAG, "reader failed: ${e.javaClass.simpleName}")
                    handleLost("read error: ${e.message}")
                    return@launch
                }
                when (r) {
                    is FramedSocket.ReadResult.Frame -> _incomingFrames.emit(r.frame)
                    is FramedSocket.ReadResult.Closed -> {
                        handleLost("peer closed the connection")
                        return@launch
                    }
                    is FramedSocket.ReadResult.Error -> {
                        handleLost(r.cause.message ?: "read error")
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun handleLost(reason: String) {
        mutex.withLock {
            AppLog.log(TAG, "connection lost: $reason (was ${_state.value})")
            if (_state.value == TransportState.CONNECTED) {
                _error.value = TransportError.ConnectionLost(reason)
            }
            closeSocketLocked()
            _state.value = TransportState.IDLE
        }
    }

    private fun closeSocketLocked() {
        readerJob?.cancel(); readerJob = null
        framed?.close(); framed = null
        try { btSocket?.close() } catch (_: IOException) {}
        btSocket = null
    }

    // ------------------------------------------------------------------ io

    override suspend fun sendFrame(frameBytes: ByteArray) {
        val f = mutex.withLock { framed }
            ?: throw IOException("not connected")
        try {
            f.writeFrame(frameBytes)
        } catch (e: IOException) {
            scope.launch { handleLost(e.message ?: "write error") }
            throw e
        }
    }

    override suspend fun disconnect() {
        invalidateDiscovery()
        mutex.withLock {
            closeSocketLocked()
            stopAcceptLocked()
            _state.value = TransportState.IDLE
            _error.value = null
            _peers.value = emptyList()
            found.clear()
        }
    }

    override fun close() {
        invalidateDiscovery()
        scope.launch { disconnect() }.invokeOnCompletion {
            unregisterReceiver()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------- receiver

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device ?: return
                    val address = try {
                        device.address
                    } catch (_: SecurityException) {
                        return
                    }
                    val name = try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    }
                    if (isLikelyAudioAccessory(device)) {
                        AppLog.log(TAG, "ignoring non-phone Audio/Video Bluetooth device during discovery")
                        return
                    }
                    val peer = PeerDevice(
                        id = address,
                        displayName = name?.takeIf { it.isNotBlank() } ?: address,
                        transport = TransportType.BLUETOOTH,
                    )
                    found[address] = peer
                    // Keep paired-but-not-discovered phones in the list too.
                    publishPeers()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    // This is a system-wide inquiry broadcast and may be
                    // triggered by another app; our own startDiscovery()
                    // call is responsible for setting our requested state.
                    AppLog.log(TAG, "system Bluetooth discovery started (appState=${_state.value})")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    val stillDiscovering = try {
                        adapter?.isDiscovering == true
                    } catch (_: SecurityException) {
                        false
                    }
                    AppLog.log(
                        TAG,
                        "system Bluetooth discovery finished (visiblePeers=${_peers.value.size}, " +
                            "stillDiscovering=$stillDiscovering)",
                    )
                    synchronized(discoveryLock) {
                        // An older finish broadcast must not clear state if a
                        // newer inquiry is already running.
                        if (!stillDiscovering && _state.value == TransportState.DISCOVERING) {
                            _state.value = TransportState.IDLE
                        }
                    }
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    updateDiscoverable()
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // ACTION_FOUND/DISCOVERY_FINISHED can originate from the
                // privileged Bluetooth app UID rather than android's system
                // UID. NOT_EXPORTED can silently block those broadcasts.
                // The filter is restricted to platform Bluetooth actions;
                // discovered peer identity is still established only by the
                // authenticated HamSeda protocol handshake.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            AppLog.log(TAG, "Bluetooth system receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "receiver register failed", e)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {}
        receiverRegistered = false
    }

    companion object {
        private const val TAG = "HamSedaBluetooth"
    }
}
