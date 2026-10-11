package com.hamseda.walkie.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.location.LocationManager
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
import kotlinx.coroutines.TimeoutCancellationException
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
import java.util.concurrent.atomic.AtomicLong

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
    // Invalidates framework callbacks/socket jobs that complete after Stop/disconnect.
    private val operationGeneration = AtomicLong(0L)
    private val discoveryGeneration = AtomicLong(0L)
    private val peerSnapshotGeneration = AtomicLong(0L)
    @Volatile private var listeningRequested = false

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
        val scanAttempt = discoveryGeneration.incrementAndGet()
        _peers.value = emptyList()
        _error.value = null

        val missing = PermissionHelper.missingWifiDirectPermissions(context)
        if (missing.isNotEmpty()) {
            AppLog.log(TAG, "p2p discovery blocked: missing runtime permissions=${missing.joinToString()}")
            _error.value = TransportError.PermissionDenied(missing)
            _state.value = TransportState.UNAVAILABLE
            return
        }
        if (!ensureInit()) {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.Unsupported("wifi_direct_unsupported")
            return
        }

        // Wi-Fi Direct peer discovery requires the device-wide Location switch
        // to be enabled on Android, even when the app uses NEARBY_WIFI_DEVICES
        // with neverForLocation. This is separate from granting app permission.
        val locationEnabled = isSystemLocationEnabled()
        val wifiEnabled = try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.isWifiEnabled
        } catch (e: Exception) {
            AppLog.log(TAG, "could not read Wi-Fi radio state: ${e.javaClass.simpleName}")
            null
        }
        AppLog.log(
            TAG,
            "p2p discovery preflight: sdk=${Build.VERSION.SDK_INT}, " +
                "wifiEnabled=$wifiEnabled, locationServicesEnabled=$locationEnabled, " +
                "nearbyWifiPermissionGranted=${PermissionHelper.missingWifiDirectPermissions(context).isEmpty()}",
        )
        if (locationEnabled == false) {
            AppLog.log(TAG, "p2p discovery blocked: system Location services are disabled")
            _error.value = TransportError.ConnectFailed("location services disabled")
            _state.value = TransportState.FAILED
            return
        }

        // Seed the current radio state before deciding it is disabled. This
        // also makes startDiscovery() safe when called before availability().
        ensureReceiver()
        if (Build.VERSION.SDK_INT >= 29) {
            // The state-changed broadcast is not guaranteed to replay after
            // a receiver is registered. Query the framework directly and wait
            // briefly, otherwise a fresh launch can misclassify an enabled
            // P2P radio as disabled and never invoke discoverPeers().
            refreshP2pEnabledState()
        }
        AppLog.log(TAG, "p2p discovery radio check: p2pEnabled=$p2pEnabled, wifiEnabled=$wifiEnabled")
        if (!p2pEnabled) {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.RadioDisabled(TransportType.WIFI_DIRECT)
            AppLog.log(TAG, "p2p discovery blocked: Wi-Fi P2P radio is disabled")
            return
        }
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.Unsupported("wifi_direct_init_failed")
            return
        }
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (discoveryGeneration.get() != scanAttempt) return
                    _state.value = TransportState.DISCOVERING
                    _error.value = null
                    AppLog.log(TAG, "p2p discovery started; waiting for peer-change broadcasts")
                    // Some framework/OEM versions do not send a fresh
                    // PEERS_CHANGED broadcast for an already cached list.
                    // Query it once on success as well as on later broadcasts.
                    requestCurrentPeers(scanAttempt, "discovery-start")
                }

                override fun onFailure(reason: Int) {
                    if (discoveryGeneration.get() != scanAttempt) return
                    val name = reasonName(reason)
                    AppLog.log(TAG, "p2p discovery failed: $name (code=$reason)")
                    _error.value = TransportError.ConnectFailed(
                        "discovery failed: $name (code=$reason)",
                    )
                    _state.value = TransportState.IDLE
                }
            })
        } catch (e: SecurityException) {
            if (discoveryGeneration.get() != scanAttempt) return
            AppLog.log(TAG, "p2p discovery denied by Android: ${e.message}")
            val stillMissing = PermissionHelper.missingWifiDirectPermissions(context)
            if (stillMissing.isNotEmpty()) {
                _error.value = TransportError.PermissionDenied(stillMissing)
                _state.value = TransportState.UNAVAILABLE
            } else {
                _error.value = TransportError.ConnectFailed(
                    "Wi-Fi Direct discovery was denied by Android; re-check nearby-device/location permissions",
                )
                _state.value = TransportState.FAILED
            }
        } catch (e: IllegalStateException) {
            if (discoveryGeneration.get() != scanAttempt) return
            AppLog.log(TAG, "p2p discovery rejected by framework: ${e.message}")
            _error.value = TransportError.ConnectFailed("Wi-Fi Direct discovery could not be started")
            _state.value = TransportState.FAILED
        }
    }

    override suspend fun stopDiscovery() {
        discoveryGeneration.incrementAndGet()
        try {
            manager?.stopPeerDiscovery(channel, null)
        } catch (_: Exception) {}
        if (_state.value == TransportState.DISCOVERING ||
            _state.value == TransportState.FAILED ||
            _state.value == TransportState.UNAVAILABLE
        ) {
            _state.value = TransportState.IDLE
        }
        // Stop/new-role setup clears old errors even when state is already IDLE.
        _error.value = null
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
            _state.value = TransportState.FAILED
            return
        }
        mutex.withLock {
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.CONNECTING
            ) return
            discoveryGeneration.incrementAndGet()
            listeningRequested = false
            val attempt = operationGeneration.incrementAndGet()
            if (!ensureInit() || !p2pEnabled) {
                _error.value = TransportError.RadioDisabled(TransportType.WIFI_DIRECT)
                _state.value = TransportState.FAILED
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
                    "p2pEnabled=$p2pEnabled, channelReady=${channel != null}, peerSelected=true",
            )
            // Do not call stopPeerDiscovery() immediately before connect().
            // The P2P framework ends discovery as part of connection setup;
            // issuing an asynchronous stop right before connect can race on
            // some vendor stacks. First check whether a group already exists,
            // then submit the connect request directly.
            val existing = requestConnectionInfoSync()
            if (operationGeneration.get() != attempt) return
            if (existing != null && existing.groupFormed) {
                AppLog.log(TAG, "reusing existing p2p group; skipping invitation")
                onConnectionInfo(existing, attempt)
                return
            }

            val config = buildP2pConfig(peer)
            // The framework rejects connect() with a generic ERROR when the
            // channel went stale (wifi toggle, service restart…). Re-init
            // and retry once before giving up.
            var initiated = p2pConnect(config)
            if (operationGeneration.get() != attempt) return
            if (!initiated) {
                AppLog.log(TAG, "re-initializing p2p channel and retrying connect")
                ensureInit(force = true)
                registerReceiver()
                delay(500)
                if (operationGeneration.get() != attempt) return
                initiated = p2pConnect(config)
            }
            if (operationGeneration.get() != attempt) return
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
                    if (operationGeneration.get() == attempt &&
                        _state.value == TransportState.CONNECTING
                    ) {
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
        } catch (e: TimeoutCancellationException) {
            AppLog.log(TAG, "p2p connect attempt timed out")
            false
        } catch (e: CancellationException) {
            // User/lifecycle cancellation must not be mistaken for a failed
            // framework attempt and followed by a second connect request.
            throw e
        } catch (e: Exception) {
            AppLog.log(TAG, "p2p connect request failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override suspend fun listen() {
        val missing = PermissionHelper.missingWifiDirectConnectPermissions(context)
        if (missing.isNotEmpty()) {
            _error.value = TransportError.PermissionDenied(missing)
            _state.value = TransportState.FAILED
            return
        }
        if (!ensureInit()) {
            _error.value = TransportError.Unsupported("wifi_direct_unsupported")
            _state.value = TransportState.FAILED
            return
        }
        val attempt = operationGeneration.incrementAndGet()
        listeningRequested = true
        registerReceiver()

        // First pick up a group that may already have formed while the user
        // moved from Search to Wait. Only initiate radio listening if no group
        // already exists.
        val existing = requestConnectionInfoSync()
        if (attempt != operationGeneration.get() || !listeningRequested) return
        if (existing != null && existing.groupFormed) {
            AppLog.log(TAG, "picking up existing p2p group while listening")
            onConnectionInfo(existing, attempt)
            return
        }

        val listenFailure = startP2pListenMode()
        if (attempt != operationGeneration.get() || !listeningRequested) return
        if (listenFailure != null) {
            AppLog.log(TAG, "p2p wait/listen mode failed: $listenFailure")
            _error.value = TransportError.ConnectFailed("p2p listen failed: $listenFailure")
            _state.value = TransportState.FAILED
            return
        }
        AppLog.log(
            TAG,
            "Wi-Fi Direct incoming-listen mode active " +
                "(sdk=${Build.VERSION.SDK_INT}, mode=${if (Build.VERSION.SDK_INT >= 33) "startListening" else "discoverPeers-fallback"})",
        )
    }

    /**
     * API 33+ has an explicit P2P LISTEN state. On older Android versions,
     * keep the radio active using discoverPeers() as the compatible fallback.
     * Neither path changes the UI state to DISCOVERING: the user explicitly
     * selected the wait role, and an incoming group is handled by the receiver.
     *
     * Returns null on success or a diagnostic reason on failure.
     */
    @SuppressLint("MissingPermission")
    private suspend fun startP2pListenMode(): String? {
        val mgr = manager ?: return "manager unavailable"
        val ch = channel ?: return "channel unavailable"
        val result = CompletableDeferred<String?>()
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                result.complete(null)
            }

            override fun onFailure(reason: Int) {
                val detail = "${reasonName(reason)} (code=$reason)"
                result.complete(detail)
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                mgr.startListening(ch, listener)
            } else {
                mgr.discoverPeers(ch, listener)
            }
            withTimeout(5_000L) { result.await() }
        } catch (e: TimeoutCancellationException) {
            "framework callback timed out"
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            "permission denied: ${e.message}"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Prepare Wi-Fi Direct to connect without sending an asynchronous
     * stopPeerDiscovery() request immediately before connect(). The framework
     * ends discovery as connection negotiation begins; stop-then-connect can
     * race and be rejected with WifiP2pManager.ERROR (0).
     */
    override suspend fun prepareForConnect() {
        discoveryGeneration.incrementAndGet()
        if (_state.value == TransportState.FAILED ||
            _state.value == TransportState.UNAVAILABLE
        ) {
            _state.value = TransportState.IDLE
        }
        _error.value = null
        AppLog.log(
            TAG,
            "prepared Wi-Fi Direct connect without stopPeerDiscovery race " +
                "(state=${_state.value})
        )
    }

    /**
     * Prepare to wait for a peer. API 33+ startListening() will transition the
     * radio to listen mode itself, so don't send an asynchronous stop that
     * could overtake and cancel that request. Older releases first stop the
     * previous scan before falling back to discoverPeers() in listen().
     */
    override suspend fun prepareForListen() {
        if (Build.VERSION.SDK_INT < 33) {
            stopDiscovery()
            return
        }
        discoveryGeneration.incrementAndGet()
        if (_state.value == TransportState.DISCOVERING ||
            _state.value == TransportState.FAILED ||
            _state.value == TransportState.UNAVAILABLE
        ) {
            _state.value = TransportState.IDLE
        }
        _error.value = null
        AppLog.log(TAG, "prepared Wi-Fi Direct wait role without asynchronous stopPeerDiscovery")
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    deferred.complete(null)
                }
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            // Do not swallow cancellation of the caller's operation.
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- socket

    private fun onConnectionInfo(info: WifiP2pInfo, expectedGeneration: Long) {
        if (expectedGeneration != operationGeneration.get()) return
        // The receiver exists from app startup for availability diagnostics.
        // Do not attach to unrelated P2P groups without an explicit user action.
        if (!listeningRequested &&
            _state.value != TransportState.CONNECTING &&
            _state.value != TransportState.AUTHENTICATING &&
            _state.value != TransportState.CONNECTED
        ) {
            AppLog.log(TAG, "ignoring unsolicited formed group while transport is idle")
            return
        }
        if (!info.groupFormed) {
            // During invitation negotiation Android may emit transient P2P
            // state updates before group formation. Do not turn an outgoing
            // CONNECTING attempt into IDLE just because the group is not ready
            // yet; only tear down a transport that was already established.
            AppLog.log(TAG, "p2p group not formed yet (transportState=${_state.value})")
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.AUTHENTICATING
            ) {
                scope.launch { handleGroupLost("p2p group lost", expectedGeneration) }
            }
            return
        }
        AppLog.log(TAG, "p2p group formed (owner=${info.isGroupOwner})")
        scope.launch {
            mutex.withLock {
                if (expectedGeneration != operationGeneration.get()) return@withLock
                if (!listeningRequested &&
                    _state.value != TransportState.CONNECTING &&
                    _state.value != TransportState.AUTHENTICATING &&
                    _state.value != TransportState.CONNECTED
                ) return@withLock
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
                // The group formed from a user-selected incoming peer or
                // outgoing connect; neither discovery nor listen is needed
                // while this socket is being established.
                listeningRequested = false
                socketJob?.cancel()
                socketJob = scope.launch {
                    try {
                        val socket = withTimeout(Protocol.CONNECT_TIMEOUT_MS.toLong()) {
                            if (info.isGroupOwner) acceptAsOwner(info) else connectAsClient(info)
                        }
                        AppLog.log(TAG, "p2p socket established")
                        onSocketReady(socket, expectedGeneration)
                    } catch (e: TimeoutCancellationException) {
                        // The bounded socket setup actually timed out. A
                        // concurrent disconnect/group-loss may already have
                        // moved the transport to IDLE, so only publish failure
                        // while this socket setup is still the active state.
                        AppLog.log(TAG, "p2p socket setup timed out")
                        mutex.withLock {
                            if (operationGeneration.get() == expectedGeneration &&
                                _state.value == TransportState.AUTHENTICATING
                            ) {
                                // A timed-out socket setup must not be restarted
                                // by a duplicate group-formed broadcast.
                                operationGeneration.incrementAndGet()
                                listeningRequested = false
                                closeSocketLocked()
                                _error.value = TransportError.ConnectFailed(
                                    "socket setup timed out",
                                )
                                _state.value = TransportState.FAILED
                            }
                        }
                    } catch (e: CancellationException) {
                        // closeSocketLocked() intentionally cancels this job
                        // during disconnect/group loss. Propagate it so the
                        // cancelled operation cannot resurrect FAILED state.
                        throw e
                    } catch (e: Exception) {
                        AppLog.log(TAG, "p2p socket failed: ${e.javaClass.simpleName}: ${e.message}")
                        mutex.withLock {
                            if (operationGeneration.get() == expectedGeneration &&
                                _state.value == TransportState.AUTHENTICATING
                            ) {
                                // A failed socket setup ends this attempt. Ignore
                                // delayed callbacks until the user starts a new one.
                                operationGeneration.incrementAndGet()
                                listeningRequested = false
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
    }

    private fun acceptAsOwner(info: WifiP2pInfo): Socket {
        // Bind only to the P2P group-owner interface. Binding ServerSocket(port)
        // uses 0.0.0.0 and exposes the listener on unrelated LAN/VPN interfaces,
        // where another local process/device could occupy the port and DoS pairing.
        val groupAddress = info.groupOwnerAddress
            ?: throw IOException("group owner address unavailable")
        val server = ServerSocket().also {
            it.reuseAddress = false
            it.soTimeout = Protocol.CONNECT_TIMEOUT_MS
            it.bind(InetSocketAddress(groupAddress, Protocol.WIFI_DIRECT_PORT))
            serverSocket = it
        }
        Log.i(TAG, "group owner: accepting on P2P interface (port ${Protocol.WIFI_DIRECT_PORT})")
        return server.accept().also { Log.i(TAG, "group owner: peer socket accepted") }
    }

    private fun connectAsClient(info: WifiP2pInfo): Socket {
        val host = info.groupOwnerAddress?.hostAddress
            ?: throw IOException("group owner address unavailable")
        Log.i(TAG, "client: connecting to Wi-Fi Direct group owner")

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
                Log.i(TAG, "client: connected to Wi-Fi Direct group owner")
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

    private suspend fun onSocketReady(socket: Socket, expectedGeneration: Long) {
        mutex.withLock {
            if (operationGeneration.get() != expectedGeneration ||
                _state.value != TransportState.AUTHENTICATING
            ) {
                try { socket.close() } catch (_: IOException) {}
                AppLog.log(TAG, "discarding stale Wi-Fi Direct socket after stop/disconnect")
                return@withLock
            }
            framed?.close()
            framed = FramedSocket.fromTcpSocket(socket) {
                scope.launch { handleGroupLost(expectedGeneration = expectedGeneration) }
            }
            _state.value = TransportState.CONNECTED
            _error.value = null
            startReaderLocked(expectedGeneration)
        }
    }

    private fun startReaderLocked(expectedGeneration: Long) {
        readerJob?.cancel()
        val f = framed ?: return
        readerJob = scope.launch {
            while (operationGeneration.get() == expectedGeneration &&
                (_state.value == TransportState.CONNECTED ||
                    _state.value == TransportState.AUTHENTICATING)
            ) {
                // HS-04: readFrame() throws FrameException on protocol
                // violation — route it through the common disconnect path
                // instead of stranding the transport in CONNECTED.
                val r = try {
                    f.readFrame()
                } catch (e: FrameException) {
                    AppLog.log(TAG, "framing error: ${e.message}")
                    handleGroupLost("framing error: ${e.message}", expectedGeneration)
                    return@launch
                } catch (e: CancellationException) {
                    throw e // normal shutdown; not an error
                } catch (e: Exception) {
                    AppLog.log(TAG, "reader failed: ${e.javaClass.simpleName}")
                    handleGroupLost("read error: ${e.message}", expectedGeneration)
                    return@launch
                }
                when (r) {
                    is FramedSocket.ReadResult.Frame -> _incomingFrames.emit(r.frame)
                    is FramedSocket.ReadResult.Closed -> {
                        handleGroupLost(expectedGeneration = expectedGeneration)
                        return@launch
                    }
                    is FramedSocket.ReadResult.Error -> {
                        if (r.cause is SocketTimeoutException) continue // idle read timeout
                        handleGroupLost(r.cause.message ?: "read error", expectedGeneration)
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun handleGroupLost(
        reason: String = "connection lost",
        expectedGeneration: Long? = null,
    ) {
        mutex.withLock {
            if (expectedGeneration != null &&
                expectedGeneration != operationGeneration.get()
            ) return@withLock
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.AUTHENTICATING
            ) {
                _error.value = TransportError.ConnectionLost(reason)
            }
            // A lost/failed group ends the active listen/connect attempt. Bump
            // the generation and clear the listen intent before releasing the
            // lock so delayed framework callbacks cannot resurrect an old
            // socket after SessionManager has started teardown.
            operationGeneration.incrementAndGet()
            listeningRequested = false
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
        val (f, generation) = mutex.withLock { framed to operationGeneration.get() }
        f ?: throw IOException("not connected")
        try {
            f.writeFrame(frameBytes)
        } catch (e: IOException) {
            scope.launch { handleGroupLost(e.message ?: "write error", generation) }
            throw e
        }
    }

    override suspend fun disconnect() {
        // Invalidate first, before waiting for the mutex held by connect/socket
        // setup. Those callbacks must not publish CONNECTED after user Stop.
        operationGeneration.incrementAndGet()
        discoveryGeneration.incrementAndGet()
        listeningRequested = false
        mutex.withLock {
            closeSocketLocked()
            try {
                manager?.removeGroup(channel, null)
            } catch (_: Exception) {}
            try {
                manager?.cancelConnect(channel, null)
            } catch (_: Exception) {}
            _state.value = TransportState.IDLE
            // Preserve terminal errors for the screen after SessionManager
            // tears down a failed connection. stopDiscovery clears them next time.
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
                            } catch (e: IllegalStateException) {
                                AppLog.log(TAG, "P2P state query ignored: channel is not ready (${e.message})")
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
                    AppLog.log(TAG, "p2p peers-changed broadcast received")
                    requestCurrentPeers(discoveryGeneration.get(), "peers-changed-broadcast")
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
                        val expectedGeneration = operationGeneration.get()
                        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                            onConnectionInfo(info, expectedGeneration)
                        }
                    } catch (e: SecurityException) {
                        AppLog.log(TAG, "connection info query denied: ${e.message}")
                    } catch (e: IllegalStateException) {
                        AppLog.log(TAG, "connection info query ignored: channel is not ready (${e.message})")
                    }
                }
            }
        }
    }

    /**
     * Query the current Wi-Fi P2P state on API 29+, instead of relying only on
     * a state-changed broadcast that may have been sent before receiver setup.
     * Returns null if the framework does not answer; the last known state is
     * preserved in that case.
     */
    private suspend fun refreshP2pEnabledState(): Boolean? {
        if (Build.VERSION.SDK_INT < 29) return p2pEnabled
        val mgr = manager ?: return null
        val ch = channel ?: return null
        val state = CompletableDeferred<Int>()
        return try {
            mgr.requestP2pState(ch) { value ->
                if (!state.isCompleted) state.complete(value)
            }
            val reportedState = withTimeout(1_500L) { state.await() }
            (reportedState == WifiP2pManager.WIFI_P2P_STATE_ENABLED).also {
                p2pEnabled = it
                AppLog.log(TAG, "p2p framework state refreshed: enabled=$it")
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.log(TAG, "p2p state refresh timed out; keeping last state=$p2pEnabled")
            null
        } catch (e: SecurityException) {
            AppLog.log(TAG, "p2p state refresh denied: ${e.message}")
            null
        } catch (e: IllegalStateException) {
            AppLog.log(TAG, "p2p state refresh rejected: ${e.message}")
            null
        }
    }

    /**
     * Android documents that discoverPeers(), requestPeers(), and service
     * discovery require system Location Mode to be enabled. This is a device
     * switch, distinct from the app's runtime location/nearby-device grants.
     */
    private fun isSystemLocationEnabled(): Boolean? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                locationManager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            }
        } catch (e: Exception) {
            AppLog.log(TAG, "could not read system Location switch: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Fetch a peer snapshot both when discovery starts and after framework
     * updates. This is not a substitute for PEERS_CHANGED events; it avoids
     * relying on a single OEM-specific broadcast timing for cached peers.
     */
    @SuppressLint("MissingPermission")
    private fun requestCurrentPeers(expectedDiscovery: Long, source: String) {
        if (expectedDiscovery != discoveryGeneration.get()) return
        val missing = PermissionHelper.missingWifiDirectPermissions(context)
        if (missing.isNotEmpty()) {
            AppLog.log(TAG, "p2p requestPeers skipped: missing permissions=${missing.joinToString()}")
            return
        }
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            AppLog.log(TAG, "p2p requestPeers skipped: manager/channel unavailable")
            return
        }
        val snapshotRequest = peerSnapshotGeneration.incrementAndGet()
        try {
            mgr.requestPeers(ch) { list: WifiP2pDeviceList ->
                // Drop late responses from an earlier query: otherwise an
                // empty initial snapshot could overwrite a newer populated
                // peer list returned after the PEERS_CHANGED broadcast.
                if (
                    expectedDiscovery != discoveryGeneration.get() ||
                    snapshotRequest != peerSnapshotGeneration.get()
                ) return@requestPeers
                val mapped = list.deviceList.map { device: WifiP2pDevice ->
                    PeerDevice(
                        id = device.deviceAddress,
                        displayName = device.deviceName.ifBlank { device.deviceAddress },
                        transport = TransportType.WIFI_DIRECT,
                    )
                }
                _peers.value = mapped
                AppLog.log(
                    TAG,
                    "p2p peer snapshot source=$source visiblePeers=${mapped.size}",
                )
            }
        } catch (e: SecurityException) {
            AppLog.log(TAG, "p2p requestPeers denied by Android: ${e.message}")
        } catch (e: IllegalStateException) {
            AppLog.log(TAG, "p2p requestPeers rejected by framework: ${e.message}")
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
