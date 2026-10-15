package com.hamseda.walkie.session

import android.util.Log
import com.hamseda.walkie.audio.AudioCodec
import com.hamseda.walkie.audio.AudioPipeline
import com.hamseda.walkie.audio.JitterBuffer
import com.hamseda.walkie.audio.OpusCodec
import com.hamseda.walkie.crypto.ReplayProtection
import com.hamseda.walkie.crypto.SessionCrypto
import com.hamseda.walkie.crypto.SessionKeys
import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.proto.DenyReason
import com.hamseda.walkie.proto.DisconnectReason
import com.hamseda.walkie.proto.Frame
import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.MessageType
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.transport.PeerDevice
import com.hamseda.walkie.transport.Transport
import com.hamseda.walkie.transport.TransportError
import com.hamseda.walkie.transport.TransportState
import com.hamseda.walkie.transport.TransportType
import com.hamseda.walkie.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated one-to-one voice session over an attached [Transport].
 *
 * Session establishment (both sides run the same symmetric logic):
 * 1. Transport reports CONNECTED → ephemeral P-256 keypair + 16-byte nonce
 *    are generated and HELLO (protocol version + codec preference) is sent.
 * 2. On HELLO → KEY_EXCHANGE (X.509 public key + nonce) is sent (once).
 * 3. Once both public keys are known → ECDH → HKDF-SHA-256 →
 *    [SessionKeys]; the transcript hash is sealed with the control key as
 *    KEY_CONFIRM. A mismatch aborts with AUTH_MISMATCH.
 * 4. Both sides display the 6-digit SAS. The users compare out-of-band and
 *    each taps confirm → SAS_CONFIRM is exchanged. Audio is enabled only
 *    after **mutual** confirmation.
 * 5. IN_SESSION: half-duplex PTT via [FloorController], AES-256-GCM audio
 *    frames with replay protection, ping/pong liveness.
 *
 * Codec negotiation: each HELLO carries a codec preference; the session
 * uses min(preferenceA, preferenceB) — Opus (0x00) wins unless both sides
 * prefer PCM. Deterministic, no extra round trips.
 *
 * All coroutines run in the caller-provided [scope] (the foreground
 * service's scope in production). [close] is idempotent.
 */
class SessionManager(
    private val scope: CoroutineScope,
    private val audio: AudioPipeline,
    /** Wall clock is used only for wire timestamps; elapsed durations use a monotonic clock. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : FloorController.Listener, AudioPipeline.PipelineListener {

    enum class Phase {
        IDLE, CONNECTING_TRANSPORT, HANDSHAKE, AWAITING_SAS_CONFIRM, IN_SESSION, ENDED
    }

    enum class SessionError {
        AUTH_MISMATCH, PEER_BUSY, TRANSPORT_LOST, PEER_TIMEOUT,
        PROTOCOL_ERROR, MIC_UNAVAILABLE, PLAYBACK_UNAVAILABLE, PEER_REJECTED,
    }

    // ------------------------------------------------------- observable state

    private val lifecycleLock = Any()
    /** Serializes handshake startup with inbound-frame processing on separate dispatcher threads. */
    private val handshakeFrameLock = Any()
    /** Independent cleanup survives VoiceService cancelling its child scope in onDestroy(). */
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(cleanupJob + Dispatchers.IO)
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _peerName = MutableStateFlow("")
    val peerName: StateFlow<String> = _peerName.asStateFlow()

    private val _transportType = MutableStateFlow<TransportType?>(null)
    val transportType: StateFlow<TransportType?> = _transportType.asStateFlow()

    private val _transportState = MutableStateFlow(TransportState.IDLE)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _sasCode = MutableStateFlow<String?>(null)
    val sasCode: StateFlow<String?> = _sasCode.asStateFlow()

    private val _peerSasConfirmed = MutableStateFlow(false)
    val peerSasConfirmed: StateFlow<Boolean> = _peerSasConfirmed.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _floorHolder = MutableStateFlow(FloorController.Holder.NONE)
    val floorHolder: StateFlow<FloorController.Holder> = _floorHolder.asStateFlow()

    private val _voiceLevel = MutableStateFlow(0f)
    val voiceLevel: StateFlow<Float> = _voiceLevel.asStateFlow()

    private val _error = MutableStateFlow<SessionError?>(null)
    val error: StateFlow<SessionError?> = _error.asStateFlow()

    private val _authFailures = MutableStateFlow(0)
    val authFailures: StateFlow<Int> = _authFailures.asStateFlow()

    // ------------------------------------------------------- session fields

    private var transport: Transport? = null
    private var collectJob: Job? = null
    private var transportWatchJob: Job? = null
    private var livenessJob: Job? = null
    /** True once the attached transport connected in this session attempt. */
    private var transportWasConnected = false

    private var myKeyPair: java.security.KeyPair? = null
    private var myNonce = ByteArray(0)
    private var myPubBytes = ByteArray(0)
    private var peerPubBytes: ByteArray? = null
    private var peerNonce: ByteArray? = null
    private var keys: SessionKeys? = null
    private var myRole: Byte = 0
    private var keyExchangeSent = false
    private var keyConfirmSent = false
    private var peerHelloReceived = false
    private var peerCodecPref: Byte? = null
    /** True once beginHandshake ran for the current attempt (re-entrancy guard). */
    private var handshakeStarted = false
    private var selfSasConfirmed = false
    private var myCodecPref: Byte = CodecId.OPUS
    private var activeCodecId: Byte = CodecId.OPUS
    private var codec: AudioCodec? = null

    private val sendSequence = SessionSequenceCounter()
    private val sequenceExhaustionTriggered = AtomicBoolean(false)
    /** Dense audio-only sequence for playout; wire seq also numbers control frames. */
    private var receivedAudioSeq: Long = 0
    private val replay = ReplayProtection()
    private val jitter = JitterBuffer()
    private val floor = FloorController(elapsedClock, this)

    /**
     * Monotonic session generation (HS-07). Incremented on every
     * attachTransport; delayed async callbacks (deadlines, handshake steps)
     * capture the generation and no-op if it changed, so a stale callback
     * from an old session can never mutate a newer one.
     */
    private var sessionGeneration = 0L
    // Monotonic timestamps used only for aggregate session lifecycle diagnostics.
    // Null means the corresponding phase was never reached in this attempt.
    @Volatile private var attemptStartedAtMs: Long? = null
    @Volatile private var transportConnectedAtMs: Long? = null
    @Volatile private var sessionEstablishedAtMs: Long? = null
    private var handshakeDeadlineJob: Job? = null
    private var sasDeadlineJob: Job? = null

    private var lastPeerSeen: Long = 0
    private var lastLevelPush: Long = 0
    private var pttHeld = false

    // Inter-arrival telemetry after authentication/replay checks, before jitter insertion.
    private var lastAudioReceiveAtNanos = 0L
    private var audioReceiveWindowFrames = 0
    private var audioReceiveIntervalSamples = 0
    private var audioReceiveIntervalTotalNanos = 0L
    private var audioReceiveIntervalMaxNanos = 0L
    private var audioReceiveGapsOver30Ms = 0
    private var audioReceiveGapsOver60Ms = 0
    private var audioReceiveGapsOver100Ms = 0

    // ------------------------------------------------------- outbound pipeline
    //
    // HS-02: all outgoing messages pass through one bounded FIFO and one sender.
    // The sender allocates wire sequence numbers, seals and writes frames in
    // deterministic order. Audio overflow evicts the oldest queued audio so
    // congestion drops stale speech rather than turning it into delayed speech.
    // A conflated signal wakes the consumer without creating a coroutine per frame.

    private sealed interface OutboundMsg {
        data class Plain(val type: Byte, val payload: ByteArray) : OutboundMsg
        data class Sealed(
            val type: Byte,
            val codecId: Byte,
            /** Captured at enqueue so a later rekey/teardown can't mix them. */
            val sessionId: ByteArray,
            val key: ByteArray,
            val role: Byte,
            val plaintext: ByteArray,
            /** Monotonic enqueue time; used only for aggregate real-time diagnostics. */
            val enqueuedAtNanos: Long = System.nanoTime(),
        ) : OutboundMsg
    }

    private val outboundQueue = RealtimeOutboundQueue<OutboundMsg>(
        maxPending = MAX_PENDING_OUTBOUND_MESSAGES,
        maxPendingAudio = MAX_QUEUED_AUDIO_FRAMES,
        isAudio = { isAudioMessage(it) },
        onDiscard = ::wipeOutboundMessage,
    )
    private val outboundSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private var senderJob: Job? = null
    @Volatile private var lastLoggedAudioDrops = 0L

    // Written only by the single sender coroutine. Windowed aggregates help
    // distinguish queue starvation, crypto/frame-build cost, and transport
    // backpressure without logging per-frame payloads or timestamps.
    private var audioTimingSamples = 0
    private var audioTimingSent = 0
    private var audioTimingFailed = 0
    private var audioQueueWaitTotalNanos = 0L
    private var audioQueueWaitMaxNanos = 0L
    private var audioBuildTotalNanos = 0L
    private var audioBuildMaxNanos = 0L
    private var audioTransportTotalNanos = 0L
    private var audioTransportMaxNanos = 0L

    private fun isAudioMessage(msg: OutboundMsg): Boolean =
        msg is OutboundMsg.Sealed && msg.type == MessageType.AUDIO

    /** Clear queued plaintext after send, eviction, rejection, or cancellation. */
    private fun wipeOutboundMessage(msg: OutboundMsg) {
        when (msg) {
            is OutboundMsg.Plain -> msg.payload.fill(0)
            is OutboundMsg.Sealed -> {
                msg.sessionId.fill(0)
                msg.plaintext.fill(0)
                // msg.key is session-owned key material; teardown wipes it centrally.
            }
        }
    }

    private fun drainOutbound() {
        outboundQueue.clear()
        while (outboundSignal.tryReceive().isSuccess) {
            // Remove stale wakeups before binding a new transport.
        }
    }

    private suspend fun nextOutbound(): OutboundMsg {
        while (true) {
            outboundQueue.poll()?.let { return it }
            // Producers enqueue under the queue lock and then signal. A signal
            // between poll() and receive() is retained by the conflated channel.
            outboundSignal.receive()
        }
    }

    private fun enqueueOutbound(msg: OutboundMsg): Boolean {
        val accepted = outboundQueue.offer(msg)
        if (accepted) outboundSignal.trySend(Unit)
        return accepted
    }

    private fun startSender() {
        senderJob?.cancel()
        senderJob = null
        drainOutbound()
        resetAudioTimingWindow()
        sequenceExhaustionTriggered.set(false)
        senderJob = scope.launch {
            while (isActive && !sequenceExhaustionTriggered.get()) {
                val first = nextOutbound()
                val t = transport
                if (t == null) {
                    wipeOutboundMessage(first)
                    break
                }
                val batch = ArrayList<OutboundMsg>(MAX_BLUETOOTH_AUDIO_BATCH_FRAMES)
                batch.add(first)
                if (t.type == com.hamseda.walkie.transport.TransportType.BLUETOOTH &&
                    isAudioMessage(first)
                ) {
                    // Batch only speech already queued at the head; add no timer delay
                    // and never move audio across a control message.
                    batch.addAll(
                        outboundQueue.pollConsecutiveAudio(MAX_BLUETOOTH_AUDIO_BATCH_FRAMES - 1),
                    )
                }
                sendOutboundBatch(batch, t)
            }
        }
    }

    private suspend fun sendOutboundBatch(messages: List<OutboundMsg>, t: Transport) {
        if (messages.isEmpty()) return
        val queuedAtWaitNanos = LongArray(messages.size) { index ->
            val message = messages[index]
            if (isAudioMessage(message)) {
                val audio = message as OutboundMsg.Sealed
                (System.nanoTime() - audio.enqueuedAtNanos).coerceAtLeast(0L)
            } else 0L
        }
        val buildNanos = LongArray(messages.size)
        val built = BooleanArray(messages.size)
        val frames = ArrayList<ByteArray>(messages.size)
        var transportNanos = 0L
        var delivered = false
        try {
            for ((index, message) in messages.withIndex()) {
                val buildStartedAt = System.nanoTime()
                try {
                    val bytes = when (message) {
                        is OutboundMsg.Plain -> buildPlainFrame(message.type, message.payload)
                        is OutboundMsg.Sealed -> buildSealedFrame(message)
                    }
                    buildNanos[index] = System.nanoTime() - buildStartedAt
                    built[index] = true
                    frames.add(bytes)
                } catch (e: CancellationException) {
                    buildNanos[index] = System.nanoTime() - buildStartedAt
                    throw e
                } catch (e: SessionSequenceCounter.SequenceExhaustedException) {
                    buildNanos[index] = System.nanoTime() - buildStartedAt
                    if (sequenceExhaustionTriggered.compareAndSet(false, true)) {
                        Log.w(TAG, "session sequence safety limit reached; ending session before uint32 wrap")
                        // No sequence remains for a trustworthy DISCONNECT frame. Fail closed
                        // and close the transport rather than wrapping the wire counter.
                        endSession(SessionError.PROTOCOL_ERROR, notifyPeer = false)
                    }
                    break
                } catch (e: Exception) {
                    buildNanos[index] = System.nanoTime() - buildStartedAt
                    Log.w(TAG, "outbound build failed: " + e.javaClass.simpleName)
                }
            }
            if (frames.isNotEmpty()) {
                val transportStartedAt = System.nanoTime()
                try {
                    t.sendFrames(frames)
                    delivered = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "outbound send failed: " + e.javaClass.simpleName)
                    // Keep the sender alive for possible teardown messages.
                } finally {
                    transportNanos = System.nanoTime() - transportStartedAt
                }
            }
        } finally {
            val builtFrameCount = frames.size
            val perFrameTransportNanos = if (builtFrameCount > 1) {
                transportNanos / builtFrameCount
            } else {
                transportNanos
            }
            messages.forEachIndexed { index, message ->
                if (isAudioMessage(message)) {
                    recordAudioTiming(
                        queueWaitNanos = queuedAtWaitNanos[index],
                        buildNanos = buildNanos[index],
                        transportNanos = if (built[index]) perFrameTransportNanos else 0L,
                        delivered = delivered && built[index],
                    )
                }
                wipeOutboundMessage(message)
            }
            frames.forEach { it.fill(0) }
        }
    }

    private fun resetAudioReceiveTiming() {
        lastAudioReceiveAtNanos = 0L
        audioReceiveWindowFrames = 0
        audioReceiveIntervalSamples = 0
        audioReceiveIntervalTotalNanos = 0L
        audioReceiveIntervalMaxNanos = 0L
        audioReceiveGapsOver30Ms = 0
        audioReceiveGapsOver60Ms = 0
        audioReceiveGapsOver100Ms = 0
    }

    private fun recordAudioReceiveTiming() {
        val now = System.nanoTime()
        val previous = lastAudioReceiveAtNanos
        lastAudioReceiveAtNanos = now
        audioReceiveWindowFrames++
        if (previous > 0L) {
            val interval = (now - previous).coerceAtLeast(0L)
            audioReceiveIntervalSamples++
            audioReceiveIntervalTotalNanos += interval
            audioReceiveIntervalMaxNanos = maxOf(audioReceiveIntervalMaxNanos, interval)
            if (interval >= 30_000_000L) audioReceiveGapsOver30Ms++
            if (interval >= 60_000_000L) audioReceiveGapsOver60Ms++
            if (interval >= 100_000_000L) audioReceiveGapsOver100Ms++
        }
        if (audioReceiveWindowFrames < AUDIO_RECEIVE_TIMING_LOG_INTERVAL) return

        fun ms(nanos: Long) = String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0)
        val intervalSamples = audioReceiveIntervalSamples
        val avgIntervalNanos = if (intervalSamples > 0) {
            audioReceiveIntervalTotalNanos / intervalSamples
        } else 0L
        AppLog.log(
            TAG,
            "audio receive timing: frames=$audioReceiveWindowFrames, intervals=$intervalSamples, " +
                "avgIntervalMs=" + ms(avgIntervalNanos) + ", maxIntervalMs=" + ms(audioReceiveIntervalMaxNanos) + ", " +
                "gapsOver30Ms=$audioReceiveGapsOver30Ms, gapsOver60Ms=$audioReceiveGapsOver60Ms, " +
                "gapsOver100Ms=$audioReceiveGapsOver100Ms, jitterBuffered=" + jitter.bufferedFrames() + ", " +
                "jitterStaleDropped=" + jitter.droppedStale + ", jitterLost=" + jitter.lost,
        )
        // Keep lastAudioReceiveAtNanos across windows for a continuous cadence measurement.
        audioReceiveWindowFrames = 0
        audioReceiveIntervalSamples = 0
        audioReceiveIntervalTotalNanos = 0L
        audioReceiveIntervalMaxNanos = 0L
        audioReceiveGapsOver30Ms = 0
        audioReceiveGapsOver60Ms = 0
        audioReceiveGapsOver100Ms = 0
    }

    private fun resetAudioTimingWindow() {
        audioTimingSamples = 0
        audioTimingSent = 0
        audioTimingFailed = 0
        audioQueueWaitTotalNanos = 0L
        audioQueueWaitMaxNanos = 0L
        audioBuildTotalNanos = 0L
        audioBuildMaxNanos = 0L
        audioTransportTotalNanos = 0L
        audioTransportMaxNanos = 0L
    }

    private fun recordAudioTiming(
        queueWaitNanos: Long,
        buildNanos: Long,
        transportNanos: Long,
        delivered: Boolean,
    ) {
        audioTimingSamples++
        if (delivered) audioTimingSent++ else audioTimingFailed++
        audioQueueWaitTotalNanos += queueWaitNanos
        audioQueueWaitMaxNanos = maxOf(audioQueueWaitMaxNanos, queueWaitNanos)
        audioBuildTotalNanos += buildNanos
        audioBuildMaxNanos = maxOf(audioBuildMaxNanos, buildNanos)
        audioTransportTotalNanos += transportNanos
        audioTransportMaxNanos = maxOf(audioTransportMaxNanos, transportNanos)
        if (audioTimingSamples < AUDIO_TIMING_LOG_INTERVAL) return

        fun avgMs(totalNanos: Long) =
            String.format(java.util.Locale.ROOT, "%.2f", totalNanos / audioTimingSamples / 1_000_000.0)
        fun maxMs(valueNanos: Long) =
            String.format(java.util.Locale.ROOT, "%.2f", valueNanos / 1_000_000.0)

        AppLog.log(
            TAG,
            "audio sender timing: frames=$audioTimingSamples, sent=$audioTimingSent, " +
                "failed=$audioTimingFailed, queueWaitAvgMs=${avgMs(audioQueueWaitTotalNanos)}, " +
                "queueWaitMaxMs=${maxMs(audioQueueWaitMaxNanos)}, " +
                "frameBuildAvgMs=${avgMs(audioBuildTotalNanos)}, " +
                "frameBuildMaxMs=${maxMs(audioBuildMaxNanos)}, " +
                "transportSendAvgMs=${avgMs(audioTransportTotalNanos)}, " +
                "transportSendMaxMs=${maxMs(audioTransportMaxNanos)}, " +
                "pendingAudio=${outboundQueue.pendingAudioFrames()}, " +
                "pendingMessages=${outboundQueue.size()}, " +
                "droppedAudioTotal=${outboundQueue.droppedAudioFrames}",
        )
        resetAudioTimingWindow()
    }

    private fun stopSender() {
        senderJob?.cancel(); senderJob = null
        drainOutbound()
    }

    private fun enqueuePlain(type: Byte, payload: ByteArray) {
        if (!enqueueOutbound(OutboundMsg.Plain(type, payload))) {
            Log.w(TAG, "outbound queue full; dropping plain frame type=$type")
        }
    }

    /** Takes ownership of the input buffer, which is cleared after copying. */
    private fun enqueueControl(type: Byte, plaintext: ByteArray) {
        val k = keys
        if (k == null) {
            plaintext.fill(0)
            return
        }
        val msg = OutboundMsg.Sealed(
            type = type,
            codecId = 0xFF.toByte(),
            sessionId = k.sessionId.copyOf(),
            key = k.controlKey,
            role = myRole,
            plaintext = plaintext.copyOf(),
        )
        plaintext.fill(0)
        if (!enqueueOutbound(msg)) {
            Log.w(TAG, "outbound queue full; dropping control frame type=$type")
        }
    }

    /**
     * Freshness-first audio queue: on congestion, evict old unsent speech rather
     * than dropping every new frame and playing stale audio when the link recovers.
     */
    /** Queue an isolated copy; AudioEngine clears its capture-owned buffer after this callback. */
    private fun enqueueAudio(codecId: Byte, plaintext: ByteArray) {
        val k = keys ?: return
        val msg = OutboundMsg.Sealed(
            type = MessageType.AUDIO,
            codecId = codecId,
            sessionId = k.sessionId.copyOf(),
            key = k.audioKey,
            role = myRole,
            plaintext = plaintext.copyOf(),
        )
        if (!enqueueOutbound(msg)) {
            Log.w(TAG, "realtime audio queue rejected a frame (pending=" + outboundQueue.size() + ")")
            return
        }
        val dropped = outboundQueue.droppedAudioFrames
        if (dropped >= lastLoggedAudioDrops + AUDIO_DROP_LOG_INTERVAL) {
            synchronized(this) {
                if (dropped >= lastLoggedAudioDrops + AUDIO_DROP_LOG_INTERVAL) {
                    lastLoggedAudioDrops = dropped
                    AppLog.log(
                        TAG,
                        "realtime audio freshness drops=$dropped, pendingAudio=${outboundQueue.pendingAudioFrames()}, " +
                            "pendingMessages=${outboundQueue.size()}",
                    )
                }
            }
        }
    }

    init {
        audio.pipelineListener = this
    }

    // ------------------------------------------------------- public control

    /** Outgoing session: connect the transport, then run the handshake. */
    fun startOutgoing(t: Transport, peer: PeerDevice, codecPref: Byte = CodecId.OPUS) {
        if (!claimSessionStart("startOutgoing")) return
        AppLog.log(TAG, "outgoing session start via ${t.type}")
        myCodecPref = codecPref
        attachTransport(t, peer.displayName)
        // The transport may already report CONNECTED (peer connected first);
        // the state-flow collector below only fires on *changes*.
        if (t.state.value == TransportState.CONNECTED) beginHandshake()
        scope.launch {
            try {
                t.connect(peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SessionError.TRANSPORT_LOST)
            }
        }
    }

    /**
     * Starts an incoming listener. [onListeningReady] is invoked after the
     * transport has actually created its listening endpoint, not merely after
     * the UI tap. Bluetooth uses this to request system discoverability only
     * after its RFCOMM server socket is ready to accept connections.
     */
    fun acceptIncoming(
        t: Transport,
        codecPref: Byte = CodecId.OPUS,
        onListeningReady: (() -> Unit)? = null,
    ) {
        if (!claimSessionStart("acceptIncoming")) return
        AppLog.log(TAG, "listening for incoming via ${t.type}")
        myCodecPref = codecPref
        attachTransport(t, "")
        val listenerGeneration = sessionGeneration
        // The transport may already report CONNECTED (peer connected first);
        // the state-flow collector below only fires on *changes*.
        if (t.state.value == TransportState.CONNECTED) beginHandshake()
        scope.launch {
            try {
                t.listen()
                // A canceled/failed old listener must not launch a late
                // discoverability prompt over a newer or already-ended session.
                if (onListeningReady != null &&
                    sessionGeneration == listenerGeneration &&
                    transport === t &&
                    _phase.value != Phase.IDLE &&
                    _phase.value != Phase.ENDED &&
                    t.state.value == TransportState.IDLE
                ) {
                    onListeningReady()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SessionError.TRANSPORT_LOST)
            }
        }
    }

    /** Atomically claims an idle manager so simultaneous Connect/Listen taps cannot race. */
    private fun claimSessionStart(operation: String): Boolean = synchronized(lifecycleLock) {
        if (_phase.value != Phase.IDLE) {
            AppLog.log(TAG, "$operation ignored: phase=${_phase.value}")
            false
        } else {
            _phase.value = Phase.CONNECTING_TRANSPORT
            true
        }
    }

    /** Transition to ENDED once per active attempt; repeated failures must not queue stale teardowns. */
    private fun markEndedIfActive(): Boolean = synchronized(lifecycleLock) {
        if (_phase.value == Phase.IDLE || _phase.value == Phase.ENDED) {
            false
        } else {
            _phase.value = Phase.ENDED
            true
        }
    }

    private fun attachTransport(t: Transport, peerName: String) {
        detachTransport()
        sessionGeneration++
        attemptStartedAtMs = elapsedClock()
        transportConnectedAtMs = null
        sessionEstablishedAtMs = null
        startSender()
        transport = t
        _peerName.value = peerName
        _transportType.value = t.type
        _error.value = null
        transportWasConnected = false
        AppLog.log(TAG, "session attempt=$sessionGeneration started (transport=${t.type})")
        // SharedFlow(extraBufferCapacity) does NOT retain emissions when there are
        // zero subscribers. Subscribe synchronously before the state watcher can
        // begin the handshake and before connect()/listen() can produce frames.
        collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.incomingFrames.collect { bytes -> onRawFrame(bytes) }
        }
        transportWatchJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.state.collect { st ->
                _transportState.value = st
                when (st) {
                    TransportState.CONNECTED -> {
                        if (!transportWasConnected) {
                            transportWasConnected = true
                            val connectedAt = elapsedClock()
                            transportConnectedAtMs = connectedAt
                            val setupMs = attemptStartedAtMs
                                ?.let { (connectedAt - it).coerceAtLeast(0L) } ?: -1L
                            AppLog.log(
                                TAG,
                                "session transport connected: attempt=$sessionGeneration, " +
                                    "transport=${t.type}, elapsedMs=$setupMs",
                            )
                        }
                        if (_phase.value == Phase.CONNECTING_TRANSPORT) beginHandshake()
                    }
                    TransportState.FAILED -> {
                        if (_phase.value != Phase.IDLE && _phase.value != Phase.ENDED) {
                            fail(transportErrorToSession(t.error.value))
                        }
                    }
                    TransportState.IDLE -> {
                        // Only a *loss* ends the session: IDLE before the
                        // first CONNECTED is just the transport's rest state.
                        if (transportWasConnected &&
                            _phase.value != Phase.IDLE && _phase.value != Phase.ENDED
                        ) {
                            endSession(SessionError.TRANSPORT_LOST, notifyPeer = false)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun detachTransport() {
        stopSender()
        collectJob?.cancel(); collectJob = null
        transportWatchJob?.cancel(); transportWatchJob = null
        transport = null
    }

    /** User compared the SAS codes shown on both devices. */
    fun confirmSas(match: Boolean) {
        if (_phase.value != Phase.AWAITING_SAS_CONFIRM) return
        if (!match) {
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        selfSasConfirmed = true
        enqueueControl(MessageType.SAS_CONFIRM, ByteArray(0))
        maybeEnterSession()
    }

    /** Push-to-talk button state. */
    fun setPttPressed(pressed: Boolean) {
        pttHeld = pressed
        if (_phase.value != Phase.IN_SESSION) return
        if (pressed) {
            if (_isTransmitting.value || floor.selfRequestPending) return
            if (floor.holder == FloorController.Holder.PEER) {
                _error.value = SessionError.PEER_BUSY
                return
            }
            if (floor.requestFloor()) {
                enqueueControl(MessageType.FLOOR_REQUEST, ByteArray(0))
            }
        } else {
            floor.cancelRequest()
            if (_isTransmitting.value) {
                stopTransmit()
                floor.releaseSelf() // SelfReleased event also stops; idempotent
                enqueueControl(MessageType.FLOOR_RELEASE, ByteArray(0))
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** Guards teardown: one ordered teardown at a time; the body is idempotent. */
    private val teardownMutex = Mutex()

    /** Ends the session, wipes keys, stops audio, disconnects transport. */
    fun endSession(
        error: SessionError? = null,
        notifyPeer: Boolean = true,
        disconnectReason: Byte = DisconnectReason.USER_HANGUP,
    ) {
        val phaseBeforeEnd = _phase.value
        if (!markEndedIfActive()) return
        val activeTransport = transport
        val endedAt = elapsedClock()
        val elapsedSinceAttemptMs = attemptStartedAtMs
            ?.let { (endedAt - it).coerceAtLeast(0L) } ?: -1L
        val activeSessionMs = sessionEstablishedAtMs
            ?.let { (endedAt - it).coerceAtLeast(0L) } ?: -1L
        AppLog.log(
            TAG,
            "session ending: attempt=$sessionGeneration, " +
                "reason=${error?.name ?: "USER_HANGUP"}, elapsedSinceAttemptMs=$elapsedSinceAttemptMs, " +
                "activeSessionMs=$activeSessionMs, previousPhase=$phaseBeforeEnd, " +
                "transport=${activeTransport?.type}, transportState=${activeTransport?.state?.value}, " +
                "notifyPeer=$notifyPeer",
        )
        // Enqueue DISCONNECT while the sender is still running; the flush
        // delay below gives it a bounded window to go out.
        if (notifyPeer && keys != null) {
            enqueueControl(MessageType.DISCONNECT, byteArrayOf(disconnectReason))
        }
        scope.launch {
            delay(150) // best-effort flush before socket close
            teardown(error)
        }
    }

    /**
     * One explicit, idempotent, ordered teardown (HS-03). Safe to call
     * twice, concurrently, after partial init, mid-handshake, or after
     * transport loss. Key wiping is best-effort minimization of secret
     * lifetime in memory — the JVM/GC may retain copies elsewhere.
     */
    private suspend fun teardown(error: SessionError?) = teardownMutex.withLock {
        cancelDeadlines()
        stopTransmit()
        stopSender() // before key wipe: queued messages hold key references
        try { audio.stopPlayback() } catch (_: Exception) {}
        livenessJob?.cancel(); livenessJob = null
        floor.reset()
        jitter.reset()
        replay.reset()
        receivedAudioSeq = 0
        keys?.wipe(); keys = null
        codec?.close(); codec = null
        myKeyPair = null
        myNonce.fill(0); myNonce = ByteArray(0)
        myPubBytes.fill(0); myPubBytes = ByteArray(0)
        peerPubBytes?.fill(0); peerPubBytes = null
        peerNonce?.fill(0); peerNonce = null
        keyExchangeSent = false; keyConfirmSent = false
        peerHelloReceived = false
        peerCodecPref = null
        pendingKeyConfirmFrame = null
        handshakeStarted = false
        selfSasConfirmed = false
        _sasCode.value = null
        _peerSasConfirmed.value = false
        _isTransmitting.value = false
        _floorHolder.value = FloorController.Holder.NONE
        _voiceLevel.value = 0f
        _error.value = error
        try {
            transport?.disconnect()
        } catch (_: Exception) {}
        detachTransport()
        _transportType.value = null
        AppLog.log(TAG, "session teardown complete: reason=${error?.name ?: "USER_HANGUP"}")
        synchronized(lifecycleLock) { _phase.value = Phase.IDLE }
    }

    /**
     * Fast synchronous path for Service.onDestroy (HS-03): stops audio and
     * wipes keys immediately without depending on the service scope (which
     * onDestroy is about to cancel). Transport close runs best-effort.
     */
    fun close() {
        synchronized(lifecycleLock) { _phase.value = Phase.ENDED }
        cancelDeadlines()
        stopSender()
        try { audio.stopCapture() } catch (_: Exception) {}
        try { audio.stopPlayback() } catch (_: Exception) {}
        keys?.wipe(); keys = null
        codec?.close(); codec = null
        jitter.reset()
        replay.reset()
        myKeyPair = null
        myNonce.fill(0); myNonce = ByteArray(0)
        myPubBytes.fill(0); myPubBytes = ByteArray(0)
        peerPubBytes?.fill(0); peerPubBytes = null
        peerNonce?.fill(0); peerNonce = null
        peerCodecPref = null
        pendingKeyConfirmFrame = null
        _sasCode.value = null
        _peerSasConfirmed.value = false
        _isTransmitting.value = false
        _floorHolder.value = FloorController.Holder.NONE
        _voiceLevel.value = 0f

        // This must not use the service's child scope: VoiceService.onDestroy()
        // cancels that scope immediately after close() returns. Serialize final
        // transport cleanup with an in-flight teardown and keep it non-cancellable.
        cleanupScope.launch {
            withContext(NonCancellable) {
                teardownMutex.withLock {
                    try { transport?.disconnect() } catch (_: Exception) {}
                    detachTransport()
                    _transportType.value = null
                    synchronized(lifecycleLock) { _phase.value = Phase.IDLE }
                }
            }
            cleanupJob.cancel()
        }
    }

    // ------------------------------------------------------- handshake

    private fun beginHandshake() = synchronized(handshakeFrameLock) {
        // A CONNECTED state callback and the first inbound HELLO can race on
        // different dispatcher threads. Keep startup atomic with inbound frame
        // processing so KEY_EXCHANGE cannot overtake this side's own HELLO.
        if (handshakeStarted) return
        handshakeStarted = true
        peerHelloReceived = false
        peerCodecPref = null
        pendingKeyConfirmFrame = null
        _phase.value = Phase.HANDSHAKE
        startHandshakeDeadline()
        myKeyPair = SessionCrypto.generateEphemeralKeyPair()
        myPubBytes = SessionCrypto.encodePublicKey(myKeyPair!!.public)
        myNonce = SessionCrypto.newNonce16()
        sendSequence.reset()
        sequenceExhaustionTriggered.set(false)
        lastPeerSeen = elapsedClock()
        // Queue our protocol preamble before another thread can process the
        // peer HELLO and enqueue KEY_EXCHANGE. The sender serializes this FIFO.
        val hello = byteArrayOf(Protocol.VERSION, myCodecPref)
        enqueuePlain(MessageType.HELLO, hello)
        Log.i(TAG, "handshake started, HELLO sent")
        val handshakeElapsedMs = attemptStartedAtMs
            ?.let { (elapsedClock() - it).coerceAtLeast(0L) } ?: -1L
        AppLog.log(
            TAG,
            "handshake started: attempt=$sessionGeneration, elapsedSinceAttemptMs=$handshakeElapsedMs, " +
                "HELLO sent (localProtocolVersion=${Protocol.VERSION.toInt() and 0xFF}, " +
                "codecPreference=${myCodecPref.toInt() and 0xFF})",
        )
    }

    /**
     * Handshake deadline (HS-07): a peer that disappears mid-handshake
     * causes bounded teardown, not an indefinite spinner.
     */
    private fun startHandshakeDeadline() {
        handshakeDeadlineJob?.cancel()
        val gen = sessionGeneration
        handshakeDeadlineJob = scope.launch {
            delay(Protocol.HANDSHAKE_TIMEOUT_MS)
            if (sessionGeneration == gen && _phase.value == Phase.HANDSHAKE) {
                Log.w(TAG, "handshake timed out")
                AppLog.log(TAG, "handshake timed out")
                endSession(SessionError.PEER_TIMEOUT, notifyPeer = false)
            }
        }
    }

    /**
     * SAS confirmation deadline (HS-07): fails closed if the peer never
     * confirms. Both sides must explicitly confirm before audio flows.
     */
    private fun startSasDeadline() {
        sasDeadlineJob?.cancel()
        val gen = sessionGeneration
        sasDeadlineJob = scope.launch {
            delay(Protocol.SAS_CONFIRM_TIMEOUT_MS)
            if (sessionGeneration == gen && _phase.value == Phase.AWAITING_SAS_CONFIRM) {
                Log.w(TAG, "SAS confirmation timed out")
                AppLog.log(TAG, "SAS confirmation timed out")
                endSession(
                    SessionError.PEER_TIMEOUT,
                    notifyPeer = true,
                    disconnectReason = DisconnectReason.PEER_TIMEOUT,
                )
            }
        }
    }

    private fun cancelDeadlines() {
        handshakeDeadlineJob?.cancel(); handshakeDeadlineJob = null
        sasDeadlineJob?.cancel(); sasDeadlineJob = null
    }

    private fun onRawFrame(bytes: ByteArray) = synchronized(handshakeFrameLock) {
        // An inbound frame proves that the transport's data path is already
        // usable, even if its CONNECTED state update is still queued behind the
        // socket-reader callback. Starting the handshake only when state ==
        // CONNECTED drops a valid early HELLO; the peer then receives our
        // KEY_EXCHANGE and rejects it as "before HELLO". Keep this idempotent
        // and start on any first frame while the session still owns the
        // CONNECTING_TRANSPORT phase. The frame is still validated below.
        if (_phase.value == Phase.CONNECTING_TRANSPORT) {
            val observedState = transport?.state?.value
            AppLog.log(
                TAG,
                "starting handshake on first inbound frame before transport-state observer " +
                    "(transportState=$observedState)",
            )
            beginHandshake()
        }

        val frame = try {
            Frame.decode(bytes)
        } catch (e: FrameException) {
            Log.w(TAG, "dropping malformed frame: ${e.message}")
            AppLog.log(
                TAG,
                "inbound frame rejected (phase=${_phase.value}, bytes=${bytes.size}, reason=${e.message})",
            )
            if (_phase.value == Phase.IN_SESSION) {
                _authFailures.value += 1
            } else if (_phase.value == Phase.HANDSHAKE ||
                _phase.value == Phase.AWAITING_SAS_CONFIRM
            ) {
                fail(SessionError.PROTOCOL_ERROR, "malformed handshake frame: ${e.message}")
            }
            return
        }
        when (_phase.value) {
            Phase.HANDSHAKE -> handleHandshakeFrame(frame)
            Phase.AWAITING_SAS_CONFIRM -> handleSasPhaseFrame(frame)
            Phase.IN_SESSION -> handleSessionFrame(frame)
            else -> Unit
        }
    }

    private fun handleHandshakeFrame(frame: Frame) {
        // A valid HELLO is the protocol preamble. TCP preserves send order, so
        // accepting KEY_EXCHANGE first would mean the peer skipped validation
        // of protocol version and codec negotiation.
        if (!peerHelloReceived &&
            frame.type != MessageType.HELLO &&
            frame.type != MessageType.DISCONNECT
        ) {
            fail(
                SessionError.PROTOCOL_ERROR,
                "received frame type=${frame.type.toInt() and 0xFF} before HELLO",
            )
            return
        }
        when (frame.type) {
            MessageType.HELLO -> {
                if (peerHelloReceived) {
                    fail(SessionError.PROTOCOL_ERROR, "duplicate HELLO")
                    return
                }
                val peerVersion = frame.payload.getOrNull(0)?.toInt()?.and(0xFF)
                val peerPrefValue = frame.payload.getOrNull(1)?.toInt()?.and(0xFF)
                if (frame.payload.size != 2 || frame.payload[0] != Protocol.VERSION) {
                    fail(
                        SessionError.PROTOCOL_ERROR,
                        "invalid HELLO (payloadBytes=${frame.payload.size}, peerVersion=$peerVersion, " +
                            "expectedVersion=${Protocol.VERSION.toInt() and 0xFF})",
                    )
                    return
                }
                val peerPref = frame.payload[1]
                if (peerPref != CodecId.OPUS && peerPref != CodecId.PCM16) {
                    fail(
                        SessionError.PROTOCOL_ERROR,
                        "unsupported codec preference=$peerPrefValue in HELLO",
                    )
                    return
                }
                peerCodecPref = peerPref
                peerHelloReceived = true
                AppLog.log(
                    TAG,
                    "received compatible HELLO (protocolVersion=$peerVersion, codecPreference=$peerPrefValue)",
                )
                activeCodecId = minOf(myCodecPref, peerPref)
                if (!keyExchangeSent) sendKeyExchange()
            }
            MessageType.KEY_EXCHANGE -> {
                if (peerPubBytes != null) return // duplicate; ignore
                try {
                    onKeyExchange(frame.payload)
                    if (!keyExchangeSent) sendKeyExchange()
                    deriveAndConfirm()
                    AppLog.log(TAG, "KEY_EXCHANGE accepted; key derivation completed")
                } catch (e: Exception) {
                    Log.w(TAG, "bad KEY_EXCHANGE or key derivation failed", e)
                    fail(
                        SessionError.PROTOCOL_ERROR,
                        "KEY_EXCHANGE processing failed (payloadBytes=${frame.payload.size}, " +
                            "cause=${e.javaClass.simpleName}: ${e.message})",
                    )
                    return
                }
            }
            MessageType.KEY_CONFIRM -> {
                // May arrive before we finished deriving (both sides race).
                if (keys == null) {
                    pendingKeyConfirmFrame = frame
                } else {
                    verifyPeerKeyConfirm(frame)
                }
            }
            MessageType.DISCONNECT -> {
                // Once keys exist, DISCONNECT must authenticate like every
                // other control frame. Before derivation, accept only the
                // protocol's exact plaintext shape.
                val activeKeys = keys
                val payload = if (activeKeys != null) {
                    try {
                        openControl(frame, activeKeys)
                    } catch (e: Exception) {
                        _authFailures.value += 1
                        AppLog.log(
                            TAG,
                            "unauthenticated handshake DISCONNECT rejected (${e.javaClass.simpleName})",
                        )
                        return
                    }
                } else {
                    if (frame.codecId != 0xFF.toByte() ||
                        frame.sessionId.any { it != 0.toByte() }
                    ) {
                        fail(SessionError.PROTOCOL_ERROR, "invalid plaintext DISCONNECT during handshake")
                        return
                    }
                    frame.payload
                }
                val reason = decodeDisconnectReason(payload)
                if (activeKeys != null) payload.fill(0)
                if (reason == null) {
                    fail(SessionError.PROTOCOL_ERROR, "invalid DISCONNECT reason during handshake")
                    return
                }
                endSession(sessionErrorForDisconnect(reason), notifyPeer = false)
            }
            else -> {
                Log.w(TAG, "unexpected ${frame.type} during handshake")
                fail(
                    SessionError.PROTOCOL_ERROR,
                    "unexpected handshake frame (type=${frame.type.toInt() and 0xFF}, " +
                        "payloadBytes=${frame.payload.size})",
                )
            }
        }
    }

    private fun sendKeyExchange() {
        val pub = myPubBytes
        val buf = ByteBuffer.allocate(2 + pub.size + 16).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(pub.size.toShort())
        buf.put(pub)
        buf.put(myNonce)
        keyExchangeSent = true
        enqueuePlain(MessageType.KEY_EXCHANGE, buf.array())
    }

    private fun onKeyExchange(payload: ByteArray) {
        require(payload.size >= 2 + 16) { "KEY_EXCHANGE too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val pubLen = buf.short.toInt() and 0xFFFF
        require(pubLen in 1..SessionCrypto.MAX_PUBKEY_BYTES) { "pubkey len $pubLen" }
        require(payload.size == 2 + pubLen + 16) { "KEY_EXCHANGE size mismatch" }
        val pub = ByteArray(pubLen).also { buf.get(it) }
        val nonce = ByteArray(16).also { buf.get(it) }
        // Validates curve/encoding now — throws on garbage.
        SessionCrypto.decodePublicKey(pub)
        peerPubBytes = pub
        peerNonce = nonce
    }

    private fun deriveAndConfirm() {
        val peerPub = peerPubBytes ?: return
        val peerNc = peerNonce ?: return
        val kp = myKeyPair ?: return
        val secret = SessionCrypto.agreeSharedSecret(kp, SessionCrypto.decodePublicKey(peerPub))
        myKeyPair = null // private key is no longer needed after ECDH
        try {
            val derived = SessionCrypto.deriveKeys(secret, myNonce, peerNc)
            keys = derived
            myRole = SessionCrypto.myRole(myPubBytes, peerPub)
            val transcript = currentTranscriptHash(peerPub, peerNc)
            if (!keyConfirmSent) {
                keyConfirmSent = true
                enqueueControl(MessageType.KEY_CONFIRM, transcript)
            } else {
                transcript.fill(0)
            }
            pendingKeyConfirmFrame?.let { stashed ->
                pendingKeyConfirmFrame = null
                verifyPeerKeyConfirm(stashed)
            }
        } finally {
            secret.fill(0)
        }
    }

    // KEY_CONFIRM frames that arrive before we derived are stashed here.
    private var pendingKeyConfirmFrame: Frame? = null

    private fun expectedTranscript(): ByteArray =
        currentTranscriptHash(
            peerPubBytes ?: error("peer public key absent"),
            peerNonce ?: error("peer nonce absent"),
        )

    private fun currentTranscriptHash(peerPub: ByteArray, peerNc: ByteArray): ByteArray {
        val peerPref = peerCodecPref ?: error("peer HELLO codec preference absent")
        val ownHello = byteArrayOf(Protocol.VERSION, myCodecPref)
        val peerHello = byteArrayOf(Protocol.VERSION, peerPref)
        return try {
            SessionCrypto.transcriptHash(
                myPubBytes, peerPub, myNonce, peerNc, ownHello, peerHello,
            )
        } finally {
            ownHello.fill(0)
            peerHello.fill(0)
        }
    }

    private fun verifyPeerKeyConfirm(frame: Frame) {
        val k = keys ?: return
        val opened = try {
            openControl(frame, k)
        } catch (e: Exception) {
            Log.w(TAG, "KEY_CONFIRM failed authentication (" + e.javaClass.simpleName + ")")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        val expected = expectedTranscript()
        try {
            verifyKeyConfirm(opened, expected)
        } finally {
            opened.fill(0)
            expected.fill(0)
        }
    }

    private fun handleSasPhaseFrame(frame: Frame) {
        val k = keys ?: return
        when (frame.type) {
            MessageType.KEY_CONFIRM -> {
                // Late/duplicate confirm — verify again, harmless.
                verifyPeerKeyConfirm(frame)
            }
            MessageType.SAS_CONFIRM -> {
                val plaintext = try {
                    openControl(frame, k)
                } catch (e: Exception) {
                    _authFailures.value += 1
                    AppLog.log(TAG, "unauthenticated SAS_CONFIRM rejected (" + e.javaClass.simpleName + ")")
                    return
                }
                try {
                    if (plaintext.isNotEmpty()) {
                        fail(SessionError.PROTOCOL_ERROR, "SAS_CONFIRM payload must be empty")
                        return
                    }
                    _peerSasConfirmed.value = true
                    lastPeerSeen = elapsedClock()
                    maybeEnterSession()
                } finally {
                    plaintext.fill(0)
                }
            }
            MessageType.DISCONNECT -> {
                val plaintext = try {
                    openControl(frame, k)
                } catch (e: Exception) {
                    _authFailures.value += 1
                    AppLog.log(
                        TAG,
                        "unauthenticated SAS-phase DISCONNECT rejected (" + e.javaClass.simpleName + ")",
                    )
                    return
                }
                try {
                    val reason = decodeDisconnectReason(plaintext)
                    if (reason == null) {
                        fail(SessionError.PROTOCOL_ERROR, "invalid authenticated DISCONNECT payload")
                        return
                    }
                    endSession(sessionErrorForDisconnect(reason), notifyPeer = false)
                } finally {
                    plaintext.fill(0)
                }
            }
            else -> fail(
                SessionError.PROTOCOL_ERROR,
                "unexpected SAS-phase frame (type=${frame.type.toInt() and 0xFF}, " +
                    "payloadBytes=${frame.payload.size})",
            )
        }
    }

    private fun verifyKeyConfirmPayload(payload: ByteArray) {
        val expected = expectedTranscript()
        val matches = try {
            MessageDigest.isEqual(payload, expected)
        } finally {
            expected.fill(0)
        }
        if (!matches) {
            Log.w(TAG, "KEY_CONFIRM transcript mismatch — possible MITM")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        if (_phase.value == Phase.HANDSHAKE) {
            _phase.value = Phase.AWAITING_SAS_CONFIRM
            _sasCode.value = SessionCrypto.shortAuthString(keys!!.sasSeed)
            // SAS confirmation deadline (HS-07): the peer must confirm.
            startSasDeadline()
            Log.i(TAG, "key exchange verified; awaiting SAS comparison")
        }
    }

    private fun verifyKeyConfirm(payload: ByteArray, expectedTranscript: ByteArray) {
        if (!MessageDigest.isEqual(payload, expectedTranscript)) {
            Log.w(TAG, "KEY_CONFIRM transcript mismatch — possible MITM")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        if (_phase.value == Phase.HANDSHAKE) {
            _phase.value = Phase.AWAITING_SAS_CONFIRM
            _sasCode.value = SessionCrypto.shortAuthString(keys!!.sasSeed)
            // SAS confirmation deadline (HS-07): the peer must confirm.
            startSasDeadline()
            Log.i(TAG, "key exchange verified; awaiting SAS comparison")
        }
    }

    private fun maybeEnterSession() {
        if (_phase.value != Phase.AWAITING_SAS_CONFIRM) return
        if (!selfSasConfirmed || !_peerSasConfirmed.value) return
        enterSession()
    }

    private fun enterSession() {
        cancelDeadlines()
        codec = AudioCodec.create(activeCodecId).also { it.reset() }
        jitter.reset()
        replay.reset()
        lastPeerSeen = elapsedClock()
        receivedAudioSeq = 0
        resetAudioReceiveTiming()
        // HS-05: never report voice readiness if playback fails to start.
        // Roll the session back cleanly instead of a fake IN_SESSION.
        val playbackOk = try {
            audio.startPlayback(codec!!, jitter)
        } catch (e: Exception) {
            Log.w(TAG, "playback start threw", e)
            false
        }
        if (!playbackOk) {
            Log.w(TAG, "playback failed to start; ending session")
            AppLog.log(TAG, "playback failed to start")
            endSession(
                SessionError.PLAYBACK_UNAVAILABLE,
                notifyPeer = true,
                disconnectReason = DisconnectReason.PROTOCOL_ERROR,
            )
            return
        }
        _phase.value = Phase.IN_SESSION
        val establishedAt = elapsedClock()
        sessionEstablishedAtMs = establishedAt
        AppLog.log(TAG, "session established (codec=$activeCodecId)")
        val setupMs = attemptStartedAtMs
            ?.let { (establishedAt - it).coerceAtLeast(0L) } ?: -1L
        val connectedToSessionMs = transportConnectedAtMs
            ?.let { (establishedAt - it).coerceAtLeast(0L) } ?: -1L
        AppLog.log(
            TAG,
            "session timing: attempt=$sessionGeneration, setupMs=$setupMs, " +
                "connectedToSessionMs=$connectedToSessionMs, transport=${transportType.value}",
        )
        livenessJob = scope.launch {
            while (isActive && _phase.value == Phase.IN_SESSION) {
                delay(1_000)
                floor.checkTimeouts()
                val now = elapsedClock()
                val peerSilenceMs = now - lastPeerSeen
                if (peerSilenceMs > Protocol.PEER_TIMEOUT_MS) {
                    AppLog.log(
                        TAG,
                        "peer liveness timeout: silenceMs=$peerSilenceMs, " +
                            "thresholdMs=${Protocol.PEER_TIMEOUT_MS}",
                    )
                    endSession(SessionError.PEER_TIMEOUT, notifyPeer = false)
                    return@launch
                }
                if (now - lastPingSent > Protocol.PING_INTERVAL_MS) {
                    lastPingSent = now
                    val ping = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                        .putLong(clock()).array()
                    enqueueControl(MessageType.PING, ping)
                }
            }
        }
        Log.i(TAG, "session established (codec=$activeCodecId)")
    }

    private var lastPingSent: Long = 0

    // ------------------------------------------------------- session frames

    private fun handleSessionFrame(frame: Frame) {
        val k = keys ?: return
        // 1. Session binding (no state mutation).
        if (!MessageDigest.isEqual(frame.sessionId, k.sessionId)) {
            _authFailures.value += 1
            return
        }
        // 2. Authenticate FIRST — before replay, liveness, or floor state.
        //    A structurally valid but unauthenticated frame must not advance
        //    the anti-replay window, refresh liveness, or influence the floor.
        //    This includes frames that will be ignored (e.g. audio from a
        //    non-floor-holder): they are still authenticated.
        val plaintext: ByteArray = try {
            when (frame.type) {
                MessageType.AUDIO ->
                    SessionCrypto.open(k.audioKey, aadForReceive(frame), frame.payload)
                else -> openControl(frame, k)
            }
        } catch (e: Exception) {
            _authFailures.value += 1
            return
        }
        try {
            // Validate authenticated payload shape before consuming replay-window
            // state or refreshing liveness.
            val payloadError = validateSessionPayload(frame, plaintext)
            if (payloadError != null) {
                fail(SessionError.PROTOCOL_ERROR, payloadError)
                return
            }
            if (!replay.accept(frame.seq)) {
                _authFailures.value += 1
                return
            }
            lastPeerSeen = elapsedClock()
            when (frame.type) {
                MessageType.AUDIO -> {
                    if (floor.holder != FloorController.Holder.PEER) return
                    floor.onPeerAudio()
                    recordAudioReceiveTiming()
                    // JitterBuffer owns a copy; this decrypted temporary is cleared below.
                    jitter.push(receivedAudioSeq++, plaintext)
                }
                MessageType.FLOOR_REQUEST -> {
                    val granted = floor.onPeerRequest()
                    _floorHolder.value = floor.holder
                    val t = if (granted) MessageType.FLOOR_GRANT else MessageType.FLOOR_DENY
                    val response = if (granted) {
                        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(FloorController.MAX_SELF_TX_MS.toInt()).array()
                    } else byteArrayOf(DenyReason.PEER_BUSY)
                    enqueueControl(t, response)
                }
                MessageType.FLOOR_GRANT -> {
                    val lease = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN).int.toLong()
                    floor.onGrant(lease)
                }
                MessageType.FLOOR_RELEASE -> {
                    floor.onPeerRelease()
                    _floorHolder.value = floor.holder
                }
                MessageType.FLOOR_DENY -> floor.onDeny(plaintext[0])
                MessageType.PING -> enqueueControl(MessageType.PONG, plaintext)
                MessageType.PONG -> Unit
                MessageType.SAS_CONFIRM -> _peerSasConfirmed.value = true
                MessageType.DISCONNECT -> {
                    endSession(sessionErrorForDisconnect(plaintext[0]), notifyPeer = false)
                }
                MessageType.ERROR -> Log.w(TAG, "peer error frame")
                MessageType.KEY_CONFIRM -> verifyKeyConfirmPayload(plaintext)
                else -> Log.w(TAG, "unexpected " + frame.type + " in session")
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decodeDisconnectReason(payload: ByteArray): Byte? {
        if (payload.size != 1) return null
        val reason = payload[0]
        return if (
            reason == DisconnectReason.USER_HANGUP ||
            reason == DisconnectReason.AUTH_MISMATCH ||
            reason == DisconnectReason.PROTOCOL_ERROR ||
            reason == DisconnectReason.TRANSPORT_LOST ||
            reason == DisconnectReason.PEER_TIMEOUT
        ) reason else null
    }

    private fun sessionErrorForDisconnect(reason: Byte): SessionError? = when (reason) {
        DisconnectReason.USER_HANGUP -> null
        DisconnectReason.AUTH_MISMATCH -> SessionError.AUTH_MISMATCH
        DisconnectReason.PROTOCOL_ERROR -> SessionError.PROTOCOL_ERROR
        DisconnectReason.TRANSPORT_LOST -> SessionError.TRANSPORT_LOST
        DisconnectReason.PEER_TIMEOUT -> SessionError.PEER_TIMEOUT
        else -> SessionError.PEER_REJECTED
    }

    /** Returns a protocol error for an authenticated but malformed session payload. */
    private fun validateSessionPayload(frame: Frame, plaintext: ByteArray): String? {
        if (frame.type != MessageType.AUDIO && frame.codecId != 0xFF.toByte()) {
            return "non-audio frame used a codec id"
        }
        return when (frame.type) {
            MessageType.AUDIO -> when {
                frame.codecId != activeCodecId -> "audio codec does not match negotiated codec"
                plaintext.isEmpty() -> "empty audio payload"
                activeCodecId == CodecId.PCM16 && plaintext.size != AudioCodec.FRAME_BYTES_PCM ->
                    "invalid PCM frame length"
                activeCodecId == CodecId.OPUS && plaintext.size > OpusCodec.MAX_PACKET_BYTES ->
                    "Opus packet exceeds the local encoder's maximum"
                else -> null
            }
            MessageType.FLOOR_REQUEST, MessageType.FLOOR_RELEASE ->
                if (plaintext.isEmpty()) null else "unexpected floor-control payload"
            MessageType.FLOOR_GRANT -> {
                if (plaintext.size != 4) {
                    "invalid FLOOR_GRANT length"
                } else {
                    val lease = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN).int.toLong()
                    if (lease in 1L..FloorController.MAX_SELF_TX_MS) null
                    else "invalid FLOOR_GRANT lease"
                }
            }
            MessageType.FLOOR_DENY -> {
                if (plaintext.size != 1) {
                    "invalid FLOOR_DENY length"
                } else if (plaintext[0] in setOf(
                        DenyReason.PEER_BUSY,
                        DenyReason.NOT_AUTHENTICATED,
                        DenyReason.SESSION_ENDED,
                    )
                ) null else "unknown FLOOR_DENY reason"
            }
            MessageType.PING, MessageType.PONG ->
                if (plaintext.size == 8) null else "invalid liveness payload length"
            MessageType.SAS_CONFIRM ->
                if (plaintext.isEmpty()) null else "invalid SAS_CONFIRM payload"
            MessageType.KEY_CONFIRM ->
                if (plaintext.size == 32) null else "invalid KEY_CONFIRM payload length"
            MessageType.DISCONNECT -> {
                if (plaintext.size != 1) {
                    "invalid DISCONNECT length"
                } else if (plaintext[0] in setOf(
                        DisconnectReason.USER_HANGUP,
                        DisconnectReason.AUTH_MISMATCH,
                        DisconnectReason.PROTOCOL_ERROR,
                        DisconnectReason.TRANSPORT_LOST,
                        DisconnectReason.PEER_TIMEOUT,
                    )
                ) null else "unknown DISCONNECT reason"
            }
            // ERROR is reserved by v1 and intentionally carries an opaque payload.
            else -> null
        }
    }

    // ------------------------------------------------------- floor events

    override fun onFloorEvent(event: FloorController.Event) {
        when (event) {
            is FloorController.Event.GrantedToSelf -> {
                _floorHolder.value = FloorController.Holder.SELF
                startTransmit()
            }
            FloorController.Event.SelfReleased -> {
                _floorHolder.value = FloorController.Holder.NONE
                val wasTx = _isTransmitting.value
                stopTransmit()
                if (wasTx && _phase.value == Phase.IN_SESSION) {
                    enqueueControl(MessageType.FLOOR_RELEASE, ByteArray(0))
                }
            }
            is FloorController.Event.Denied -> {
                _error.value = SessionError.PEER_BUSY
            }
            FloorController.Event.PeerTookFloor -> {
                _floorHolder.value = FloorController.Holder.PEER
            }
            FloorController.Event.PeerReleasedFloor -> {
                _floorHolder.value = FloorController.Holder.NONE
            }
        }
    }

    // ------------------------------------------------------- audio paths

    private fun startTransmit() {
        val c = codec ?: return
        _isTransmitting.value = true
        // HS-02: the capture callback only ENQUEUES; the single outbound
        // sender allocates sequences and writes. No per-frame coroutines.
        val ok = audio.startCapture(c) { encoded, level ->
            val now = elapsedClock()
            if (now - lastLevelPush > 120) {
                lastLevelPush = now
                _voiceLevel.value = level
            }
            if (_phase.value == Phase.IN_SESSION && _isTransmitting.value) {
                enqueueAudio(c.codecId, encoded)
            }
        }
        if (!ok) {
            _isTransmitting.value = false
            floor.releaseSelf()
            _error.value = SessionError.MIC_UNAVAILABLE
        }
    }

    private fun stopTransmit() {
        if (_isTransmitting.value) {
            _isTransmitting.value = false
            _voiceLevel.value = 0f
        }
        try {
            audio.stopCapture()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------- frame builders
    //
    // Called ONLY from the single outbound sender coroutine, so sequence
    // allocation is deterministic and race-free. Do not wrap uint32 sequence
    // numbers within one session: replay-window serial arithmetic and a random
    // AEAD nonce do not make a sequence rollover safe at the protocol layer.
    // End the session at a guarded limit and establish a fresh key/session.
    private fun nextSeq(): Long = sendSequence.next()

    private fun buildPlainFrame(type: Byte, payload: ByteArray): ByteArray {
        return Frame(
            type = type,
            sessionId = ByteArray(8),
            seq = nextSeq(),
            timestamp = clock(),
            codecId = 0xFF.toByte(),
            payload = payload,
        ).encode()
    }

    private fun buildSealedFrame(msg: OutboundMsg.Sealed): ByteArray {
        // The AAD covers the frame header, which embeds the payload length —
        // so the header must be built with the FINAL sealed length, not zero.
        val sealedLen = SessionCrypto.sealedLength(msg.plaintext.size)
        val frame = Frame(
            type = msg.type,
            sessionId = msg.sessionId,
            seq = nextSeq(),
            timestamp = clock(),
            codecId = msg.codecId,
            payload = ByteArray(sealedLen), // placeholder; swapped for sealed bytes below
        )
        val aad = frame.headerBytes() + msg.role
        val sealed = SessionCrypto.seal(msg.key, aad, msg.plaintext)
        check(sealed.size == sealedLen) { "sealed length mismatch" }
        return frame.copy(payload = sealed).encode()
    }

    private fun aadForReceive(frame: Frame): ByteArray =
        frame.headerBytes() + (1 - myRole).toByte()

    private fun openControl(frame: Frame, k: SessionKeys): ByteArray =
        SessionCrypto.open(k.controlKey, aadForReceive(frame), frame.payload)

    private fun transportErrorToSession(e: TransportError?): SessionError = when (e) {
        is TransportError.PermissionDenied -> SessionError.TRANSPORT_LOST
        else -> SessionError.TRANSPORT_LOST
    }

    private fun fail(error: SessionError, detail: String? = null) {
        // Mark ENDED synchronously before scheduling cleanup. A burst of bad
        // frames must not queue multiple teardowns that could affect a new session.
        if (!markEndedIfActive()) return
        if (detail.isNullOrBlank()) {
            AppLog.log(TAG, "session failed: attempt=$sessionGeneration, error=$error")
        } else {
            AppLog.log(TAG, "session failed: attempt=$sessionGeneration, error=$error ($detail)")
        }
        scope.launch { teardown(error) }
    }

    // ------------------------------------------------------- pipeline events

    override fun onAudioFocusLost() {
        // A phone call (or similar) stole focus: stop transmitting now.
        if (_isTransmitting.value) {
            setPttPressed(false)
        }
    }

    override fun onCaptureError(message: String) {
        Log.w(TAG, "capture error: $message")
        AppLog.log(TAG, "capture error: $message")
        if (_isTransmitting.value) {
            setPttPressed(false)
            _error.value = SessionError.MIC_UNAVAILABLE
        }
    }

    override fun onPlaybackError(message: String) {
        Log.w(TAG, "playback error: $message")
        AppLog.log(TAG, "playback error: $message")
        if (_phase.value == Phase.IN_SESSION) {
            endSession(SessionError.PLAYBACK_UNAVAILABLE, notifyPeer = false)
        }
    }

    companion object {
        private const val MAX_QUEUED_AUDIO_FRAMES = 6
        private const val MAX_BLUETOOTH_AUDIO_BATCH_FRAMES = 3
        private const val MAX_PENDING_OUTBOUND_MESSAGES = 64
        private const val AUDIO_DROP_LOG_INTERVAL = 50L
        private const val AUDIO_TIMING_LOG_INTERVAL = 50
        private const val AUDIO_RECEIVE_TIMING_LOG_INTERVAL = 50
        private const val TAG = "HamSedaSession"
    }
}
