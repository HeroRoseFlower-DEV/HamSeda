package com.hamseda.walkie

import com.hamseda.walkie.audio.AudioCodec
import com.hamseda.walkie.audio.AudioPipeline
import com.hamseda.walkie.audio.JitterBuffer
import com.hamseda.walkie.proto.Frame
import com.hamseda.walkie.transport.Availability
import com.hamseda.walkie.transport.PeerDevice
import com.hamseda.walkie.transport.Transport
import com.hamseda.walkie.transport.TransportError
import com.hamseda.walkie.transport.TransportState
import com.hamseda.walkie.transport.TransportType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory loopback transport pair for session tests: frames sent on one
 * side are delivered to the other's [incomingFrames]. Optionally wraps
 * sent bytes with [intercept] (e.g. to simulate a MITM tampering).
 */
class LoopbackTransport(
    override val type: TransportType = TransportType.WIFI_DIRECT,
    private val intercept: ((ByteArray) -> ByteArray)? = null,
) : Transport {
    var linked: LoopbackTransport? = null

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    private val _error = MutableStateFlow<TransportError?>(null)
    override val error: StateFlow<TransportError?> = _error.asStateFlow()
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()
    override val discoverable: StateFlow<Boolean> =
        MutableStateFlow(true).asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incomingFrames: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    fun setState(s: TransportState) {
        _state.value = s
    }

    override fun clearError() {
        _error.value = null
    }

    override fun availability() = Availability(true, true)
    override suspend fun startDiscovery() {}
    override suspend fun stopDiscovery() {}

    override suspend fun connect(peer: PeerDevice) {
        setState(TransportState.CONNECTING)
        // Both ends become connected, like a real P2P group forming.
        setState(TransportState.CONNECTED)
        linked?.setState(TransportState.CONNECTED)
    }

    override suspend fun listen() {}

    override suspend fun sendFrame(frameBytes: ByteArray) {
        val out = intercept?.invoke(frameBytes) ?: frameBytes
        linked?._incoming?.emit(out)
    }

    override suspend fun disconnect() {
        setState(TransportState.IDLE)
        linked?.setState(TransportState.IDLE)
    }

    override fun close() {}

    companion object {
        fun pair(
            interceptA: ((ByteArray) -> ByteArray)? = null,
            interceptB: ((ByteArray) -> ByteArray)? = null,
        ): Pair<LoopbackTransport, LoopbackTransport> {
            val a = LoopbackTransport(intercept = interceptA)
            val b = LoopbackTransport(intercept = interceptB)
            a.linked = b
            b.linked = a
            return a to b
        }
    }
}

/** Fake voice pipeline: records calls, lets tests drive capture frames. */
class FakeAudioPipeline : AudioPipeline {
    override var pipelineListener: AudioPipeline.PipelineListener? = null

    var captureCodec: AudioCodec? = null
    var onFrame: ((ByteArray, Float) -> Unit)? = null
    var playbackCodec: AudioCodec? = null
    var playbackBuffer: JitterBuffer? = null
    var speakerphoneOn: Boolean = true
        private set

    override fun startCapture(
        codec: AudioCodec,
        onFrame: (encoded: ByteArray, level: Float) -> Unit,
    ): Boolean {
        captureCodec = codec
        this.onFrame = onFrame
        return true
    }

    override fun stopCapture() {
        captureCodec = null
        onFrame = null
    }

    override fun startPlayback(codec: AudioCodec, buffer: JitterBuffer): Boolean {
        playbackCodec = codec
        playbackBuffer = buffer
        return true
    }

    override fun stopPlayback() {
        playbackCodec = null
        playbackBuffer = null
    }

    override fun setSpeakerphone(on: Boolean) {
        speakerphoneOn = on
    }

    override val isCapturing: Boolean get() = onFrame != null
}

/** Decodes a raw frame for test interception (uses wall clock). */
fun decodeType(bytes: ByteArray): Byte = Frame.decode(bytes).type
