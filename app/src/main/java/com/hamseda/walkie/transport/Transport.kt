package com.hamseda.walkie.transport

import com.hamseda.walkie.proto.FramedSocket
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The two direct, offline transports HamSeda supports. */
enum class TransportType { WIFI_DIRECT, BLUETOOTH }

/**
 * Transport lifecycle states. The UI renders these directly — a transport
 * must never report CONNECTED unless a real socket handshake succeeded.
 */
enum class TransportState {
    /** Hardware missing or radio off — with a human-readable reason. */
    UNAVAILABLE,
    IDLE,
    DISCOVERING,
    /** Outgoing connection attempt in progress. */
    CONNECTING,
    /** Socket established; session handshake running above. */
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

/** Typed transport failures surfaced to the UI. */
sealed interface TransportError {
    data class PermissionDenied(val missing: List<String>) : TransportError
    data class RadioDisabled(val transport: TransportType) : TransportError
    data class PeerNotFound(val peerId: String) : TransportError
    data class ConnectFailed(val reason: String) : TransportError
    data class HandshakeTimeout(val reason: String) : TransportError
    data class ConnectionLost(val reason: String) : TransportError
    data class Unsupported(val reason: String) : TransportError
}

/**
 * A discovered peer device.
 *
 * WARNING: [displayName] is the device's self-reported Bluetooth/Wi-Fi
 * Direct name. It is **never** treated as identity — the session layer
 * authenticates via ECDH + SAS comparison. Names are display-only.
 */
data class PeerDevice(
    val id: String, // MAC / Wi-Fi P2P device address — the connect key
    val displayName: String,
    val transport: TransportType,
)

/** Hardware/radio capability report. */
data class Availability(
    val supported: Boolean,
    val enabled: Boolean,
    val reason: String = "",
)

/**
 * Common interface for the direct offline transports.
 *
 * Implementations own their sockets, reader threads/coroutines, and
 * receivers, and must release all of them in [disconnect]/[close] on every
 * lifecycle and error path. Frames are exchanged as complete encoded
 * [com.hamseda.walkie.proto.Frame] byte arrays via [incomingFrames] /
 * [sendFrame]; framing and EOF detection are handled inside [FramedSocket].
 */
interface Transport {
    val type: TransportType
    val state: StateFlow<TransportState>
    val error: StateFlow<TransportError?>
    val peers: StateFlow<List<PeerDevice>>

    /** Frames received from the connected peer (complete encoded frames). */
    val incomingFrames: SharedFlow<ByteArray>

    fun availability(): Availability

    /** Starts peer discovery; results flow into [peers]. */
    suspend fun startDiscovery()

    suspend fun stopDiscovery()

    /**
     * Connects to [peer] and establishes the framed socket. On success the
     * state becomes CONNECTED (or AUTHENTICATING while the session layer
     * runs its handshake). Never blocks the caller indefinitely — bounded
     * by [com.hamseda.walkie.proto.Protocol.CONNECT_TIMEOUT_MS].
     */
    suspend fun connect(peer: PeerDevice)

    /**
     * Listens for an incoming connection (be the acceptor side). Used for
     * the Bluetooth RFCOMM server socket and the Wi-Fi Direct group-owner
     * server socket.
     */
    suspend fun listen()

    suspend fun sendFrame(frameBytes: ByteArray)

    /** Tears down the connection and returns to IDLE (or UNAVAILABLE). */
    suspend fun disconnect()

    /** Releases receivers, threads, and sockets permanently. */
    fun close()
}

/** User's transport preference (persisted in settings). */
enum class TransportPreference {
    WIFI_DIRECT,
    BLUETOOTH,
    /** Let the app pick the best available radio; the choice is shown. */
    AUTO_RECOMMEND,
}
