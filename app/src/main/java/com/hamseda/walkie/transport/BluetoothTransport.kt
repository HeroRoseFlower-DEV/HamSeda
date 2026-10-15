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
import android.os.SystemClock
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
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
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

    private val _incomingFrames = MutableSharedFlow<ByteArray>()
    override val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // Serializes incoming-frame publication with socket replacement. A reader
    // from an old RFCOMM socket must never publish frames into a later session.
    private val readerGate = Any()

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

    // Outgoing RFCOMM connect() is a blocking platform call. Keep its job and
    // socket reference so disconnect() can close it immediately instead of
    // allowing a delayed connection to resurrect CONNECTED afterwards.
    private val connectionGeneration = AtomicLong(0L)
    private var connectJob: Job? = null
    private var pendingConnectSocket: AtomicReference<BluetoothSocket?>? = null
    private var receiverRegistered = false
    // Receiver callbacks and scan coroutines can touch these maps on different
    // dispatchers. Bound the cache even though ACTION_FOUND is a protected
    // platform broadcast; a dense environment can still contain many devices.
    private val peerLock = Any()
    private val found = LinkedHashMap<String, PeerDevice>()
    /** Monotonic timestamps for currently discovered (unbonded) peers. */
    private val foundSeenAt = LinkedHashMap<String, Long>()
    private val bondedCache = LinkedHashMap<String, PeerDevice>()

    // Windowed write telemetry avoids per-packet logs and never records audio contents.
    private val rfcommWriteStatsLock = Any()
    private var rfcommWriteFrames = 0
    private var rfcommWriteCalls = 0
    private var rfcommWriteFailedCalls = 0
    private var rfcommWriteBytes = 0L
    private var rfcommWriteTotalNanos = 0L
    private var rfcommWriteMaxNanos = 0L
    private var rfcommWriteMaxBatchFrames = 0

    /** Merge a bounded snapshot without racing receiver and scan callbacks. */
    private fun publishPeers() {
        val nowMs = SystemClock.elapsedRealtime()
        val (snapshot, expiredCount) = synchronized(peerLock) {
            val stale = stalePeerIds(foundSeenAt, nowMs, FOUND_PEER_TTL_MS)
            stale.forEach { id ->
                found.remove(id)
                foundSeenAt.remove(id)
            }
            val result = ArrayList<PeerDevice>(MAX_VISIBLE_PEERS)
            found.values.take(MAX_DISCOVERED_PEERS).forEach(result::add)
            for (peer in bondedCache.values) {
                if (result.size >= MAX_VISIBLE_PEERS) break
                if (peer.id !in found && result.none { it.id == peer.id }) result.add(peer)
            }
            result to stale.size
        }
        if (expiredCount > 0) {
            AppLog.log(TAG, "expiring $expiredCount stale discovered peer(s)")
        }
        _peers.value = snapshot
    }

    private fun isUsableBluetoothAddress(address: String): Boolean =
        BluetoothAdapter.checkBluetoothAddress(address) &&
            !address.equals("02:00:00:00:00:00", ignoreCase = true) &&
            !address.equals("00:00:00:00:00:00", ignoreCase = true) &&
            !address.equals("FF:FF:FF:FF:FF:FF", ignoreCase = true)

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
        // Adapter state and scan mode require BLUETOOTH_CONNECT on Android 12+.
        // TransportManager calls availability during startup, before the user
        // necessarily grants that runtime permission.
        if (PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false).isNotEmpty()) {
            return Availability(true, false, "bluetooth_permission_missing")
        }
        return try {
            val enabled = a.isEnabled
            registerReceiver()
            updateDiscoverable()
            Availability(true, enabled, if (enabled) "" else "bluetooth_disabled")
        } catch (e: SecurityException) {
            AppLog.log(TAG, "adapter availability denied: ${e.message}")
            Availability(true, false, "bluetooth_permission_missing")
        }
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
            synchronized(peerLock) {
                bondedCache.clear()
                bonded.take(MAX_VISIBLE_PEERS).forEach { bondedCache[it.id] = it }
            }
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
            updateDiscoveryOutcome(
                attempt,
                state = TransportState.UNAVAILABLE,
                error = TransportError.PermissionDenied(missing),
            )
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
        val enabled = try {
            a.isEnabled
        } catch (e: SecurityException) {
            failDiscoveryAfterSecurityException(attempt, "read adapter state", e)
            return
        }
        if (!enabled) {
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
            synchronized(peerLock) {
                found.clear()
                foundSeenAt.clear()
            }
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
            DiscoveryStartResult.CANCELLED,
            DiscoveryStartResult.PERMISSION_ERROR -> return
            DiscoveryStartResult.FAILED -> Unit
        }

        // Bluetooth service readiness can lag behind STATE_ON on some OEM
        // builds. Retry with bounded backoff, but abandon this generation if
        // the user stops scanning, disconnects, or starts a newer scan.
        val retryDelaysMs = longArrayOf(750L, 1_500L, 3_000L)
        for ((index, waitMs) in retryDelaysMs.withIndex()) {
            val retrySnapshot = try {
                a.state to a.isDiscovering
            } catch (e: SecurityException) {
                failDiscoveryAfterSecurityException(attempt, "inspect retry state", e)
                return
            }
            AppLog.log(
                TAG,
                "discovery start returned false; retry ${index + 1}/${retryDelaysMs.size} in ${waitMs}ms " +
                    "(adapterState=${retrySnapshot.first}, discovering=${retrySnapshot.second})",
            )
            delay(waitMs)
            if (!isCurrentDiscovery(attempt)) {
                AppLog.log(TAG, "discovery retry cancelled because the request is no longer current")
                return
            }
            val retryState = try {
                a.state
            } catch (e: SecurityException) {
                failDiscoveryAfterSecurityException(attempt, "read adapter state during retry", e)
                return
            }
            if (retryState != BluetoothAdapter.STATE_ON) {
                AppLog.log(TAG, "discovery retry stopped: adapter no longer STATE_ON (state=$retryState)")
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
                DiscoveryStartResult.CANCELLED,
                DiscoveryStartResult.PERMISSION_ERROR -> return
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
        val finalSnapshot = try {
            Triple(a.state, a.isDiscovering, runCatching { a.scanMode }.getOrNull())
        } catch (e: SecurityException) {
            failDiscoveryAfterSecurityException(attempt, "collect final scan diagnostics", e)
            return
        }
        AppLog.log(
            TAG,
            "discovery failed after bounded retries: startDiscovery() returned false " +
                "(sdk=${Build.VERSION.SDK_INT}, adapterState=${finalSnapshot.first}, discovering=${finalSnapshot.second}, " +
                "scanPermissionGranted=$scanPermissionGranted, fineLocationGranted=$fineLocationGranted, " +
                "coarseLocationGranted=$coarseLocationGranted, scanMode=${finalSnapshot.third}, " +
                "bondedCandidates=${synchronized(peerLock) { bondedCache.size }})",
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
        PERMISSION_ERROR,
        CANCELLED,
    }

    /**
     * Android permissions can be revoked between our preflight check and the
     * Bluetooth binder call. Convert that race into an observable transport
     * error instead of letting the discovery coroutine crash.
     */
    private fun failDiscoveryAfterSecurityException(
        attempt: Long,
        operation: String,
        error: SecurityException,
    ) {
        AppLog.log(TAG, "Bluetooth discovery denied during $operation: ${error.message}")
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = true)
        if (missing.isNotEmpty()) {
            updateDiscoveryOutcome(
                attempt,
                state = TransportState.UNAVAILABLE,
                error = TransportError.PermissionDenied(missing),
            )
        } else {
            updateDiscoveryOutcome(
                attempt,
                state = TransportState.FAILED,
                error = TransportError.ConnectFailed(
                    "Android denied Bluetooth discovery access; re-check Bluetooth and nearby-device permissions",
                ),
            )
        }
    }

    /** Start scan + commit the state atomically with Stop/disconnect. */
    @SuppressLint("MissingPermission")
    private fun tryStartDiscovery(
        adapter: BluetoothAdapter,
        attempt: Long,
    ): DiscoveryStartResult = synchronized(discoveryLock) {
        if (attempt != discoveryGeneration) return@synchronized DiscoveryStartResult.CANCELLED
        try {
            val alreadyActive = adapter.isDiscovering
            if (!alreadyActive && !adapter.startDiscovery()) {
                return@synchronized DiscoveryStartResult.FAILED
            }
            _state.value = TransportState.DISCOVERING
            _error.value = null
            if (alreadyActive) DiscoveryStartResult.ALREADY_ACTIVE else DiscoveryStartResult.STARTED
        } catch (e: SecurityException) {
            AppLog.log(TAG, "Bluetooth discovery start denied by Android: ${e.message}")
            val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = true)
            if (missing.isNotEmpty()) {
                _state.value = TransportState.UNAVAILABLE
                _error.value = TransportError.PermissionDenied(missing)
            } else {
                _state.value = TransportState.FAILED
                _error.value = TransportError.ConnectFailed(
                    "Android denied Bluetooth discovery access; re-check Bluetooth and nearby-device permissions",
                )
            }
            DiscoveryStartResult.PERMISSION_ERROR
        }
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
        // Clear an old terminal error only when the user explicitly stops
        // discovery or starts a new role, not during failure teardown.
        invalidateDiscovery(clearError = true)
    }

    /** Invalidates pending retries before cancelling the platform scan. */
    @SuppressLint("MissingPermission")
    private fun invalidateDiscovery(clearError: Boolean = false) {
        synchronized(discoveryLock) {
            discoveryGeneration++
            try {
                adapter?.cancelDiscovery()
            } catch (_: Exception) {}
            if (_state.value == TransportState.DISCOVERING ||
                _state.value == TransportState.FAILED ||
                _state.value == TransportState.UNAVAILABLE
            ) {
                _state.value = TransportState.IDLE
            }
            if (clearError) _error.value = null
        }
    }

    // ------------------------------------------------------------- connect

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: PeerDevice) {
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
        if (missing.isNotEmpty()) {
            AppLog.log(TAG, "connect blocked: missing permissions $missing")
            _error.value = TransportError.PermissionDenied(missing)
            _state.value = TransportState.FAILED
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
                _state.value = TransportState.FAILED
                return
            }
            val radioEnabled = try {
                a.isEnabled
            } catch (e: SecurityException) {
                AppLog.log(TAG, "connect denied while checking Bluetooth state: ${e.message}")
                val stillMissing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
                _error.value = if (stillMissing.isNotEmpty()) {
                    TransportError.PermissionDenied(stillMissing)
                } else {
                    TransportError.ConnectFailed("Android denied Bluetooth access; re-check Nearby devices permission")
                }
                _state.value = if (stillMissing.isNotEmpty()) TransportState.UNAVAILABLE else TransportState.FAILED
                return
            }
            if (!radioEnabled) {
                _error.value = TransportError.RadioDisabled(TransportType.BLUETOOTH)
                _state.value = TransportState.FAILED
                return
            }
            cancelPendingConnectLocked()
            val attempt = connectionGeneration.incrementAndGet()
            val socketRef = AtomicReference<BluetoothSocket?>()
            pendingConnectSocket = socketRef
            _state.value = TransportState.CONNECTING
            _error.value = null
            AppLog.log(TAG, "connecting to selected Bluetooth peer")
            try {
                a.cancelDiscovery()
            } catch (_: SecurityException) {}
            stopAcceptLocked()
            // HS-06: the blocking connect() runs on Dispatchers.IO, and the
            // deadline is enforced by a watchdog that CLOSES the socket —
            // closing unblocks the pending connect(), which withTimeout
            // alone cannot interrupt. The AtomicReference guarantees the
            // watchdog never closes a newer socket.
            connectJob = scope.launch(Dispatchers.IO) {
                var socket: BluetoothSocket? = null
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
                    // disconnect() invalidates the generation and closes this
                    // reference. Check before entering the blocking call so a
                    // cancellation during device/socket creation cannot start
                    // a fresh RFComm connection after the user stopped it.
                    if (connectionGeneration.get() != attempt) {
                        socketRef.getAndSet(null)?.let { try { it.close() } catch (_: IOException) {} }
                        return@launch
                    }
                    socket.connect() // blocking; disconnect/watchdog close unblocks it
                    watchdog.cancel()
                    socketRef.set(null)
                    if (!socket.isConnected) {
                        throw SocketTimeoutException("bluetooth connect timed out")
                    }
                    if (connectionGeneration.get() != attempt) {
                        try { socket.close() } catch (_: IOException) {}
                        socket = null
                        return@launch
                    }
                    AppLog.log(TAG, "RFCOMM connection established")
                    onSocketReady(socket, expectedGeneration = attempt)
                    socket = null // onSocketReady accepted or closed the socket
                } catch (e: CancellationException) {
                    watchdog.cancel()
                    socketRef.getAndSet(null)?.let { try { it.close() } catch (_: IOException) {} }
                    try { socket?.close() } catch (_: IOException) {}
                    throw e
                } catch (e: SecurityException) {
                    watchdog.cancel()
                    socketRef.getAndSet(null)?.let { try { it.close() } catch (_: IOException) {} }
                    try { socket?.close() } catch (_: IOException) {}
                    AppLog.log(TAG, "RFCOMM connect denied by Android: ${e.message}")
                    val stillMissing = PermissionHelper.missingBluetoothPermissions(
                        context,
                        forDiscovery = false,
                    )
                    mutex.withLock {
                        if (connectionGeneration.get() == attempt &&
                            _state.value == TransportState.CONNECTING
                        ) {
                            _error.value = if (stillMissing.isNotEmpty()) {
                                TransportError.PermissionDenied(stillMissing)
                            } else {
                                TransportError.ConnectFailed(
                                    "Android denied Bluetooth RFCOMM access; check Nearby devices permission",
                                )
                            }
                            _state.value = if (stillMissing.isNotEmpty()) {
                                TransportState.UNAVAILABLE
                            } else {
                                TransportState.FAILED
                            }
                        }
                    }
                } catch (e: Exception) {
                    watchdog.cancel()
                    socketRef.set(null)
                    AppLog.log(TAG, "connect failed: ${e.javaClass.simpleName}: ${e.message}")
                    try {
                        socket?.close()
                    } catch (_: IOException) {}
                    mutex.withLock {
                        // An old attempt that was cancelled by disconnect() or
                        // superseded by a retry must not overwrite the newer
                        // transport state with FAILED.
                        if (connectionGeneration.get() == attempt &&
                            _state.value == TransportState.CONNECTING
                        ) {
                            _error.value = TransportError.ConnectFailed(
                                "bluetooth connect failed: ${e.message}",
                            )
                            _state.value = TransportState.FAILED
                        }
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
            _state.value = TransportState.FAILED
            return
        }
        mutex.withLock {
            val a = adapter ?: run {
                _error.value = TransportError.Unsupported("bluetooth_unsupported")
                _state.value = TransportState.FAILED
                return
            }
            val radioEnabled = try {
                a.isEnabled
            } catch (e: SecurityException) {
                AppLog.log(TAG, "listen denied while checking Bluetooth state: ${e.message}")
                val stillMissing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
                _error.value = if (stillMissing.isNotEmpty()) {
                    TransportError.PermissionDenied(stillMissing)
                } else {
                    TransportError.ConnectFailed("Android denied Bluetooth access; re-check Nearby devices permission")
                }
                _state.value = if (stillMissing.isNotEmpty()) TransportState.UNAVAILABLE else TransportState.FAILED
                return
            }
            if (!radioEnabled) {
                _state.value = TransportState.FAILED
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
            _state.value = TransportState.FAILED
            return
        } catch (e: SecurityException) {
            AppLog.log(TAG, "rfcomm listen denied: ${e.message}")
            _error.value = TransportError.PermissionDenied(listOf("bluetooth_connect"))
            _state.value = TransportState.FAILED
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

    private suspend fun onSocketReady(
        socket: BluetoothSocket,
        expectedGeneration: Long? = null,
    ) {
        mutex.withLock {
            // A socket may finish connecting at the same moment as Stop or
            // disconnect. Only the active outgoing attempt may publish it.
            if (expectedGeneration != null &&
                (connectionGeneration.get() != expectedGeneration ||
                    _state.value != TransportState.CONNECTING)
            ) {
                try { socket.close() } catch (_: IOException) {}
                AppLog.log(TAG, "discarding stale RFCOMM socket after disconnect/retry")
                return@withLock
            }
            closeSocketLocked()
            btSocket = socket
            AppLog.log(TAG, "RFCOMM socket ready")
            val sourceRef = AtomicReference<FramedSocket?>()
            val newFramed = FramedSocket.fromStreams(socket.inputStream, socket.outputStream) {
                sourceRef.get()?.let { source ->
                    scope.launch { handleLost("peer closed the connection", source) }
                }
            }
            sourceRef.set(newFramed)
            synchronized(readerGate) { framed = newFramed }
            _state.value = TransportState.CONNECTED
            _error.value = null
            startReaderLocked(newFramed)
        }
    }

    private fun startReaderLocked(source: FramedSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            while (isCurrentSocketConnected(source)) {
                // HS-04: malformed frames and I/O failures use the common
                // cleanup path. Always carry the source socket identity so a
                // cancelled old reader cannot tear down a newer connection.
                val r = try {
                    source.readFrame()
                } catch (e: FrameException) {
                    AppLog.log(TAG, "framing error: ${e.message}")
                    handleLost("framing error: ${e.message}", source)
                    return@launch
                } catch (e: CancellationException) {
                    throw e // normal shutdown; not an error
                } catch (e: Exception) {
                    AppLog.log(TAG, "reader failed: ${e.javaClass.simpleName}")
                    handleLost("read error: ${e.message}", source)
                    return@launch
                }
                when (r) {
                    is FramedSocket.ReadResult.Frame -> {
                        if (!publishIncomingFrame(source, r.frame)) {
                            return@launch // socket was replaced or closed
                        }
                    }
                    is FramedSocket.ReadResult.Closed -> {
                        handleLost("peer closed the connection", source)
                        return@launch
                    }
                    is FramedSocket.ReadResult.Error -> {
                        handleLost(r.cause.message ?: "read error", source)
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Publish with backpressure rather than buffering up to 64 stale media
     * frames. The stream reader stops reading while the session collector
     * processes a frame, allowing the socket/peer's bounded send queue to
     * absorb congestion instead of replaying a long burst later.
     *
     * The reader job is cancelled when this socket is replaced; emit is
     * cancellable, so a suspended old reader cannot publish into a new session.
     */
    private suspend fun publishIncomingFrame(source: FramedSocket, frame: ByteArray): Boolean {
        val current = synchronized(readerGate) {
            framed === source && _state.value == TransportState.CONNECTED
        }
        if (!current) return false
        _incomingFrames.emit(frame)
        return true
    }

    private fun isCurrentSocketConnected(source: FramedSocket): Boolean =
        synchronized(readerGate) {
            framed === source && _state.value == TransportState.CONNECTED
        }

    private suspend fun handleLost(reason: String, source: FramedSocket) {
        mutex.withLock {
            if (framed !== source || _state.value != TransportState.CONNECTED) {
                AppLog.log(
                    TAG,
                    "ignoring stale RFCOMM close/error (reason=$reason, state=${_state.value})",
                )
                return@withLock
            }
            AppLog.log(TAG, "connection lost: $reason (was ${_state.value})")
            _error.value = TransportError.ConnectionLost(reason)
            closeSocketLocked()
            _state.value = TransportState.IDLE
        }
    }

    /** Must be called under [mutex]. Invalidates and actively closes a pending connect. */
    private fun cancelPendingConnectLocked() {
        connectionGeneration.incrementAndGet()
        pendingConnectSocket?.getAndSet(null)?.let { socket ->
            try { socket.close() } catch (_: IOException) {}
        }
        pendingConnectSocket = null
        connectJob?.cancel()
        connectJob = null
    }

    private fun closeSocketLocked() {
        // Invalidate this reader before closing the stream. Closing an old
        // RFCOMM socket can unblock its read concurrently with a new connect.
        val oldFramed = synchronized(readerGate) {
            framed.also { framed = null }
        }
        readerJob?.cancel(); readerJob = null
        oldFramed?.close()
        try { btSocket?.close() } catch (_: IOException) {}
        btSocket = null
    }

    // ------------------------------------------------------------------ io

    override suspend fun sendFrame(frameBytes: ByteArray) {
        sendFrames(listOf(frameBytes))
    }

    /**
     * RFCOMM is a byte stream, so several complete length-prefixed frames can
     * share one output write without changing the protocol seen by the reader.
     * This reduces per-write stack overhead when the sender queue has a backlog.
     */
    override suspend fun sendFrames(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        val f = mutex.withLock { framed }
            ?: throw IOException("not connected")
        val byteCount = frames.sumOf { 4L + it.size }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val startedAtNanos = System.nanoTime()
        val writeGuard = RfcommWriteGuard()
        var succeeded = false

        // Socket stream writes are blocking platform calls: coroutine timeout
        // alone cannot interrupt one. SessionManager already bounds its audio
        // queue and drops stale frames while this writer is blocked; allow that
        // freshness policy to absorb short RF pauses before declaring the link lost.
        // The watchdog still closes this exact socket after a sustained stall so
        // a native write cannot remain blocked indefinitely.
        val watchdog = scope.launch {
            delay(RFCOMM_WRITE_STALL_TIMEOUT_MS)
            if (!writeGuard.tryTimeout()) return@launch

            mutex.withLock {
                if (framed !== f || _state.value != TransportState.CONNECTED) {
                    AppLog.log(TAG, "RFCOMM write watchdog ignored a stale socket")
                    return@withLock
                }
                val reason = "Bluetooth send stalled (>${RFCOMM_WRITE_STALL_TIMEOUT_MS} ms)"
                AppLog.log(TAG, "$reason; sustained stall, closing socket after audio freshness drops")
                _error.value = TransportError.ConnectionLost(reason)
                closeSocketLocked()
                _state.value = TransportState.IDLE
            }
        }

        try {
            withContext(Dispatchers.IO) {
                f.writeFrames(frames)
            }
            // A timeout may win just as the blocking write returns. If it did,
            // this connection is no longer safe to treat as live.
            if (!writeGuard.tryComplete()) {
                throw IOException("RFCOMM write exceeded watchdog deadline")
            }
            succeeded = true
        } catch (e: CancellationException) {
            // Cancellation does not reliably interrupt a blocking Java I/O
            // call. Close the matching socket so no orphan write thread stays
            // blocked after the caller has stopped sending.
            if (writeGuard.tryAbort()) {
                scope.launch { handleLost("RFCOMM write cancelled", f) }
            }
            throw e
        } catch (e: IOException) {
            scope.launch { handleLost(e.message ?: "write error", f) }
            throw e
        } finally {
            writeGuard.tryComplete()
            watchdog.cancel()
            recordRfcommWriteTiming(
                frameCount = frames.size,
                byteCount = byteCount,
                elapsedNanos = System.nanoTime() - startedAtNanos,
                succeeded = succeeded,
            )
        }
    }

    private fun recordRfcommWriteTiming(
        frameCount: Int,
        byteCount: Int,
        elapsedNanos: Long,
        succeeded: Boolean,
    ) {
        val report = synchronized(rfcommWriteStatsLock) {
            rfcommWriteFrames += frameCount
            rfcommWriteCalls++
            if (!succeeded) rfcommWriteFailedCalls++
            rfcommWriteBytes += byteCount.toLong()
            rfcommWriteTotalNanos += elapsedNanos.coerceAtLeast(0L)
            rfcommWriteMaxNanos = maxOf(rfcommWriteMaxNanos, elapsedNanos)
            rfcommWriteMaxBatchFrames = maxOf(rfcommWriteMaxBatchFrames, frameCount)
            if (rfcommWriteFrames < RFCOMM_WRITE_DIAGNOSTIC_INTERVAL) {
                null
            } else {
                val avgCallMs = rfcommWriteTotalNanos / rfcommWriteCalls / 1_000_000.0
                val maxCallMs = rfcommWriteMaxNanos / 1_000_000.0
                val avgFramesPerCall = rfcommWriteFrames.toDouble() / rfcommWriteCalls
                val avgBytesPerFrame = rfcommWriteBytes.toDouble() / rfcommWriteFrames
                val summary = "frames=$rfcommWriteFrames, calls=$rfcommWriteCalls, " +
                    "failedCalls=$rfcommWriteFailedCalls, " +
                    "avgCallMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgCallMs) + ", " +
                    "maxCallMs=" + String.format(java.util.Locale.ROOT, "%.2f", maxCallMs) + ", " +
                    "avgFramesPerCall=" + String.format(java.util.Locale.ROOT, "%.2f", avgFramesPerCall) + ", " +
                    "avgBytesPerFrame=" + String.format(java.util.Locale.ROOT, "%.1f", avgBytesPerFrame) + ", " +
                    "maxBatchFrames=$rfcommWriteMaxBatchFrames"
                rfcommWriteFrames = 0
                rfcommWriteCalls = 0
                rfcommWriteFailedCalls = 0
                rfcommWriteBytes = 0L
                rfcommWriteTotalNanos = 0L
                rfcommWriteMaxNanos = 0L
                rfcommWriteMaxBatchFrames = 0
                summary
            }
        }
        report?.let { AppLog.log(TAG, "RFCOMM write timing: $it") }
    }

    override suspend fun disconnect() {
        invalidateDiscovery()
        mutex.withLock {
            cancelPendingConnectLocked()
            closeSocketLocked()
            stopAcceptLocked()
            _state.value = TransportState.IDLE
            // Preserve the terminal transport error for the UI diagnostics
            // banner; a subsequent stopDiscovery/new attempt clears it.
            _peers.value = emptyList()
            synchronized(peerLock) {
                found.clear()
                foundSeenAt.clear()
            }
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
                    if (!isUsableBluetoothAddress(address)) return
                    val displayName = name
                        ?.filterNot { it.isISOControl() }
                        ?.take(MAX_PEER_NAME_CHARS)
                        ?.takeIf { it.isNotBlank() }
                        ?: address
                    val peer = PeerDevice(
                        id = address,
                        displayName = displayName,
                        transport = TransportType.BLUETOOTH,
                    )
                    val seenAtMs = SystemClock.elapsedRealtime()
                    synchronized(peerLock) {
                        if (address in found || found.size < MAX_DISCOVERED_PEERS) {
                            found[address] = peer
                            foundSeenAt[address] = seenAtMs
                        }
                    }
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
                    // Expire vanished non-bonded peers even when this scan found nothing.
                    publishPeers()
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
        private const val RFCOMM_WRITE_DIAGNOSTIC_INTERVAL = 50
        /** Let bounded outbound audio freshness drops absorb brief RF/backpressure pauses. */
        private const val RFCOMM_WRITE_STALL_TIMEOUT_MS = 2_000L
        private const val TAG = "HamSedaBluetooth"
        private const val MAX_DISCOVERED_PEERS = 128
        private const val MAX_VISIBLE_PEERS = 192
        private const val MAX_PEER_NAME_CHARS = 64
        /** Discovered peers expire after two minutes without a fresh sighting. */
        const val FOUND_PEER_TTL_MS = 120_000L
    }
}


/**
 * Returns discovered peer IDs older than the freshness window. Uses elapsed
 * realtime values supplied by Android so wall-clock changes cannot retain or
 * prematurely evict a peer. Exact TTL equality is still considered fresh.
 */
internal fun stalePeerIds(
    lastSeenMs: Map<String, Long>,
    nowMs: Long,
    ttlMs: Long,
): Set<String> {
    require(ttlMs >= 0L) { "ttlMs must be non-negative" }
    return lastSeenMs.filterValues { lastSeen ->
        nowMs >= lastSeen && nowMs - lastSeen > ttlMs
    }.keys.toSet()
}
