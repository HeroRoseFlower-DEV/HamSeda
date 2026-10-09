package com.hamseda.walkie.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.FramedSocket
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.util.AppLog
import com.hamseda.walkie.util.PermissionHelper
import kotlinx.coroutines.CompletableDeferred
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Primary transport: Wi-Fi Direct (Wi-Fi P2P).
 *
 * Flow: [startDiscovery] → peer list via framework discovery → [connect]
 * with WPS-PBC → framework negotiates group owner → the group owner opens
 * a [ServerSocket] on [Protocol.WIFI_DIRECT_PORT], the client opens a TCP
 * socket to the negotiated group-owner address (never hard-coded) →
 * [FramedSocket] carries the session frames.
 *
 * Incoming connections are handled symmetrically: when the framework
 * reports a formed group and this device is the owner, the transport
 * accepts the peer's socket without any prior outgoing [connect] call.
 *
 * No internet, no access point, no router: the group is negotiated directly
 * between the two phones over the Wi-Fi radio.
 */
class WifiDirectTransport(private val context: Context) : Transport {

    override val type = TransportType.WIFI_DIRECT

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _error = MutableStateFlow<TransportError?>(null)
    override val error: StateFlow<TransportError?> = _error.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    // Wi-Fi Direct P2P discovery finds nearby devices without an explicit
    // "discoverable" step, so this is always true.
    override val discoverable: StateFlow<Boolean> =
        MutableStateFlow(true).asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    @Volatile private var p2pEnabled = false
    private var framed: FramedSocket? = null
    private var serverSocket: ServerSocket? = null
    private var readerJob: Job? = null
    private var socketJob: Job? = null
    private var connectWatchdog: Job? = null

    private fun ensureInit(force: Boolean = false): Boolean {
        if (!force && manager != null && channel != null) return true
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return false
        }
        return try {
            val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return false
            manager = m
            // ChannelListener: if the framework kills the channel (wifi
            // toggle, service restart…), drop the cached handles so the
            // next operation re-initializes instead of failing silently.
            channel = m.initialize(context, Looper.getMainLooper()) {
                AppLog.log(TAG, "p2p channel lost; will re-initialize on next use")
                scope.launch {
                    mutex.withLock {
                        channel = null
                        manager = null
                    }
                }
            }
            channel != null
        } catch (e: Exception) {
            Log.w(TAG, "wifi p2p init failed", e)
            false
        }
    }

    override fun availability(): Availability {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return Availability(false, false, "wifi_direct_unsupported")
        }
        if (!ensureInit()) return Availability(false, false, "wifi_direct_init_failed")
        ensureReceiver()
        return Availability(true, p2pEnabled, if (p2pEnabled) "" else "wifi_direct_disabled")
    }

    override fun clearError() {
        _error.value = null
    }

    // ------------------------------------------------------------ discovery

    // Permissions (NEARBY_WIFI_DEVICES / location) are checked explicitly at
    // the top of this function; lint cannot follow the early return.
    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        val missing = PermissionHelper.missingWifiDirectPermissions(context)
        if (missing.isNotEmpty()) {
            _error.value = TransportError.PermissionDenied(missing)
            return
        }
        if (!ensureInit()) {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.Unsupported("wifi_direct_unsupported")
            return
        }
        if (!p2pEnabled) {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.RadioDisabled(TransportType.WIFI_DIRECT)
            return
        }
        registerReceiver()
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = TransportState.DISCOVERING
                _error.value = null
            }

            override fun onFailure(reason: Int) {
                _error.value = TransportError.ConnectFailed("discovery failed: ${reasonName(reason)}")
                _state.value = TransportState.IDLE
            }
        })
    }

    override suspend fun stopDiscovery() {
        try {
            manager?.stopPeerDiscovery(channel, null)
        } catch (_: Exception) {}
        if (_state.value == TransportState.DISCOVERING) _state.value = TransportState.IDLE
    }

    // ------------------------------------------------------------- connect

    // Permissions (discovery + ACCESS_LOCAL_NETWORK on API 37+) are checked
    // explicitly at the top of this function; lint cannot follow the early
    // return.
    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: PeerDevice) {
        // Discovery permission AND local-network access (API 37+) — the P2P
        // group socket is a local-network connection and would otherwise be
        // silently blocked on Android 17.
        val missing = PermissionHelper.missingWifiDirectConnectPermissions(context)
        if (missing.isNotEmpty()) {
            _error.value = TransportError.PermissionDenied(missing)
            return
        }
        mutex.withLock {
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.CONNECTING
            ) return
            if (!ensureInit() || !p2pEnabled) {
                _error.value = TransportError.RadioDisabled(TransportType.WIFI_DIRECT)
                return
            }
            registerReceiver()
            _state.value = TransportState.CONNECTING
            _error.value = null
            val wifiEnabled = try {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.isWifiEnabled
            } catch (_: SecurityException) {
                null
            }
            AppLog.log(
                TAG,
                "p2p connect preflight: sdk=${Build.VERSION.SDK_INT}, wifiEnabled=$wifiEnabled, " +
                    "p2pEnabled=$p2pEnabled, channelReady=${channel != null}, peer=${peer.displayName} (${peer.id})",
            )
            // Do not call stopPeerDiscovery() immediately before connect().
            // The P2P framework ends discovery as part of connection setup;
            // issuing an asynchronous stop right before connect can race on
            // some vendor stacks. First check whether a group already exists,
            // then submit the connect request directly.
            val existing = requestConnectionInfoSync()
            if (existing != null && existing.groupFormed) {
                AppLog.log(TAG, "reusing existing p2p group; skipping invitation")
                onConnectionInfo(existing)
                return
            }

            val config = buildP2pConfig(peer)
            // The framework rejects connect() with a generic ERROR when the
            // channel went stale (wifi toggle, service restart…). Re-init
            // and retry once before giving up.
            var initiated = p2pConnect(config)
            if (!initiated) {
                AppLog.log(TAG, "re-initializing p2p channel and retrying connect")
                ensureInit(force = true)
                registerReceiver()
                delay(500)
                initiated = p2pConnect(config)
            }
            if (!initiated) {
                _error.value = TransportError.ConnectFailed(
                    "wi-fi direct connect rejected by framework",
                )
                _state.value = TransportState.FAILED
                return
            }
            AppLog.log(TAG, "p2p connect initiated; waiting for peer to accept the system invitation")
            // Watchdog: the framework reports nothing when the peer ignores
            // the system invitation prompt — fail visibly instead of hanging
            // in CONNECTING forever.
            connectWatchdog?.cancel()
            connectWatchdog = scope.launch {
                delay(Protocol.P2P_INVITE_TIMEOUT_MS)
                mutex.withLock {
                    if (_state.value == TransportState.CONNECTING) {
                        AppLog.log(TAG, "p2p invitation timed out (peer did not accept)")
                        try {
                            manager?.cancelConnect(channel, null)
                        } catch (_: Exception) {}
                        _error.value = TransportError.ConnectFailed(
                            "peer did not accept the wi-fi direct invitation in time",
                        )
                        _state.value = TransportState.FAILED
                    }
                }
            }
        }
    }

    /**
     * Modern P2P connect config: WifiP2pConfig.Builder (API 29+); legacy
     * constructor below that.
     */
    private fun buildP2pConfig(peer: PeerDevice): WifiP2pConfig {
        return if (Build.VERSION.SDK_INT >= 29) {
            WifiP2pConfig.Builder()
                .setDeviceAddress(android.net.MacAddress.fromString(peer.id))
                .build()
                .apply {
                    @Suppress("DEPRECATION")
                    wps.setup = WpsInfo.PBC
                }
        } else {
            @Suppress("DEPRECATION")
            WifiP2pConfig().apply {
                deviceAddress = peer.id
                wps.setup = WpsInfo.PBC
                // Neutral owner intent: let negotiation decide; we handle
                // both outcomes (see onConnectionInfo).
                groupOwnerIntent = 7
            }
        }
    }

    /**
     * Suspends until the framework accepts or rejects the P2P invitation.
     * Returns false on rejection or timeout (stale channel, busy…).
     */
    @SuppressLint("MissingPermission")
    private suspend fun p2pConnect(config: WifiP2pConfig): Boolean {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return false
        return try {
            withTimeout(10_000) {
                val done = CompletableDeferred<Boolean>()
                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "p2p connect accepted by framework")
                        done.complete(true)
                    }

                    override fun onFailure(reason: Int) {
                        val name = reasonName(reason)
                        AppLog.log(TAG, "p2p connect failed: $name (code=$reason)")
                        done.complete(false)
                    }
                })
                done.await()
            }
        } catch (_: Exception) {
            AppLog.log(TAG, "p2p connect attempt timed out")
            false
        }
    }

    override suspend fun listen() {
        if (!ensureInit()) {
            _state.value = TransportState.UNAVAILABLE
            return
        }
        registerReceiver()
        // Pick up a group that already exists (e.g. formed via system
        // settings before the app registered its receiver).
        val existing = requestConnectionInfoSync()
        if (existing != null && existing.groupFormed) {
            AppLog.log(TAG, "picking up existing p2p group while listening")
            onConnectionInfo(existing)
        } else if (_state.value == TransportState.IDLE) {
            Log.i(TAG, "listening for incoming wi-fi direct groups")
        }
    }

    /**
     * Synchronously queries the current P2P connection state. Returns null
     * when the query itself fails (missing permission, dead channel…).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestConnectionInfoSync(): WifiP2pInfo? {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return null
        if (PermissionHelper.missingWifiDirectPermissions(context).isNotEmpty()) return null
        return try {
            withTimeout(5_000) {
                val deferred = CompletableDeferred<WifiP2pInfo?>()
                try {
                    mgr.requestConnectionInfo(ch) { info ->
                        deferred.complete(info)
                    }
                } catch (e: Exception) {
                    deferred.complete(null)
                }
                deferred.await()
            }
        } catch (_: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- socket

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            // During invitation negotiation Android may emit transient P2P
            // state updates before group formation. Do not turn an outgoing
            // CONNECTING attempt into IDLE just because the group is not ready
            // yet; only tear down a transport that was already established.
            AppLog.log(TAG, "p2p group not formed yet (transportState=${_state.value})")
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.AUTHENTICATING
            ) {
                scope.launch { handleGroupLost("p2p group lost") }
            }
            return
        }
        AppLog.log(TAG, "p2p group formed (owner=${info.isGroupOwner}, addr=${info.groupOwnerAddress?.hostAddress})")
        scope.launch {
            mutex.withLock {
                if (framed != null) return@withLock // already have a socket
                // Connection-changed broadcasts may be duplicated while the
                // group is forming. Do not cancel/restart an active TCP
                // accept/connect job for the same group.
                if (_state.value == TransportState.AUTHENTICATING &&
                    socketJob?.isActive == true
                ) {
                    AppLog.log(TAG, "duplicate group-formed event ignored; socket setup is already running")
                    return@withLock
                }
                connectWatchdog?.cancel()
                connectWatchdog = null
                // The P2P group socket is a local-network connection: on
                // API 37+ it is silently blocked without ACCESS_LOCAL_NETWORK.
                val missing = PermissionHelper.missingLocalNetworkPermission(context)
                if (missing.isNotEmpty()) {
                    AppLog.log(TAG, "p2p socket blocked: missing $missing (API 37+)")
                    _error.value = TransportError.PermissionDenied(missing)
                    _state.value = TransportState.FAILED
                    return@withLock
                }
                _state.value = TransportState.AUTHENTICATING
                socketJob?.cancel()
                socketJob = scope.launch {
                    try {
                        val socket = withTimeout(Protocol.CONNECT_TIMEOUT_MS.toLong()) {
                            if (info.isGroupOwner) acceptAsOwner() else connectAsClient(info)
                        }
                        AppLog.log(TAG, "p2p socket established")
                        onSocketReady(socket)
                    } catch (e: Exception) {
                        AppLog.log(TAG, "p2p socket failed: ${e.javaClass.simpleName}: ${e.message}")
                        mutex.withLock {
                            closeSocketLocked()
                            _error.value = TransportError.ConnectFailed(
                                "socket failed: ${e.message}",
                            )
                            _state.value = TransportState.FAILED
                        }
                    }
                }
            }
        }
    }

    private fun acceptAsOwner(): Socket {
        val server = ServerSocket(Protocol.WIFI_DIRECT_PORT).also {
            it.soTimeout = Protocol.CONNECT_TIMEOUT_MS
            serverSocket = it
        }
        Log.i(TAG, "group owner: accepting on port ${Protocol.WIFI_DIRECT_PORT}")
        return server.accept().also { Log.i(TAG, "group owner: peer socket accepted") }
    }

    private fun connectAsClient(info: WifiP2pInfo): Socket {
        val host = info.groupOwnerAddress?.hostAddress
            ?: throw IOException("group owner address unavailable")
        Log.i(TAG, "client: connecting to group owner $host")

        // Both phones receive the group-formed callback independently. The
        // client can reach this point a moment before the owner has bound its
        // ServerSocket; a single TCP attempt then fails immediately with
        // ECONNREFUSED even though Wi-Fi Direct itself succeeded. Retry
        // short connection attempts until the same bounded connection deadline.
        val deadlineNanos =
            System.nanoTime() + Protocol.CONNECT_TIMEOUT_MS.toLong() * 1_000_000L
        var lastFailure: IOException? = null
        var loggedNotReady = false
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) break
            val timeoutMs = (remainingNanos / 1_000_000L)
                .coerceAtLeast(1L)
                .coerceAtMost(1_000L)
                .toInt()
            val client = Socket()
            try {
                client.connect(
                    InetSocketAddress(host, Protocol.WIFI_DIRECT_PORT),
                    timeoutMs,
                )
                client.tcpNoDelay = true
                Log.i(TAG, "client: connected to group owner $host")
                return client
            } catch (e: IOException) {
                lastFailure = e
                try { client.close() } catch (_: IOException) {}
                if (!loggedNotReady) {
                    AppLog.log(TAG, "group owner socket not ready yet; retrying (${e.javaClass.simpleName})")
                    loggedNotReady = true
                }
                if (deadlineNanos - System.nanoTime() <= 0L) break
                try {
                    Thread.sleep(200L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("client connection interrupted", e)
                }
            }
        }
        throw IOException(
            "could not connect to group owner $host:${Protocol.WIFI_DIRECT_PORT} " +
                "within ${Protocol.CONNECT_TIMEOUT_MS}ms; lastError=${lastFailure?.message}",
            lastFailure,
        )
    }

    private suspend fun onSocketReady(socket: Socket) {
        mutex.withLock {
            framed?.close()
            framed = FramedSocket.fromTcpSocket(socket) {
                scope.launch { handleGroupLost() }
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
            while (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.AUTHENTICATING
            ) {
                // HS-04: readFrame() throws FrameException on protocol
                // violation — route it through the common disconnect path
                // instead of stranding the transport in CONNECTED.
                val r = try {
                    f.readFrame()
                } catch (e: FrameException) {
                    AppLog.log(TAG, "framing error: ${e.message}")
                    handleGroupLost("framing error: ${e.message}")
                    return@launch
                } catch (e: CancellationException) {
                    throw e // normal shutdown; not an error
                } catch (e: Exception) {
                    AppLog.log(TAG, "reader failed: ${e.javaClass.simpleName}")
                    handleGroupLost("read error: ${e.message}")
                    return@launch
                }
                when (r) {
                    is FramedSocket.ReadResult.Frame -> _incomingFrames.emit(r.frame)
                    is FramedSocket.ReadResult.Closed -> {
                        handleGroupLost()
                        return@launch
                    }
                    is FramedSocket.ReadResult.Error -> {
                        if (r.cause is SocketTimeoutException) continue // idle read timeout
                        handleGroupLost(r.cause.message ?: "read error")
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun handleGroupLost(reason: String = "connection lost") {
        mutex.withLock {
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.AUTHENTICATING
            ) {
                _error.value = TransportError.ConnectionLost(reason)
            }
            closeSocketLocked()
            _state.value = TransportState.IDLE
        }
    }

    private fun closeSocketLocked() {
        readerJob?.cancel(); readerJob = null
        socketJob?.cancel(); socketJob = null
        connectWatchdog?.cancel(); connectWatchdog = null
        framed?.close(); framed = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    // ------------------------------------------------------------------ io

    override suspend fun sendFrame(frameBytes: ByteArray) {
        val f = mutex.withLock { framed }
            ?: throw IOException("not connected")
        try {
            f.writeFrame(frameBytes)
        } catch (e: IOException) {
            scope.launch { handleGroupLost(e.message ?: "write error") }
            throw e
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            closeSocketLocked()
            try {
                manager?.removeGroup(channel, null)
            } catch (_: Exception) {}
            try {
                manager?.cancelConnect(channel, null)
            } catch (_: Exception) {}
            _state.value = TransportState.IDLE
            _error.value = null
            _peers.value = emptyList()
        }
    }

    override fun close() {
        scope.launch { disconnect() }.invokeOnCompletion {
            unregisterReceiver()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------- receiver

    private val receiver = object : BroadcastReceiver() {
        // requestPeers/requestConnectionInfo need NEARBY_WIFI_DEVICES; the
        // helper check below is real but lint cannot trace it through the
        // helper, so this is suppressed rather than duplicated.
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    // The receiver must be EXPORTED to hear broadcasts from
                    // the privileged Wi-Fi module. Treat broadcast extras as
                    // untrusted: on API 29+, query the framework's current
                    // state instead of accepting a caller-supplied boolean.
                    if (Build.VERSION.SDK_INT >= 29) {
                        val mgr = manager
                        val ch = channel
                        if (mgr != null && ch != null) {
                            try {
                                mgr.requestP2pState(ch) { state ->
                                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                                    p2pEnabled = enabled
                                    if (!enabled && _state.value != TransportState.IDLE) {
                                        scope.launch { handleGroupLost("wi-fi direct disabled") }
                                    }
                                }
                            } catch (e: SecurityException) {
                                AppLog.log(TAG, "P2P state query denied: ${e.message}")
                            }
                        }
                    } else {
                        p2pEnabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1,
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!p2pEnabled && _state.value != TransportState.IDLE) {
                            scope.launch { handleGroupLost("wi-fi direct disabled") }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Permissions can be revoked mid-discovery; skip rather
                    // than crash with SecurityException.
                    if (PermissionHelper.missingWifiDirectPermissions(ctx).isNotEmpty()) return
                    manager?.requestPeers(channel) { list: WifiP2pDeviceList ->
                        _peers.value = list.deviceList.map { d: WifiP2pDevice ->
                            PeerDevice(
                                id = d.deviceAddress,
                                displayName = d.deviceName.ifBlank { d.deviceAddress },
                                transport = TransportType.WIFI_DIRECT,
                            )
                        }
                    }
                }
                WifiP2pManager.ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED -> {
                    // Android 13+ exposes whether a P2P connection request was
                    // accepted by the system/peer approval flow. This is
                    // diagnostic only; it never bypasses normal authentication.
                    if (Build.VERSION.SDK_INT >= 33) {
                        val accepted = intent.getBooleanExtra(
                            WifiP2pManager.EXTRA_REQUEST_RESPONSE,
                            false,
                        )
                        AppLog.log(TAG, "system connection-request response: accepted=$accepted")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (PermissionHelper.missingWifiDirectPermissions(ctx).isNotEmpty()) return

                    // Do not trust WifiP2pInfo/NetworkInfo extras from an
                    // exported broadcast receiver. They can be absent on some
                    // OEM builds, and an app could spoof an extra to make us
                    // open a socket to an arbitrary address. Ask WifiP2pManager
                    // for the authoritative connection state instead.
                    val mgr = manager
                    val ch = channel
                    if (mgr == null || ch == null) {
                        AppLog.log(TAG, "connection update ignored: P2P channel unavailable")
                        return
                    }
                    try {
                        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                            onConnectionInfo(info)
                        }
                    } catch (e: SecurityException) {
                        AppLog.log(TAG, "connection info query denied: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Registers the P2P receiver eagerly and seeds [p2pEnabled] from the
     * sticky state broadcast. The state broadcast only arrives *after* a
     * receiver is registered, but availability() is called as soon as the
     * Discovery screen opens — without this, the UI wrongly reports
     * "Wi-Fi Direct is off" on a fresh launch even when the radio is on.
     */
    private fun ensureReceiver() {
        registerReceiver()
        if (!p2pEnabled) {
            try {
                val sticky = context.registerReceiver(
                    null,
                    IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION),
                )
                if (sticky != null) {
                    p2pEnabled = sticky.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, -1,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
            } catch (_: Exception) {
                // Best effort: future state changes arrive via the receiver.
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                addAction(WifiP2pManager.ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED)
            }
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Wi-Fi P2P broadcasts may be sent by the privileged Wi-Fi
                // module UID, not android's system UID. NOT_EXPORTED can
                // silently block them. The receiver only subscribes to
                // platform Wi-Fi P2P actions; socket peers still must pass the
                // authenticated HamSeda handshake.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            AppLog.log(TAG, "Wi-Fi P2P system receiver registered")
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

    private fun reasonName(reason: Int): String = when {
        reason == WifiP2pManager.P2P_UNSUPPORTED -> "p2p unsupported"
        reason == WifiP2pManager.BUSY -> "framework busy"
        reason == WifiP2pManager.ERROR -> "framework internal error"
        Build.VERSION.SDK_INT >= 36 && reason == WifiP2pManager.NO_PERMISSION ->
            "framework permission denied"
        else -> "unknown framework result"
    }

    companion object {
        private const val TAG = "HamSedaWifiDirect"
    }
}
