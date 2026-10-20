package com.hamseda.walkie.session

import android.util.Log
import com.hamseda.walkie.audio.AudioCodec
import com.hamseda.walkie.audio.AudioPipeline
import com.hamseda.walkie.audio.JitterBuffer
import com.hamseda.walkie.audio.OpusCodec
import com.hamseda.walkie.crypto.MemoryPeerIdentityStore
import com.hamseda.walkie.crypto.PeerIdentityStore
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated one-to-one voice session over an attached [Transport].
 *
 * Session establishment (both sides run the same symmetric logic):
 * 1. Each endpoint generates ephemeral P-256 hs.keys, a nonce and a random salt,
 *    then sends HELLO.
 * 2. Each sends KEY_COMMIT over its public key, nonce, HELLO and salt before
 *    either endpoint reveals its key share.
 * 3. After receiving the peer commitment, KEY_EXCHANGE reveals the committed
 *    values. A mismatch aborts as AUTH_MISMATCH before ECDH or SAS display.
 * 4. Valid reveals feed ECDH/HKDF; sealed KEY_CONFIRM authenticates the transcript.
 *    Both endpoints prove local TOFU state inside KEY_CONFIRM. If both pins exist,
 *    SAS is skipped; otherwise both users compare the six-digit SAS before audio.
 * 5. IN_SESSION: half-duplex PTT, AES-256-GCM audio, replay protection, liveness.
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
    /** Shared identity store (single instance with the service's); exposed for UI layers. */
    val peerIdentityStore: PeerIdentityStore = MemoryPeerIdentityStore(),
) : FloorController.Listener, AudioPipeline.PipelineListener {

    enum class Phase {
        IDLE, CONNECTING_TRANSPORT, HANDSHAKE, AWAITING_SAS_CONFIRM, IN_SESSION, ENDED
    }

    enum class SessionError {
        AUTH_MISMATCH, PEER_BUSY, TRANSPORT_LOST, PEER_TIMEOUT,
        PROTOCOL_ERROR, MIC_UNAVAILABLE, PLAYBACK_UNAVAILABLE, PEER_REJECTED,
    }

    /**
     * Sink for authenticated offline-messaging frames
     * (MSG / MSG_ACK / MSG_READ / TYPING / CHUNK / MSG_HELLO / PROFILE /
     * PROFILE_PHOTO). The plaintext is a fresh copy; the session wipes its
     * own buffer afterwards.
     */
    fun interface MessagingFrameListener {
        fun onMessagingFrame(type: Byte, plaintext: ByteArray)
    }

    /** Set by MessagingEngine; null when messaging is not attached. */
    @Volatile
    var messagingListener: MessagingFrameListener? = null

    // ------------------------------------------------------- observable state

    // Lock ordering (global, must be respected everywhere to avoid deadlock):
    //   1. teardownMutex  (outermost; suspending Mutex, serializes teardown)
    //   2. lifecycleLock  (phase transitions; may be taken while holding 1)
    //   3. handshakeFrameLock, sessionEntryLock (leaf locks)
    // The only legal nesting is teardownMutex -> lifecycleLock (see teardown()).
    // In particular, never acquire teardownMutex while holding lifecycleLock.
    //
    // Leaf -> lifecycleLock nesting IS used (enterSession/fail paths call
    // markEndedIfActive while holding a leaf lock). This is safe if and only
    // if code holding lifecycleLock never blocks on a leaf lock: every
    // synchronized(lifecycleLock) section is a brief, non-blocking phase
    // check-and-set (claimSessionStart, markEndedIfActive, direct writes).
    // Keep it that way — a blocking call or a leaf acquisition inside any of
    // them would create a real deadlock cycle.
    private val lifecycleLock = Any()
    /** Guards lastLoggedAudioDrops; never synchronize on `this` (publicly reachable). */
    private val audioDropLogLock = Any()
    /** Prevents simultaneous local/peer SAS callbacks from starting playback twice. */
    private val sessionEntryLock = Any()
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

    private val _peerIdentityTrusted = MutableStateFlow(false)
    /** True only when this session's peer identity matches a persisted pin. */
    val peerIdentityTrusted: StateFlow<Boolean> = _peerIdentityTrusted.asStateFlow()

    private val _peerIdentityFingerprint = MutableStateFlow<String?>(null)
    /**
     * Hex of the peer's 16-byte identity ID, for manual verification on the
     * pairing screen. Despite the name, this is NOT a fingerprint of the
     * long-term identity key — it is the identity ID the peer advertises in
     * its HELLO (see [SessionCrypto.IDENTITY_ID_BYTES]). Distinct from the
     * per-session SAS: the SAS authenticates this session, the identity ID
     * pins the peer across sessions.
     */
    val peerIdentityFingerprint: StateFlow<String?> = _peerIdentityFingerprint.asStateFlow()

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

    /** Handshake protocol state (extracted from the god class; SessionManager keeps the locks). */
    private val hs = HandshakeStateMachine()

    /**
     * Dedicated single thread for audio bring-up/teardown (AudioRecord /
     * AudioTrack creation can block 50-500 ms on the HAL). Never run these
     * on the UI thread — PTT press/release must stay responsive. FIFO
     * ordering keeps start/stop pairs serialized.
     */
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hamseda-audio-ctl").apply { isDaemon = true }
    }

    /**
     * Run [block] on the audio executor. If close() already shut it down,
     * run inline so teardown-after-close can never throw
     * RejectedExecutionException.
     */
    private fun runOnAudioExecutor(block: () -> Unit) {
        if (audioExecutor.isShutdown) {
            block()
        } else {
            audioExecutor.execute(block)
        }
    }

    /**
     * Barrier: wait until previously queued audio-executor tasks
     * (stopCapture/stopPlayback) have run before this thread touches shared
     * audio state (codec close in wipeSecrets()). Without it, a capture
     * worker can still be encoding when the codec is closed — harmless
     * (encode-after-close is caught per frame) but noisy and wasteful.
     * Best-effort with a timeout; never throws.
     */
    private fun drainAudioExecutor(timeoutMs: Long = 5_000L) {
        if (audioExecutor.isShutdown) return
        try {
            audioExecutor.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "audio executor drain failed: ${e.javaClass.simpleName}")
        }
    }


    private class KeyCommitmentMismatchException : Exception("KEY_EXCHANGE commitment mismatch")
    private class PeerIdentityMismatchException : Exception("peer identity signature or pin mismatch")
    /** True once beginHandshake ran for the current attempt (re-entrancy guard). */
    private var handshakeStarted = false
    private var selfSasConfirmed = false
    private var activeCodecId: Byte = CodecId.OPUS
    private var codec: AudioCodec? = null
    /**
     * Separate encode-side instance: TX (capture thread) and RX (playback
     * thread) must not serialize on one codec's internal lock.
     */
    private var txCodec: AudioCodec? = null

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
    // Intervals within a continuous speech burst measure transport cadence.
    // Gaps >250 ms are tracked separately, since they may be ordinary PTT
    // silence/turn changes and must not inflate the cadence average.
    private var audioReceiveIntervalSamples = 0
    private var audioReceiveIntervalTotalNanos = 0L
    private var audioReceiveIntervalMaxNanos = 0L
    private var audioReceiveCadenceMaxNanos = 0L
    private var audioReceiveLongGaps = 0
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
        var buildFailed = false
        try {
            for ((index, message) in messages.withIndex()) {
                // A prior blocked socket write may make queued voice obsolete.
                // Drop it before assigning a sequence or serializing a frame.
                if (isAudioMessage(message)) {
                    val audio = message as OutboundMsg.Sealed
                    val ageNanos = (System.nanoTime() - audio.enqueuedAtNanos).coerceAtLeast(0L)
                    queuedAtWaitNanos[index] = maxOf(queuedAtWaitNanos[index], ageNanos)
                    if (isAudioFrameExpired(ageNanos, MAX_OUTBOUND_AUDIO_AGE_MS * 1_000_000L)) {
                        outboundQueue.recordDroppedAudioFrame()
                        continue
                    }
                }
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
                    buildFailed = true
                    Log.w(TAG, "outbound build failed: " + e.javaClass.simpleName)
                    AppLog.log(TAG, "outbound frame construction failed; ending session")
                    endSession(SessionError.PROTOCOL_ERROR, notifyPeer = false)
                    break
                }
            }
            if (frames.isNotEmpty() && !buildFailed) {
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
        audioReceiveCadenceMaxNanos = 0L
        audioReceiveLongGaps = 0
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
            audioReceiveIntervalMaxNanos = maxOf(audioReceiveIntervalMaxNanos, interval)
            if (interval > AUDIO_BURST_GAP_THRESHOLD_MS * 1_000_000L) {
                // Preserve the longest interval, but do not call idle time
                // between speech bursts network jitter or include it in the
                // within-burst mean.
                audioReceiveLongGaps++
            } else {
                audioReceiveIntervalSamples++
                audioReceiveIntervalTotalNanos += interval
                audioReceiveCadenceMaxNanos = maxOf(audioReceiveCadenceMaxNanos, interval)
                if (interval >= 30_000_000L) audioReceiveGapsOver30Ms++
                if (interval >= 60_000_000L) audioReceiveGapsOver60Ms++
                if (interval >= 100_000_000L) audioReceiveGapsOver100Ms++
            }
        }
        if (audioReceiveWindowFrames < AUDIO_RECEIVE_TIMING_LOG_INTERVAL) return

        fun ms(nanos: Long) = String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0)
        val intervalSamples = audioReceiveIntervalSamples
        val avgIntervalNanos = if (intervalSamples > 0) {
            audioReceiveIntervalTotalNanos / intervalSamples
        } else 0L
        AppLog.log(
            TAG,
            "audio receive timing: frames=$audioReceiveWindowFrames, cadenceIntervals=$intervalSamples, " +
                "avgIntervalMs=" + ms(avgIntervalNanos) +
                ", maxIntervalMs=" + ms(audioReceiveIntervalMaxNanos) +
                ", maxCadenceIntervalMs=" + ms(audioReceiveCadenceMaxNanos) +
                ", interBurstGapsOver${AUDIO_BURST_GAP_THRESHOLD_MS}Ms=$audioReceiveLongGaps, " +
                "gapsOver30Ms=$audioReceiveGapsOver30Ms, gapsOver60Ms=$audioReceiveGapsOver60Ms, " +
                "gapsOver100Ms=$audioReceiveGapsOver100Ms, jitterBuffered=" + jitter.bufferedFrames() + ", " +
                "jitterStaleDropped=" + jitter.droppedStale + ", jitterLost=" + jitter.lost,
        )
        // Keep lastAudioReceiveAtNanos across windows for a continuous cadence measurement.
        audioReceiveWindowFrames = 0
        audioReceiveIntervalSamples = 0
        audioReceiveIntervalTotalNanos = 0L
        audioReceiveIntervalMaxNanos = 0L
        audioReceiveCadenceMaxNanos = 0L
        audioReceiveLongGaps = 0
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

    /**
     * Suspending variant for teardown: cancel the sender and wait (bounded)
     * for any in-flight frame build to finish BEFORE the caller wipes hs.keys.
     * Without the join, a sender suspended mid-build could race the key wipe.
     * Best-effort: never blocks teardown longer than the bound.
     */
    private suspend fun stopSenderAndJoin() {
        val job = senderJob
        senderJob = null
        job?.cancel()
        withTimeoutOrNull(SENDER_JOIN_TIMEOUT_MS) { job?.join() }
        drainOutbound()
    }

    private fun enqueuePlain(type: Byte, payload: ByteArray): Boolean {
        if (!enqueueOutbound(OutboundMsg.Plain(type, payload))) {
            payload.fill(0)
            Log.w(TAG, "outbound queue full; dropping plain frame type=$type")
            return false
        }
        return true
    }

    /**
     * Queues one sealed offline-messaging frame on the control channel.
     * Returns false unless the session is live and the frame was accepted.
     * The plaintext is copied; the caller retains ownership of its buffer.
     */
    fun sendMessagingFrame(type: Byte, plaintext: ByteArray): Boolean {
        if (_phase.value != Phase.IN_SESSION) return false
        val k = hs.keys ?: return false
        val msg = OutboundMsg.Sealed(
            type = type,
            codecId = 0xFF.toByte(),
            sessionId = k.sessionId.copyOf(),
            key = k.controlKey,
            role = hs.myRole,
            plaintext = plaintext.copyOf(),
        )
        if (!enqueueOutbound(msg)) {
            Log.w(TAG, "outbound queue full; dropping messaging frame type=$type")
            return false
        }
        return true
    }

    /** Takes ownership of the input buffer, which is cleared after copying. */
    private fun enqueueControl(type: Byte, plaintext: ByteArray) {
        val k = hs.keys
        if (k == null) {
            plaintext.fill(0)
            return
        }
        val msg = OutboundMsg.Sealed(
            type = type,
            codecId = 0xFF.toByte(),
            sessionId = k.sessionId.copyOf(),
            key = k.controlKey,
            role = hs.myRole,
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
        val k = hs.keys ?: return
        val msg = OutboundMsg.Sealed(
            type = MessageType.AUDIO,
            codecId = codecId,
            sessionId = k.sessionId.copyOf(),
            key = k.audioKey,
            role = hs.myRole,
            plaintext = plaintext.copyOf(),
        )
        if (!enqueueOutbound(msg)) {
            Log.w(TAG, "realtime audio queue rejected a frame (pending=" + outboundQueue.size() + ")")
            return
        }
        val dropped = outboundQueue.droppedAudioFrames
        if (dropped >= lastLoggedAudioDrops + AUDIO_DROP_LOG_INTERVAL) {
            synchronized(audioDropLogLock) {
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

    /**
     * Codec policy, single source of truth: PCM16 requires 768 kbps before
     * protocol overhead and is not a usable voice profile over Bluetooth
     * Classic RFCOMM, so Bluetooth sessions always use Opus.
     */
    private fun resolveCodecPref(t: Transport, pref: Byte): Byte =
        if (t.type == TransportType.BLUETOOTH) CodecId.OPUS else pref

    /** Outgoing session: connect the transport, then run the handshake. */
    fun startOutgoing(t: Transport, peer: PeerDevice, codecPref: Byte = CodecId.OPUS) {
        if (!claimSessionStart("startOutgoing")) return
        AppLog.log(TAG, "outgoing session start via ${t.type}")
        hs.myCodecPref = resolveCodecPref(t, codecPref)
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
        hs.myCodecPref = resolveCodecPref(t, codecPref)
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
        // The transport's inbound SharedFlow is intentionally unbuffered so
        // slow frame processing backpressures the reader rather than queueing a
        // second's worth of stale voice. Subscribe synchronously before the state
        // watcher starts the handshake or connect()/listen() can produce frames.
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
        // enterSession() blocks on native codec init and the AudioTrack HAL
        // (50-500ms). It is fully serialized on sessionEntryLock and safe
        // from any thread — the inbound SAS_CONFIRM reader already calls it
        // off the main thread. Dispatch here too so this UI-tap handler
        // never freezes; the HS-05 rollback still applies inside enterSession().
        cleanupScope.launch { maybeEnterSession() }
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

    /** Ends the session, wipes hs.keys, stops audio, disconnects transport. */
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
        if (notifyPeer && hs.keys != null) {
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
    /**
     * Wipe all key material and identity secrets. Extracted from teardown()
     * and close(): this is exactly the code that must never drift between
     * the two paths. Callers handle transport/audio/flags around it.
     */
    private fun wipeSecrets() {
        hs.wipe()
        // Each close guarded: a throwing codec close must not abort the
        // rest of the wipe — and via close(), must never skip scheduling
        // the cleanup block (its callback releases the service wake lock).
        try {
            codec?.close()
        } catch (e: Exception) {
            Log.w(TAG, "wipeSecrets: codec close failed: ${e.javaClass.simpleName}")
        }
        codec = null
        try {
            txCodec?.close()
        } catch (e: Exception) {
            Log.w(TAG, "wipeSecrets: txCodec close failed: ${e.javaClass.simpleName}")
        }
        txCodec = null
        _peerIdentityTrusted.value = false
        _peerIdentityFingerprint.value = null
    }

    private suspend fun teardown(error: SessionError?) = teardownMutex.withLock {
        cancelDeadlines()
        stopTransmit()
        stopSenderAndJoin() // flush-then-wipe: join the sender before hs.keys are wiped below
        runOnAudioExecutor {
            try { audio.stopPlayback() } catch (e: Exception) { Log.w(TAG, "teardown: stopPlayback failed: ${e.javaClass.simpleName}") }
        }
        // Barrier: the queued stopCapture/stopPlayback above must finish
        // before wipeSecrets() closes the codecs below.
        drainAudioExecutor()
        livenessJob?.cancel(); livenessJob = null
        floor.reset()
        jitter.reset()
        replay.reset()
        receivedAudioSeq = 0
        // hs.wipe() inside wipeSecrets() already reset all handshake flags
        // (sent markers, hello/codec state); do not duplicate them here.
        wipeSecrets()
        pendingKeyConfirmFrame?.payload?.fill(0); pendingKeyConfirmFrame = null
        pendingSasConfirmFrame?.payload?.fill(0); pendingSasConfirmFrame = null
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
        } catch (e: Exception) { Log.w(TAG, "teardown: transport disconnect failed: ${e.javaClass.simpleName}") }
        detachTransport()
        _transportType.value = null
        AppLog.log(TAG, "session teardown complete: reason=${error?.name ?: "USER_HANGUP"}")
        synchronized(lifecycleLock) { _phase.value = Phase.IDLE }
    }

    /**
     * Fast synchronous path for Service.onDestroy (HS-03): stops audio and
     * wipes hs.keys immediately without depending on the service scope (which
     * onDestroy is about to cancel). Transport close runs best-effort.
     *
     * Residual race (intentional): unlike teardown()'s stopSenderAndJoin(),
     * this cancels the sender without joining it — a sender suspended
     * mid-frame-build may race wipeSecrets(). Joining here could block
     * onDestroy past the ANR threshold on a stuck HAL, which is worse than
     * the race: encode-after-close is caught per frame and OpusCodec.close()
     * is serialized on the codec lock, so there is no native use-after-free.
     *
     * @param onCleanupDone invoked on the cleanup thread after the async
     * transport disconnect, executor drain, and shutdown complete — use it
     * to release resources (e.g. the service wake lock) that must outlive
     * the whole teardown. Never null-checked by callers; always invoked
     * exactly once per close() unless the process dies first (in which case
     * the OS releases the wake lock anyway).
     */
    fun close(onCleanupDone: (() -> Unit)? = null) {
        synchronized(lifecycleLock) { _phase.value = Phase.ENDED }
        cancelDeadlines()
        stopSender()
        runOnAudioExecutor {
            try { audio.stopCapture() } catch (e: Exception) { Log.w(TAG, "close: stopCapture failed: ${e.javaClass.simpleName}") }
            try { audio.stopPlayback() } catch (e: Exception) { Log.w(TAG, "close: stopPlayback failed: ${e.javaClass.simpleName}") }
        }
        // wipeSecrets() stays synchronous: it is fast (no HAL, no blocking)
        // and must run before onDestroy returns. The drain/shutdown barrier
        // moves off the main thread below: drainAudioExecutor() can block up
        // to 5s (the ANR threshold) on a stuck HAL/worker, and the barrier
        // is not needed for correctness — encode-after-close is caught per
        // frame and OpusCodec.close() is serialized on the codec lock, so
        // there is no native use-after-free.
        // Guarded so NOTHING in the sync prologue can skip scheduling the
        // cleanup block below: the callback contract depends on it.
        try {
            wipeSecrets()
        } catch (e: Exception) {
            Log.w(TAG, "close: wipeSecrets failed: ${e.javaClass.simpleName}")
        }
        jitter.reset()
        replay.reset()
        pendingKeyConfirmFrame?.payload?.fill(0); pendingKeyConfirmFrame = null
        pendingSasConfirmFrame?.payload?.fill(0); pendingSasConfirmFrame = null
        handshakeStarted = false
        _sasCode.value = null
        _peerSasConfirmed.value = false
        _isTransmitting.value = false
        _floorHolder.value = FloorController.Holder.NONE
        _voiceLevel.value = 0f

        // This must not use the service's child scope: VoiceService.onDestroy()
        // cancels that scope immediately after close() returns. Serialize final
        // transport cleanup with an in-flight teardown and keep it non-cancellable.
        cleanupScope.launch {
            try {
                withContext(NonCancellable) {
                    teardownMutex.withLock {
                        try { transport?.disconnect() } catch (e: Exception) { Log.w(TAG, "cleanup: transport disconnect failed: ${e.javaClass.simpleName}") }
                        try { detachTransport() } catch (e: Exception) { Log.w(TAG, "cleanup: detachTransport failed: ${e.javaClass.simpleName}") }
                        _transportType.value = null
                        synchronized(lifecycleLock) { _phase.value = Phase.IDLE }
                    }
                }
                // Off-main barrier (was on the main thread above): let the
                // queued stopCapture/stopPlayback finish, then shut the executor
                // down. Runs here — not on the main thread — because the drain
                // can block up to 5s on a stuck HAL/worker (the ANR threshold).
                // No self-deadlock: neither close() nor teardown() runs on the
                // audio executor thread.
                try {
                    drainAudioExecutor()
                    audioExecutor.shutdown()
                } catch (e: Exception) {
                    Log.w(TAG, "cleanup: audio executor shutdown failed: ${e.javaClass.simpleName}")
                }
            } finally {
                // Hard contract (see KDoc): the callback fires exactly once
                // on EVERY path — VoiceService releases its wake lock only
                // here, and a swallowed failure would hold it until process
                // death. The cleanup scope is deliberately NOT cancelled at
                // the end of this block: cancelling the shared scope from
                // inside one close() would smother a second close()'s block
                // before it ever runs (its callback would never fire). The
                // scope is idle once cleanup completes and dies with the
                // manager.
                try {
                    onCleanupDone?.invoke()
                } catch (e: Exception) {
                    Log.w(TAG, "cleanup: onCleanupDone failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // ------------------------------------------------------- handshake

    private fun beginHandshake() = synchronized(handshakeFrameLock) {
        // A CONNECTED state callback and the first inbound HELLO can race on
        // different dispatcher threads. Keep startup atomic with inbound frame
        // processing so the commitment/reveal exchange cannot overtake local HELLO.
        if (handshakeStarted) return
        handshakeStarted = true
        // Per-attempt handshake state: single source of truth is
        // HandshakeStateMachine.resetForNewHandshake(). This only runs on
        // the first handshake (fields already zero) or after teardown()
        // (which ran hs.wipe() via wipeSecrets()), so nothing else needs
        // clearing here.
        hs.resetForNewHandshake()
        _phase.value = Phase.HANDSHAKE
        startHandshakeDeadline()
        try {
            hs.myIdentityId = peerIdentityStore.localIdentityId()
            hs.myIdentityPublicKey = peerIdentityStore.localPublicKey()
            require(hs.myIdentityId.size == SessionCrypto.IDENTITY_ID_BYTES) {
                "local identity ID must be 16 bytes"
            }
            SessionCrypto.decodePublicKey(hs.myIdentityPublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "local identity key unavailable: ${e.javaClass.simpleName}")
            fail(SessionError.PROTOCOL_ERROR, "local identity key unavailable")
            return@synchronized
        }
        hs.myKeyPair = SessionCrypto.generateEphemeralKeyPair()
        // Invariant: assigned immediately above in this same synchronized block.
        hs.myPubBytes = SessionCrypto.encodePublicKey(checkNotNull(hs.myKeyPair) { "ephemeral keypair just generated" }.public)
        hs.myNonce = SessionCrypto.newNonce16()
        hs.myCommitSalt = SessionCrypto.newCommitmentSalt()
        sendSequence.reset()
        sequenceExhaustionTriggered.set(false)
        lastPeerSeen = elapsedClock()
        // Queue our protocol preamble before another thread can process the
        // peer HELLO and enqueue KEY_COMMIT. The sender serializes this FIFO.
        val hello = byteArrayOf(Protocol.VERSION, hs.myCodecPref)
        enqueuePlain(MessageType.HELLO, hello)
        Log.i(TAG, "handshake started, HELLO sent")
        val handshakeElapsedMs = attemptStartedAtMs
            ?.let { (elapsedClock() - it).coerceAtLeast(0L) } ?: -1L
        AppLog.log(
            TAG,
            "handshake started: attempt=$sessionGeneration, elapsedSinceAttemptMs=$handshakeElapsedMs, " +
                "HELLO sent (localProtocolVersion=${Protocol.VERSION.toInt() and 0xFF}, " +
                "codecPreference=${hs.myCodecPref.toInt() and 0xFF})",
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
        if (MessageType.isPlaintext(frame.type) &&
            (frame.codecId != 0xFF.toByte() || frame.sessionId.any { it != 0.toByte() })
        ) {
            fail(SessionError.PROTOCOL_ERROR, "invalid plaintext handshake frame header")
            return
        }
        // A valid HELLO is the protocol preamble. TCP preserves send order, so
        // accepting KEY_EXCHANGE first would mean the peer skipped validation
        // of protocol version and codec negotiation.
        if (!hs.peerHelloReceived &&
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
                if (hs.peerHelloReceived) {
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
                hs.peerCodecPref = peerPref
                hs.peerHelloReceived = true
                AppLog.log(
                    TAG,
                    "received compatible HELLO (protocolVersion=$peerVersion, codecPreference=$peerPrefValue)",
                )
                activeCodecId = minOf(hs.myCodecPref, peerPref)
                if (!hs.keyCommitSent) sendKeyCommit()
            }
            MessageType.KEY_COMMIT -> {
                if (frame.payload.size != SessionCrypto.COMMITMENT_BYTES) {
                    fail(SessionError.PROTOCOL_ERROR, "invalid KEY_COMMIT length")
                    return
                }
                if (hs.peerKeyCommitment != null) {
                    fail(SessionError.PROTOCOL_ERROR, "duplicate KEY_COMMIT")
                    return
                }
                if (!hs.keyCommitSent) {
                    fail(SessionError.PROTOCOL_ERROR, "peer KEY_COMMIT arrived before local commitment")
                    return
                }
                hs.peerKeyCommitment = frame.payload.copyOf()
                if (!hs.keyExchangeSent) sendKeyExchange()
            }
            MessageType.KEY_EXCHANGE -> {
                if (hs.peerPubBytes != null) return // duplicate; ignore
                if (hs.peerKeyCommitment == null) {
                    fail(SessionError.PROTOCOL_ERROR, "KEY_EXCHANGE received before KEY_COMMIT")
                    return
                }
                if (!hs.keyExchangeSent) {
                    fail(SessionError.PROTOCOL_ERROR, "peer reveal arrived before local reveal")
                    return
                }
                try {
                    onKeyExchange(frame.payload)
                    deriveAndConfirm()
                    AppLog.log(TAG, "KEY_EXCHANGE commitment verified; key derivation completed")
                } catch (e: PeerIdentityMismatchException) {
                    Log.w(TAG, "KEY_EXCHANGE identity proof or TOFU pin mismatch")
                    fail(SessionError.AUTH_MISMATCH, "peer identity verification failed")
                    return
                } catch (e: KeyCommitmentMismatchException) {
                    Log.w(TAG, "KEY_EXCHANGE does not match prior commitment")
                    fail(SessionError.AUTH_MISMATCH, "KEY_EXCHANGE commitment mismatch")
                    return
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
            MessageType.SAS_CONFIRM -> {
                // A peer can verify our KEY_CONFIRM and send confirmation just
                // before we process its KEY_CONFIRM. Defer until transcript
                // verification makes the SAS-phase state transition.
                if (hs.keys == null) {
                    fail(SessionError.PROTOCOL_ERROR, "SAS_CONFIRM received before key derivation")
                    return
                }
                if (pendingSasConfirmFrame == null) {
                    pendingSasConfirmFrame = frame.copy(payload = frame.payload.copyOf())
                } else {
                    _authFailures.value += 1
                }
            }
            MessageType.KEY_CONFIRM -> {
                // May arrive before we finished deriving (both sides race).
                if (hs.keys == null) {
                    // Wipe the stashed payload before overwrite: it carries
                    // key-confirmation material and must not linger.
                    pendingKeyConfirmFrame?.payload?.fill(0)
                    pendingKeyConfirmFrame = frame
                } else {
                    verifyPeerKeyConfirm(frame)
                }
            }
            MessageType.DISCONNECT -> {
                // Once hs.keys exist, DISCONNECT must authenticate like every
                // other control frame. Before derivation, accept only the
                // protocol's exact plaintext shape.
                // Accepted risk (DoS only, no key compromise): a plaintext
                // DISCONNECT of the exact shape can kill a pre-handshake
                // session with a single frame. Anyone on the radio path can
                // already drop frames; this adds no new capability beyond a
                // marginally cheaper teardown signal.
                val activeKeys = hs.keys
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

    private fun sendKeyCommit() {
        if (hs.keyCommitSent) return
        val hello = byteArrayOf(Protocol.VERSION, hs.myCodecPref)
        val commitment = try {
            SessionCrypto.keyCommitment(
                hs.myPubBytes, hs.myNonce, hello, hs.myCommitSalt, hs.myIdentityId, hs.myIdentityPublicKey,
            )
        } finally {
            hello.fill(0)
        }
        hs.keyCommitSent = true
        if (!enqueuePlain(MessageType.KEY_COMMIT, commitment)) {
            fail(SessionError.PROTOCOL_ERROR, "could not enqueue KEY_COMMIT")
        }
    }

    private fun sendKeyExchange() {
        if (hs.keyExchangeSent) return
        if (hs.peerKeyCommitment == null) {
            fail(SessionError.PROTOCOL_ERROR, "cannot reveal before peer KEY_COMMIT")
            return
        }
        val pub = hs.myPubBytes
        val salt = hs.myCommitSalt
        if (salt.size != SessionCrypto.COMMITMENT_SALT_BYTES) {
            fail(SessionError.PROTOCOL_ERROR, "local commitment salt unavailable")
            return
        }
        if (hs.myIdentityId.size != SessionCrypto.IDENTITY_ID_BYTES || hs.myIdentityPublicKey.isEmpty()) {
            fail(SessionError.PROTOCOL_ERROR, "local identity material unavailable")
            return
        }

        val hello = byteArrayOf(Protocol.VERSION, hs.myCodecPref)
        val proofInput = try {
            SessionCrypto.identityProofInput(
                hs.myIdentityId, hs.myIdentityPublicKey, pub, hs.myNonce, hello, salt,
            )
        } finally {
            hello.fill(0)
        }
        val signature = try {
            peerIdentityStore.signIdentity(proofInput)
        } finally {
            proofInput.fill(0)
        }
        if (signature.isEmpty() || signature.size > SessionCrypto.MAX_IDENTITY_SIGNATURE_BYTES) {
            signature.fill(0)
            salt.fill(0)
            hs.myCommitSalt = ByteArray(0)
            fail(SessionError.PROTOCOL_ERROR, "local identity signature size invalid")
            return
        }

        // v5 wire layout: ephLen(2)||ephKey||nonce16||salt32||identityId16||
        // identityKeyLen(2)||identityKey||signatureLen(2)||ECDSA signature.
        val payload = ByteBuffer.allocate(
            2 + pub.size + hs.myNonce.size + salt.size +
                hs.myIdentityId.size + 2 + hs.myIdentityPublicKey.size + 2 + signature.size,
        ).order(ByteOrder.BIG_ENDIAN)
            .putShort(pub.size.toShort()).put(pub)
            .put(hs.myNonce).put(salt)
            .put(hs.myIdentityId)
            .putShort(hs.myIdentityPublicKey.size.toShort()).put(hs.myIdentityPublicKey)
            .putShort(signature.size.toShort()).put(signature)
            .array()

        hs.keyExchangeSent = true
        val enqueued = try {
            enqueuePlain(MessageType.KEY_EXCHANGE, payload)
        } finally {
            salt.fill(0)
            hs.myCommitSalt = ByteArray(0)
            signature.fill(0)
        }
        if (!enqueued) fail(SessionError.PROTOCOL_ERROR, "could not enqueue KEY_EXCHANGE")
    }

    private fun onKeyExchange(payload: ByteArray) {
        val minimum = 2 + 1 + 16 + SessionCrypto.COMMITMENT_SALT_BYTES +
            SessionCrypto.IDENTITY_ID_BYTES + 2 + 1 + 2 + 1
        require(payload.size >= minimum) { "KEY_EXCHANGE too short" }
        require(payload.size <= Protocol.MAX_PAYLOAD) { "KEY_EXCHANGE exceeds maximum payload" }

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val pubLen = buf.short.toInt() and 0xFFFF
        require(pubLen in 1..SessionCrypto.MAX_PUBKEY_BYTES) {
            "ephemeral public key length out of bounds"
        }
        require(buf.remaining() >= pubLen + 16 + SessionCrypto.COMMITMENT_SALT_BYTES +
            SessionCrypto.IDENTITY_ID_BYTES + 2 + 1 + 2 + 1) { "KEY_EXCHANGE truncated" }

        val pub = ByteArray(pubLen).also { buf.get(it) }
        val nonce = ByteArray(16).also { buf.get(it) }
        val salt = ByteArray(SessionCrypto.COMMITMENT_SALT_BYTES).also { buf.get(it) }
        val identityId = ByteArray(SessionCrypto.IDENTITY_ID_BYTES).also { buf.get(it) }
        val identityPubLen = buf.short.toInt() and 0xFFFF
        require(identityPubLen in 1..SessionCrypto.MAX_PUBKEY_BYTES) {
            "identity public key length out of bounds"
        }
        require(buf.remaining() >= identityPubLen + 2 + 1) { "identity public key truncated" }
        val identityPublicKey = ByteArray(identityPubLen).also { buf.get(it) }
        val signatureLength = buf.short.toInt() and 0xFFFF
        require(
            signatureLength in 1..SessionCrypto.MAX_IDENTITY_SIGNATURE_BYTES &&
                buf.remaining() == signatureLength,
        ) { "identity signature length invalid" }
        val signature = ByteArray(signatureLength).also { buf.get(it) }

        var accepted = false
        var pinnedKey: ByteArray? = null
        try {
            SessionCrypto.decodePublicKey(pub)
            SessionCrypto.decodePublicKey(identityPublicKey)
            val peerPref = hs.peerCodecPref ?: error("peer HELLO codec preference absent")
            val peerHello = byteArrayOf(Protocol.VERSION, peerPref)
            val expectedCommitment = try {
                SessionCrypto.keyCommitment(
                    pub, nonce, peerHello, salt, identityId, identityPublicKey,
                )
            } finally {
                peerHello.fill(0)
            }
            try {
                val committed = hs.peerKeyCommitment ?: error("peer commitment absent")
                if (!MessageDigest.isEqual(expectedCommitment, committed)) {
                    throw KeyCommitmentMismatchException()
                }
            } finally {
                expectedCommitment.fill(0)
            }

            val helloForProof = byteArrayOf(Protocol.VERSION, peerPref)
            val proofInput = try {
                SessionCrypto.identityProofInput(
                    identityId, identityPublicKey, pub, nonce, helloForProof, salt,
                )
            } finally {
                helloForProof.fill(0)
            }
            val validSignature = try {
                SessionCrypto.verifyIdentitySignature(identityPublicKey, proofInput, signature)
            } finally {
                proofInput.fill(0)
            }
            if (!validSignature) throw PeerIdentityMismatchException()

            pinnedKey = peerIdentityStore.trustedPublicKey(identityId)
            if (pinnedKey != null && !MessageDigest.isEqual(pinnedKey, identityPublicKey)) {
                // A known identity ID can never silently rotate to a different key.
                throw PeerIdentityMismatchException()
            }

            hs.peerIdentityWasTrusted = pinnedKey != null
            _peerIdentityTrusted.value = hs.peerIdentityWasTrusted
            hs.peerPubBytes = pub
            hs.peerNonce = nonce
            hs.peerIdentityId = identityId
            _peerIdentityFingerprint.value = identityId.joinToString("") { "%02x".format(it) }
            hs.peerIdentityPublicKey = identityPublicKey
            accepted = true
        } finally {
            salt.fill(0)
            signature.fill(0)
            pinnedKey?.fill(0)
            if (!accepted) {
                pub.fill(0)
                nonce.fill(0)
                identityId.fill(0)
                identityPublicKey.fill(0)
            }
        }
    }

    private fun deriveAndConfirm() {
        val peerPub = hs.peerPubBytes ?: return
        val peerNc = hs.peerNonce ?: return
        val kp = hs.myKeyPair ?: return
        val secret = SessionCrypto.agreeSharedSecret(kp, SessionCrypto.decodePublicKey(peerPub))
        SessionCrypto.destroyPrivateKey(kp.private)
        hs.myKeyPair = null // private key is no longer needed after ECDH
        try {
            val derived = SessionCrypto.deriveKeys(secret, hs.myNonce, peerNc)
            hs.keys = derived
            hs.myRole = SessionCrypto.myRole(hs.myPubBytes, peerPub)
            val transcript = hs.transcriptHash()
            if (!hs.keyConfirmSent) {
                hs.keyConfirmSent = true
                val confirmPayload = transcript + byteArrayOf(if (hs.peerIdentityWasTrusted) 1 else 0)
                try {
                    enqueueControl(MessageType.KEY_CONFIRM, confirmPayload)
                } finally {
                    confirmPayload.fill(0)
                    transcript.fill(0)
                }
            } else {
                transcript.fill(0)
            }
            pendingKeyConfirmFrame?.let { stashed ->
                pendingKeyConfirmFrame = null
                try {
                    verifyPeerKeyConfirm(stashed)
                } finally {
                    stashed.payload.fill(0)
                }
            }
        } finally {
            secret.fill(0)
        }
    }

    // KEY_CONFIRM frames that arrive before we derived are stashed here.
    private var pendingKeyConfirmFrame: Frame? = null
    /** SAS confirmation can race ahead of local processing of peer KEY_CONFIRM. */
    private var pendingSasConfirmFrame: Frame? = null

    private fun verifyPeerKeyConfirm(frame: Frame) {
        val k = hs.keys ?: return
        val opened = try {
            openControl(frame, k)
        } catch (e: Exception) {
            Log.w(TAG, "KEY_CONFIRM failed authentication (" + e.javaClass.simpleName + ")")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        val expected = hs.transcriptHash()
        try {
            verifyKeyConfirm(opened, expected)
        } finally {
            opened.fill(0)
            expected.fill(0)
        }
    }

    private fun handleSasPhaseFrame(frame: Frame) {
        val k = hs.keys ?: return
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
        val expected = hs.transcriptHash()
        try {
            verifyKeyConfirm(payload, expected)
        } finally {
            expected.fill(0)
        }
    }

    private fun verifyKeyConfirm(payload: ByteArray, expectedTranscript: ByteArray) {
        if (payload.size != expectedTranscript.size + 1) {
            Log.w(TAG, "KEY_CONFIRM payload length invalid")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        val trustByte = payload[payload.lastIndex].toInt() and 0xFF
        if (trustByte !in 0..1) {
            Log.w(TAG, "KEY_CONFIRM trust flag invalid")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        val receivedTranscript = payload.copyOfRange(0, expectedTranscript.size)
        val matches = try {
            MessageDigest.isEqual(receivedTranscript, expectedTranscript)
        } finally {
            receivedTranscript.fill(0)
        }
        if (!matches) {
            Log.w(TAG, "KEY_CONFIRM transcript mismatch — possible MITM")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }

        val reportedTrust = trustByte == 1
        val priorReport = hs.peerReportedIdentityTrusted
        if (priorReport != null && priorReport != reportedTrust) {
            Log.w(TAG, "KEY_CONFIRM peer trust flag changed during session")
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        hs.peerReportedIdentityTrusted = reportedTrust
        onKeyConfirmAccepted()
    }

    private fun onKeyConfirmAccepted() {
        if (_phase.value != Phase.HANDSHAKE) return
        _phase.value = Phase.AWAITING_SAS_CONFIRM
        // Skip SAS only when both endpoints attest—inside authenticated
        // KEY_CONFIRM frames—that each has already pinned the other's identity.
        // If either pin is absent (e.g. local data loss), both show/compare SAS.
        val mutuallyTrusted = hs.peerIdentityWasTrusted && hs.peerReportedIdentityTrusted == true
        if (mutuallyTrusted) {
            _sasCode.value = null
            selfSasConfirmed = true
            enqueueControl(MessageType.SAS_CONFIRM, ByteArray(0))
            AppLog.log(TAG, "both peer identities pinned; SAS skipped")
        } else {
            // Invariant: SAS is only computed after hs.keys are established.
            _sasCode.value = SessionCrypto.shortAuthString(checkNotNull(hs.keys) { "session hs.keys established before SAS" }.sasSeed)
            AppLog.log(TAG, "first contact or asymmetric trust; awaiting SAS comparison")
        }
        startSasDeadline()
        val pendingSas = pendingSasConfirmFrame
        pendingSasConfirmFrame = null
        if (pendingSas != null) {
            try {
                handleSasPhaseFrame(pendingSas)
            } finally {
                pendingSas.payload.fill(0)
            }
        }
    }

    private fun maybeEnterSession() {
        if (_phase.value != Phase.AWAITING_SAS_CONFIRM) return
        if (!selfSasConfirmed || !_peerSasConfirmed.value) return
        enterSession()
    }

    /**
     * Undoes this call's codec/playback bring-up. Called from the abort
     * paths of [enterSession] when the phase already moved: teardown ran
     * while we held [sessionEntryLock] — it never takes that lock, so it
     * could not see or clean up what we assigned afterwards.
     *
     * Takes and nulls the fields before closing so a racing teardown
     * observes null and skips them (OpusCodec.close() is idempotent-safe
     * regardless: handles are zeroed before the native destroy, which
     * null-checks). Mirrors teardown's order — stop playback first, then
     * close the codecs. Safe to call when teardown already took the fields
     * (null-safe) and when playback never started (stopPlayback is
     * idempotent).
     */
    private fun abortBringup() {
        val doomedCodec = codec
        val doomedTxCodec = txCodec
        codec = null
        txCodec = null
        try {
            audio.stopPlayback()
        } catch (e: Exception) {
            Log.w(TAG, "abortBringup: stopPlayback failed: ${e.javaClass.simpleName}")
        }
        try {
            doomedCodec?.close()
        } catch (e: Exception) {
            Log.w(TAG, "abortBringup: codec close failed: ${e.javaClass.simpleName}")
        }
        try {
            doomedTxCodec?.close()
        } catch (e: Exception) {
            Log.w(TAG, "abortBringup: txCodec close failed: ${e.javaClass.simpleName}")
        }
    }

    private fun enterSession() = synchronized(sessionEntryLock) {
        // confirmSas() and the inbound SAS_CONFIRM reader can race on different
        // threads. Only the first caller observing the SAS phase may initialize
        // codec/playback; later callers see the phase transition and return.
        if (_phase.value != Phase.AWAITING_SAS_CONFIRM) return@synchronized
        cancelDeadlines()
        val initializedCodec = try {
            // OpusCodec initializes its native encoder/decoder in its constructor;
            // don't immediately destroy and recreate those handles with reset().
            AudioCodec.create(activeCodecId)
        } catch (e: Exception) {
            Log.e(TAG, "codec initialization failed: ${e.javaClass.simpleName}", e)
            AppLog.log(TAG, "codec initialization failed: ${e.javaClass.simpleName}")
            endSession(SessionError.PLAYBACK_UNAVAILABLE, notifyPeer = true,
                disconnectReason = DisconnectReason.PROTOCOL_ERROR)
            return@synchronized
        } catch (e: LinkageError) {
            // Covers a missing/corrupt ABI library or JNI symbol, which is an
            // Error (not Exception) and otherwise could crash the app at PTT setup.
            Log.e(TAG, "native codec unavailable: ${e.javaClass.simpleName}", e)
            AppLog.log(TAG, "native codec unavailable: ${e.javaClass.simpleName}")
            endSession(SessionError.PLAYBACK_UNAVAILABLE, notifyPeer = true,
                disconnectReason = DisconnectReason.PROTOCOL_ERROR)
            return@synchronized
        }
        codec = initializedCodec
        // Dedicated encode-side instance so TX never contends with RX on the
        // codec's internal lock. Created here so a failure still aborts the
        // session via the same error path below.
        txCodec = try {
            AudioCodec.create(activeCodecId)
        } catch (e: Exception) {
            Log.e(TAG, "tx codec initialization failed: ${e.javaClass.simpleName}", e)
            initializedCodec.close()
            codec = null
            endSession(SessionError.PLAYBACK_UNAVAILABLE, notifyPeer = true,
                disconnectReason = DisconnectReason.PROTOCOL_ERROR)
            return@synchronized
        } catch (e: LinkageError) {
            Log.e(TAG, "tx native codec unavailable: ${e.javaClass.simpleName}", e)
            initializedCodec.close()
            codec = null
            endSession(SessionError.PLAYBACK_UNAVAILABLE, notifyPeer = true,
                disconnectReason = DisconnectReason.PROTOCOL_ERROR)
            return@synchronized
        }
        jitter.reset()
        replay.reset()
        lastPeerSeen = elapsedClock()
        receivedAudioSeq = 0
        resetAudioReceiveTiming()
        // HS-05: never report voice readiness if playback fails to start.
        // Roll the session back cleanly instead of a fake IN_SESSION.
        val playbackOk = try {
            // Invariant: codec is created before the session reaches voice setup.
            audio.startPlayback(checkNotNull(codec) { "codec initialized for session" }, jitter)
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
            // endSession is a no-op when the phase already moved (teardown
            // ran during bring-up); our codec/txCodec would leak. Clean up
            // our own bring-up unconditionally — abortBringup is null-safe
            // when teardown already took the fields.
            abortBringup()
            return@synchronized
        }
        // Pin a new peer only after mutual SAS confirmation and successful playback setup.
        if (!hs.peerIdentityWasTrusted) {
            val peerId = hs.peerIdentityId
            val peerKey = hs.peerIdentityPublicKey
            if (peerId != null && peerKey != null) {
                val stored = try {
                    peerIdentityStore.pinPeer(peerId, peerKey)
                } catch (e: Exception) {
                    Log.w(TAG, "could not persist peer identity pin: ${e.javaClass.simpleName}")
                    false
                }
                if (stored) {
                    hs.peerIdentityWasTrusted = true
                    _peerIdentityTrusted.value = true
                    AppLog.log(TAG, "peer identity pinned after mutual SAS confirmation")
                } else {
                    AppLog.log(TAG, "peer identity pin was not stored; SAS will be required next time")
                }
            }
        }
        // M7 re-check: endSession()/fail() may have run during the blocking
        // codec/playback bring-up above. Assigning IN_SESSION unconditionally
        // would resurrect a phantom session (UI connected, wake lock, liveness
        // job) after teardown. Check-and-set under lifecycleLock instead.
        val entered = synchronized(lifecycleLock) {
            if (_phase.value == Phase.AWAITING_SAS_CONFIRM) {
                _phase.value = Phase.IN_SESSION
                true
            } else {
                false
            }
        }
        if (!entered) {
            // The phase moved while we held sessionEntryLock: teardown ran
            // (it never takes this lock) and could not see or clean up what
            // we brought up afterwards. Clean up our own bring-up instead of
            // assuming teardown owns it — otherwise the native codec
            // handles, the AudioTrack, and the playback worker leak, and the
            // zombie worker can poison the next session's audio.
            Log.w(TAG, "session ended during codec bring-up; aborting enterSession")
            AppLog.log(TAG, "session ended during codec bring-up; aborting enterSession")
            abortBringup()
            return@synchronized
        }
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
        val k = hs.keys ?: return
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
                // Offline text messaging: hand the authenticated plaintext to
                // the registered MessagingEngine. A copy is passed because the
                // buffer is wiped in the finally block below.
                MessageType.MSG, MessageType.MSG_ACK, MessageType.MSG_READ,
                MessageType.TYPING, MessageType.CHUNK, MessageType.MSG_HELLO,
                MessageType.PROFILE, MessageType.PROFILE_PHOTO ->
                    messagingListener?.onMessagingFrame(frame.type, plaintext.copyOf())
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
            // Offline messaging frames: bounds-checked here; deeper codec
            // validation happens in MessagingEngine after authentication.
            MessageType.MSG ->
                if (plaintext.size in 33..(32 + Protocol.MSG_CHUNK_PLAINTEXT)) null
                else "invalid MSG payload length"
            MessageType.CHUNK ->
                if (plaintext.size in
                    (Protocol.MSG_CHUNK_HEADER_BYTES + 1)..
                    (Protocol.MSG_CHUNK_HEADER_BYTES + Protocol.MSG_CHUNK_PLAINTEXT)
                ) null else "invalid CHUNK payload length"
            MessageType.MSG_ACK, MessageType.MSG_READ ->
                if (plaintext.size == 16) null else "invalid messaging id payload length"
            MessageType.TYPING ->
                if (plaintext.size == 1) null else "invalid TYPING payload length"
            MessageType.MSG_HELLO ->
                if (plaintext.size == 8) null else "invalid MSG_HELLO payload length"
            // Profile exchange: nameLen(1) + name(0..64) + photoHash(32).
            MessageType.PROFILE ->
                if (plaintext.size in Protocol.PROFILE_MIN_BYTES..Protocol.PROFILE_MAX_BYTES) null
                else "invalid PROFILE payload length"
            // Profile photo chunks: photoId(16) + index(4) + total(4) + bytes.
            MessageType.PROFILE_PHOTO ->
                if (plaintext.size in
                    (Protocol.PROFILE_PHOTO_HEADER_BYTES + 1)..
                    (Protocol.PROFILE_PHOTO_HEADER_BYTES + Protocol.MSG_CHUNK_PLAINTEXT)
                ) null else "invalid PROFILE_PHOTO payload length"
            MessageType.KEY_CONFIRM ->
                if (plaintext.size == 33) null else "invalid KEY_CONFIRM payload length"
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
        // Encode-side instance: never share the RX codec across threads.
        val c = txCodec ?: return
        _isTransmitting.value = true
        // AudioRecord creation blocks on the HAL; run it on the audio
        // executor, never on the UI thread. The single thread keeps this
        // serialized with stopTransmit's stopCapture below.
        runOnAudioExecutor {
            if (!_isTransmitting.value) return@runOnAudioExecutor // released while queued
            // HS-02: the capture callback only ENQUEUES; the single outbound
            // sender allocates sequences and writes. No per-frame coroutines.
            val ok = try {
                audio.startCapture(c) { encoded, level ->
                    val now = elapsedClock()
                    if (now - lastLevelPush > 120) {
                        lastLevelPush = now
                        _voiceLevel.value = level
                    }
                    if (_phase.value == Phase.IN_SESSION && _isTransmitting.value) {
                        enqueueAudio(c.codecId, encoded)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "startTransmit: startCapture failed: ${e.javaClass.simpleName}")
                false
            }
            if (!ok) {
                _isTransmitting.value = false
                floor.releaseSelf()
                _error.value = SessionError.MIC_UNAVAILABLE
            }
        }
    }

    private fun stopTransmit() {
        if (_isTransmitting.value) {
            _isTransmitting.value = false
            _voiceLevel.value = 0f
        }
        // Off the UI thread: AudioRecord teardown can block on the HAL.
        runOnAudioExecutor {
            try {
                audio.stopCapture()
            } catch (e: Exception) { Log.w(TAG, "stopTransmit: stopCapture failed: ${e.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------- frame builders
    //
    // Called only from the single outbound sender coroutine, so allocation is deterministic.
    // Do not wrap uint32 sequence numbers within one session: replay-window serial arithmetic
    // and a random AEAD nonce do not make rollover safe at the protocol layer.
    // End before the guarded limit and establish a fresh authenticated session.
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
        frame.headerBytes() + (1 - hs.myRole).toByte()

    private fun openControl(frame: Frame, k: SessionKeys): ByteArray =
        SessionCrypto.open(k.controlKey, aadForReceive(frame), frame.payload)

    private fun transportErrorToSession(e: TransportError?): SessionError = when (e) {
        is TransportError.PermissionDenied -> SessionError.TRANSPORT_LOST
        else -> SessionError.TRANSPORT_LOST
    }

    /**
     * Fails the session without notifying the peer. This is deliberate:
     * - Auth failures (bad MAC, replay, identity mismatch) must not signal
     *   an attacker that we are still listening.
     * - Transport failures mean the peer likely already knows (the link is
     *   down); a DISCONNECT would not be deliverable anyway.
     * Use [endSession] with notifyPeer=true for user-initiated hangups where
     * the peer should be told.
     */
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
        // Bound stale voice waiting behind a blocked/slow socket to about 60 ms.
        // Fresh frames replace old ones during congestion instead of building latency.
        private const val MAX_QUEUED_AUDIO_FRAMES = 3
        /** Discard dequeued speech already stale before encryption/serialization. */
        private const val MAX_OUTBOUND_AUDIO_AGE_MS = 120L
        private const val MAX_BLUETOOTH_AUDIO_BATCH_FRAMES = 3
        private const val MAX_PENDING_OUTBOUND_MESSAGES = 64
        private const val AUDIO_DROP_LOG_INTERVAL = 50L
        private const val AUDIO_TIMING_LOG_INTERVAL = 50
        private const val AUDIO_RECEIVE_TIMING_LOG_INTERVAL = 50
        /** Intervals beyond this are reported separately from continuous speech cadence. */
        private const val AUDIO_BURST_GAP_THRESHOLD_MS = 250L
        /** Bounded join for the sender coroutine before key wipe (see stopSenderAndJoin). */
        private const val SENDER_JOIN_TIMEOUT_MS = 1000L
        private const val TAG = "HamSedaSession"
    }
}
