package com.hamseda.walkie.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

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

    override fun availability(): Availability {
        val a = adapter ?: return Availability(false, false, "bluetooth_unsupported")
        return Availability(true, a.isEnabled, if (a.isEnabled) "" else "bluetooth_disabled")
    }

    // ------------------------------------------------------------ discovery

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = true)
        if (missing.isNotEmpty()) {
            _error.value = TransportError.PermissionDenied(missing)
            return
        }
        val a = adapter ?: run {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.Unsupported("bluetooth_unsupported")
            return
        }
        if (!a.isEnabled) {
            _state.value = TransportState.UNAVAILABLE
            _error.value = TransportError.RadioDisabled(TransportType.BLUETOOTH)
            return
        }
        registerReceiver()
        found.clear()
        _peers.value = emptyList()
        if (a.isDiscovering) a.cancelDiscovery()
        if (a.startDiscovery()) {
            _state.value = TransportState.DISCOVERING
            _error.value = null
        } else {
            _error.value = TransportError.ConnectFailed("bluetooth discovery failed to start")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
        if (_state.value == TransportState.DISCOVERING) _state.value = TransportState.IDLE
    }

    // ------------------------------------------------------------- connect

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: PeerDevice) {
        val missing = PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
        if (missing.isNotEmpty()) {
            _error.value = TransportError.PermissionDenied(missing)
            return
        }
        mutex.withLock {
            if (_state.value == TransportState.CONNECTED ||
                _state.value == TransportState.CONNECTING
            ) return
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
            try {
                a.cancelDiscovery()
            } catch (_: SecurityException) {}
            stopAcceptLocked()
            scope.launch {
                var socket: BluetoothSocket? = null
                try {
                    val device: BluetoothDevice = try {
                        a.getRemoteDevice(peer.id)
                    } catch (e: IllegalArgumentException) {
                        throw IOException("invalid device address: ${peer.id}")
                    }
                    socket = device.createRfcommSocketToServiceRecord(
                        Protocol.BLUETOOTH_SERVICE_UUID,
                    )
                    // connect() blocks; closing the socket aborts it, so on
                    // timeout we close below to avoid a leaked thread.
                    withTimeout(Protocol.CONNECT_TIMEOUT_MS.toLong()) {
                        socket.connect()
                    }
                    onSocketReady(socket)
                    socket = null // owned by the transport now
                } catch (e: Exception) {
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
        } catch (e: IOException) {
            _error.value = TransportError.ConnectFailed("rfcomm listen failed: ${e.message}")
            return
        } catch (e: SecurityException) {
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
                        mutex.withLock {
                            // One-to-one MVP: keep the first live connection.
                            if (framed != null) {
                                try { socket.close() } catch (_: IOException) {}
                                return@withLock
                            }
                            try {
                                adapter?.cancelDiscovery()
                            } catch (_: SecurityException) {}
                            onSocketReady(socket)
                        }
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
                when (val r = f.readFrame()) {
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
                    val peer = PeerDevice(
                        id = address,
                        displayName = name?.takeIf { it.isNotBlank() } ?: address,
                        transport = TransportType.BLUETOOTH,
                    )
                    found[address] = peer
                    _peers.value = found.values.toList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_state.value == TransportState.DISCOVERING) {
                        _state.value = TransportState.IDLE
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
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

    companion object {
        private const val TAG = "HamSedaBluetooth"
    }
}
