package com.hamseda.walkie.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.hamseda.walkie.proto.FramedSocket
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private fun ensureInit(): Boolean {
        if (manager != null) return true
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return false
        }
        return try {
            val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return false
            manager = m
            channel = m.initialize(context, Looper.getMainLooper(), null)
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
        return Availability(true, p2pEnabled, if (p2pEnabled) "" else "wifi_direct_disabled")
    }

    // ------------------------------------------------------------ discovery

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
            try {
                manager?.stopPeerDiscovery(channel, null)
            } catch (_: Exception) {}

            @Suppress("DEPRECATION") // WifiP2pConfig() works on API 26–36; the
            val config = WifiP2pConfig().apply { // Builder variant needs API 29+
                deviceAddress = peer.id
                wps.setup = WpsInfo.PBC
                // Neutral owner intent: let negotiation decide; we handle
                // both outcomes (see onConnectionInfo).
                groupOwnerIntent = 7
            }
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "p2p connect initiated to ${peer.id}")
                }

                override fun onFailure(reason: Int) {
                    scope.launch {
                        mutex.withLock {
                            _error.value =
                                TransportError.ConnectFailed("connect failed: ${reasonName(reason)}")
                            _state.value = TransportState.FAILED
                        }
                    }
                }
            })
        }
    }

    override suspend fun listen() {
        if (!ensureInit()) {
            _state.value = TransportState.UNAVAILABLE
            return
        }
        registerReceiver()
        if (_state.value == TransportState.IDLE) {
            Log.i(TAG, "listening for incoming wi-fi direct groups")
        }
    }

    // --------------------------------------------------------------- socket

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            scope.launch { handleGroupLost() }
            return
        }
        scope.launch {
            mutex.withLock {
                if (framed != null) return@withLock // already have a socket
                // The P2P group socket is a local-network connection: on
                // API 37+ it is silently blocked without ACCESS_LOCAL_NETWORK.
                val missing = PermissionHelper.missingLocalNetworkPermission(context)
                if (missing.isNotEmpty()) {
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
                        onSocketReady(socket)
                    } catch (e: Exception) {
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
        return Socket().also {
            it.connect(InetSocketAddress(host, Protocol.WIFI_DIRECT_PORT), Protocol.CONNECT_TIMEOUT_MS)
        }
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
                when (val r = f.readFrame()) {
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
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    p2pEnabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, -1,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!p2pEnabled && _state.value != TransportState.IDLE) {
                        scope.launch { handleGroupLost("wi-fi direct disabled") }
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
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
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val netInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (netInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                            onConnectionInfo(info)
                        }
                    } else {
                        scope.launch { handleGroupLost("p2p group changed") }
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
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

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "p2p unsupported"
        WifiP2pManager.BUSY -> "framework busy"
        WifiP2pManager.ERROR -> "framework error"
        else -> "reason=$reason"
    }

    companion object {
        private const val TAG = "HamSedaWifiDirect"
    }
}
