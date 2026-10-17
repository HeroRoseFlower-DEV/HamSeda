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
    /**
     * Optional transport-internal intermediate state: the link (e.g. the Wi-Fi
     * Direct group) exists but the framed socket is not ready yet. Transports
     * with atomic setup (Bluetooth RFCOMM) skip it and go CONNECTING directly
     * to CONNECTED. Consumers must treat AUTHENTICATING as "connection in
     * progress", never as a usable socket.
     */
    AUTHENTICATING,
    CONNECTED,
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

    /**
     * True when this device can currently be *found* by a peer's scan.
     * Bluetooth Classic requires explicit discoverability
     * ([BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE]); Wi-Fi Direct
     * P2P discovery needs none, so it reports true.
     */
    val discoverable: StateFlow<Boolean>

    /** Frames received from the connected peer (complete encoded frames). */
    val incomingFrames: SharedFlow<ByteArray>

    fun availability(): Availability

    /** Clears the last surfaced [error] (e.g. after the user dismisses it). */
    fun clearError()

    /** Starts peer discovery; results flow into [peers]. */
    suspend fun startDiscovery()

    suspend fun stopDiscovery()

    /**
     * Prepare a selected peer connection. Wi-Fi Direct can stop peer discovery
     * itself when connect() begins; other transports may need an explicit stop.
     */
    suspend fun prepareForConnect() {
        stopDiscovery()
    }

    /**
     * Prepare this device to wait for an incoming peer. Wi-Fi Direct's modern
     * listen mode must not race an asynchronous stopPeerDiscovery() request.
     */
    suspend fun prepareForListen() {
        stopDiscovery()
    }

    /**
     * Connects to [peer] and establishes the framed socket. On success the
     * state becomes CONNECTED (transports may report the transport-internal
     * AUTHENTICATING state while the session layer runs its handshake).
     * Never blocks the caller indefinitely — bounded
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

    /**
     * Sends complete frames in order. Stream transports may combine adjacent
     * frames into one write while preserving each existing length-prefixed
     * wire envelope. Other transports keep the default one-frame-at-a-time path.
     */
    suspend fun sendFrames(frames: List<ByteArray>) {
        for (frame in frames) sendFrame(frame)
    }

    /** Tears down the connection and returns to IDLE (or UNAVAILABLE). */
    suspend fun disconnect()

    /** Releases receivers, threads, and sockets permanently. */
    fun close()
}

/**
 * Guards a just-read socket frame against publication after the transport has
 * moved to a newer connection attempt. The socket identity check matters even
 * when a vendor callback or cancelled reader completes late.
 */
internal fun isCurrentIncomingFrame(
    expectedGeneration: Long,
    currentGeneration: Long,
    expectedSocket: Any,
    currentSocket: Any?,
    state: TransportState,
): Boolean =
    expectedGeneration == currentGeneration &&
        expectedSocket === currentSocket &&
        (state == TransportState.CONNECTED || state == TransportState.AUTHENTICATING)

/**
 * Wipe a frame buffer that is being discarded (stale generation, replaced
 * socket). Discarded frames may hold ciphertext; never let them linger.
 * Callers log the discard reason themselves.
 */
internal fun discardStaleFrame(frame: ByteArray) {
    frame.fill(0)
}

/** User's transport preference (persisted in settings). */
enum class TransportPreference {
    WIFI_DIRECT,
    BLUETOOTH,
    /** Let the app pick the best available radio; the choice is shown. */
    AUTO_RECOMMEND,
}
