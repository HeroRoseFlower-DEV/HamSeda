package com.hamseda.walkie.session

import android.util.Log
import com.hamseda.walkie.audio.AudioCodec
import com.hamseda.walkie.audio.AudioPipeline
import com.hamseda.walkie.audio.JitterBuffer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val clock: () -> Long = System::currentTimeMillis,
) : FloorController.Listener, AudioPipeline.PipelineListener {

    enum class Phase {
        IDLE, CONNECTING_TRANSPORT, HANDSHAKE, AWAITING_SAS_CONFIRM, IN_SESSION, ENDED
    }

    enum class SessionError {
        AUTH_MISMATCH, PEER_BUSY, TRANSPORT_LOST, PEER_TIMEOUT,
        PROTOCOL_ERROR, MIC_UNAVAILABLE, PLAYBACK_UNAVAILABLE, PEER_REJECTED,
    }

    // ------------------------------------------------------- observable state

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
    /** True once beginHandshake ran for the current attempt (re-entrancy guard). */
    private var handshakeStarted = false
    private var selfSasConfirmed = false
    private var myCodecPref: Byte = CodecId.OPUS
    private var activeCodecId: Byte = CodecId.OPUS
    private var codec: AudioCodec? = null

    private var sendSeq: Long = 0
    private val replay = ReplayProtection()
    private val jitter = JitterBuffer()
    private val floor = FloorController(clock, this)

    /**
     * Monotonic session generation (HS-07). Incremented on every
     * attachTransport; delayed async callbacks (deadlines, handshake steps)
     * capture the generation and no-op if it changed, so a stale callback
     * from an old session can never mutate a newer one.
     */
    private var sessionGeneration = 0L
    private var handshakeDeadlineJob: Job? = null
    private var sasDeadlineJob: Job? = null

    private var lastPeerSeen: Long = 0
    private var lastLevelPush: Long = 0
    private var pttHeld = false

    // ------------------------------------------------------- outbound pipeline
    //
    // HS-02: all outgoing session messages are serialized through ONE bounded
    // channel with a SINGLE consumer. The consumer allocates the sequence
    // number, seals, and writes in deterministic FIFO order. This fixes:
    //  - non-atomic sendSeq++ raced by concurrent producers,
    //  - an unbounded coroutine launched per 20 ms audio frame,
    //  - non-deterministic wire order from coroutine scheduling.
    //
    // Overflow policy (documented): audio frames are dropped on a full queue
    // (bounded latency for real-time voice; counted in audioDropped);
    // control messages are never expected to overflow (low volume) and a
    // drop is logged as a warning.

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
        ) : OutboundMsg
    }

    private val outbound = Channel<OutboundMsg>(capacity = 256)
    private var senderJob: Job? = null
    private var audioDropped = 0L

    private fun startSender() {
        // Drain anything stale from a previous session before (re)starting.
        while (outbound.tryReceive().isSuccess) { /* drop */ }
        senderJob?.cancel()
        senderJob = scope.launch {
            for (msg in outbound) {
                val bytes: ByteArray = try {
                    when (msg) {
                        is OutboundMsg.Plain -> buildPlainFrame(msg.type, msg.payload)
                        is OutboundMsg.Sealed -> buildSealedFrame(msg)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "outbound build failed: ${e.message}")
                    continue
                }
                try {
                    val t = transport ?: break
                    t.sendFrame(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "outbound send failed: ${e.message}")
                    // Transport loss is surfaced via the transport watcher;
                    // keep the sender alive so teardown DISCONNECT can flush.
                }
            }
        }
    }

    private fun stopSender() {
        senderJob?.cancel(); senderJob = null
        while (outbound.tryReceive().isSuccess) { /* drop */ }
    }

    /** Enqueues a plaintext handshake frame (HELLO / KEY_EXCHANGE). */
    private fun enqueuePlain(type: Byte, payload: ByteArray) {
        if (!outbound.trySend(OutboundMsg.Plain(type, payload)).isSuccess) {
            Log.w(TAG, "outbound queue full; dropping plain frame type=$type")
        }
    }

    /** Enqueues a sealed control frame. */
    private fun enqueueControl(type: Byte, plaintext: ByteArray) {
        val k = keys ?: return
        val msg = OutboundMsg.Sealed(
            type = type,
            codecId = 0xFF.toByte(),
            sessionId = k.sessionId.copyOf(),
            key = k.controlKey,
            role = myRole,
            plaintext = plaintext,
        )
        if (!outbound.trySend(msg).isSuccess) {
            Log.w(TAG, "outbound queue full; dropping control frame type=$type")
        }
    }

    /** Enqueues an audio frame; drops on overflow (bounded latency). */
    private fun enqueueAudio(codecId: Byte, plaintext: ByteArray) {
        val k = keys ?: return
        val msg = OutboundMsg.Sealed(
            type = MessageType.AUDIO,
            codecId = codecId,
            sessionId = k.sessionId.copyOf(),
            key = k.audioKey,
            role = myRole,
            plaintext = plaintext,
        )
        if (!outbound.trySend(msg).isSuccess) audioDropped++
    }

    init {
        audio.pipelineListener = this
    }

    // ------------------------------------------------------- public control

    /** Outgoing session: connect the transport, then run the handshake. */
    fun startOutgoing(t: Transport, peer: PeerDevice, codecPref: Byte = CodecId.OPUS) {
        if (_phase.value != Phase.IDLE) {
            AppLog.log(TAG, "startOutgoing ignored: phase=${_phase.value}")
            return
        }
        AppLog.log(TAG, "outgoing session to ${peer.displayName} via ${t.type}")
        myCodecPref = codecPref
        attachTransport(t, peer.displayName)
        _phase.value = Phase.CONNECTING_TRANSPORT
        // The transport may already report CONNECTED (peer connected first);
        // the state-flow collector below only fires on *changes*.
        if (t.state.value == TransportState.CONNECTED) beginHandshake()
        scope.launch {
            try {
                t.connect(peer)
            } catch (e: Exception) {
                fail(SessionError.TRANSPORT_LOST)
            }
        }
    }

    /** Incoming session: the transport is already listening for a peer. */
    fun acceptIncoming(t: Transport, codecPref: Byte = CodecId.OPUS) {
        if (_phase.value != Phase.IDLE) {
            AppLog.log(TAG, "acceptIncoming ignored: phase=${_phase.value}")
            return
        }
        AppLog.log(TAG, "listening for incoming via ${t.type}")
        myCodecPref = codecPref
        attachTransport(t, "")
        _phase.value = Phase.CONNECTING_TRANSPORT
        // The transport may already report CONNECTED (peer connected first);
        // the state-flow collector below only fires on *changes*.
        if (t.state.value == TransportState.CONNECTED) beginHandshake()
        scope.launch {
            try {
                t.listen()
            } catch (e: Exception) {
                fail(SessionError.TRANSPORT_LOST)
            }
        }
    }

    private fun attachTransport(t: Transport, peerName: String) {
        detachTransport()
        sessionGeneration++
        startSender()
        transport = t
        _peerName.value = peerName
        _transportType.value = t.type
        _error.value = null
        transportWasConnected = false
        transportWatchJob = scope.launch {
            t.state.collect { st ->
                _transportState.value = st
                when (st) {
                    TransportState.CONNECTED -> {
                        transportWasConnected = true
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
        collectJob = scope.launch {
            t.incomingFrames.collect { bytes -> onRawFrame(bytes) }
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
        if (_phase.value == Phase.IDLE || _phase.value == Phase.ENDED) return
        _phase.value = Phase.ENDED
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
        keys?.wipe(); keys = null
        codec?.close(); codec = null
        myKeyPair = null
        peerPubBytes = null; peerNonce = null
        keyExchangeSent = false; keyConfirmSent = false
        peerHelloReceived = false
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
        _phase.value = Phase.IDLE
    }

    /**
     * Fast synchronous path for Service.onDestroy (HS-03): stops audio and
     * wipes keys immediately without depending on the service scope (which
     * onDestroy is about to cancel). Transport close runs best-effort.
     */
    fun close() {
        val wasActive = _phase.value != Phase.IDLE && _phase.value != Phase.ENDED
        _phase.value = Phase.ENDED
        cancelDeadlines()
        stopSender()
        try { audio.stopCapture() } catch (_: Exception) {}
        try { audio.stopPlayback() } catch (_: Exception) {}
        keys?.wipe(); keys = null
        myKeyPair = null
        scope.launch {
            // NonCancellable: serviceScope.cancel() in onDestroy must not
            // abort the transport disconnect.
            withContext(NonCancellable) {
                try { transport?.disconnect() } catch (_: Exception) {}
                detachTransport()
            }
            _phase.value = Phase.IDLE
        }
        if (!wasActive) {
            // Nothing was running; still detach for a clean slate.
            detachTransport()
            _phase.value = Phase.IDLE
        }
    }

    // ------------------------------------------------------- handshake

    private fun beginHandshake() {
        // Guarded: the transport may report CONNECTED twice (once via the
        // state flow, once via the post-attach check below).
        if (handshakeStarted) return
        handshakeStarted = true
        peerHelloReceived = false
        pendingKeyConfirmFrame = null
        _phase.value = Phase.HANDSHAKE
        startHandshakeDeadline()
        myKeyPair = SessionCrypto.generateEphemeralKeyPair()
        myPubBytes = SessionCrypto.encodePublicKey(myKeyPair!!.public)
        myNonce = SessionCrypto.newNonce16()
        sendSeq = 0
        lastPeerSeen = clock()
        // HELLO payload: protocol version (1) + codec preference (1)
        val hello = byteArrayOf(Protocol.VERSION, myCodecPref)
        enqueuePlain(MessageType.HELLO, hello)
        Log.i(TAG, "handshake started, HELLO sent")
        AppLog.log(
            TAG,
            "handshake started: HELLO sent (localProtocolVersion=${Protocol.VERSION.toInt() and 0xFF}, " +
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

    private fun onRawFrame(bytes: ByteArray) {
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
                endSession(SessionError.PEER_REJECTED, notifyPeer = false)
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
        val k = SessionCrypto.deriveKeys(secret, myNonce, peerNc)
        keys = k
        myRole = SessionCrypto.myRole(myPubBytes, peerPub)
        val transcript = SessionCrypto.transcriptHash(myPubBytes, peerPub, myNonce, peerNc)
        // Wipe our private key material as soon as the shared secret exists.
        myKeyPair = null
        secret.fill(0)
        if (!keyConfirmSent) {
            keyConfirmSent = true
            enqueueControl(MessageType.KEY_CONFIRM, transcript)
        }
        // The peer's KEY_CONFIRM may have arrived before derivation finished.
        pendingKeyConfirmFrame?.let { stashed ->
            pendingKeyConfirmFrame = null
            verifyPeerKeyConfirm(stashed)
        }
    }

    // KEY_CONFIRM frames that arrive before we derived are stashed here.
    private var pendingKeyConfirmFrame: Frame? = null

    private fun expectedTranscript(): ByteArray = SessionCrypto.transcriptHash(
        myPubBytes, peerPubBytes!!, myNonce, peerNonce!!,
    )

    private fun verifyPeerKeyConfirm(frame: Frame) {
        val k = keys ?: return
        val opened = try {
            openControl(frame, k)
        } catch (e: Exception) {
            Log.w(TAG, "KEY_CONFIRM failed authentication", e)
            endSession(SessionError.AUTH_MISMATCH, notifyPeer = true,
                disconnectReason = DisconnectReason.AUTH_MISMATCH)
            return
        }
        verifyKeyConfirm(opened, expectedTranscript())
    }

    private fun handleSasPhaseFrame(frame: Frame) {
        val k = keys ?: return
        when (frame.type) {
            MessageType.KEY_CONFIRM -> {
                // Late/duplicate confirm — verify again, harmless.
                verifyPeerKeyConfirm(frame)
            }
            MessageType.SAS_CONFIRM -> {
                // A malformed/forged control frame must not throw out of the
                // incomingFrames collector and silently stop all later traffic.
                val plaintext = try {
                    openControl(frame, k)
                } catch (e: Exception) {
                    _authFailures.value += 1
                    AppLog.log(TAG, "unauthenticated SAS_CONFIRM rejected (${e.javaClass.simpleName})")
                    return
                }
                if (plaintext.isNotEmpty()) {
                    fail(SessionError.PROTOCOL_ERROR, "SAS_CONFIRM payload must be empty")
                    return
                }
                _peerSasConfirmed.value = true
                lastPeerSeen = clock()
                maybeEnterSession()
            }
            MessageType.DISCONNECT -> {
                endSession(SessionError.PEER_REJECTED, notifyPeer = false)
            }
            else -> fail(
                SessionError.PROTOCOL_ERROR,
                "unexpected SAS-phase frame (type=${frame.type.toInt() and 0xFF}, " +
                    "payloadBytes=${frame.payload.size})",
            )
        }
    }

    private fun verifyKeyConfirmPayload(payload: ByteArray) {
        if (!MessageDigest.isEqual(payload, expectedTranscript())) {
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
        lastPeerSeen = clock()
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
        AppLog.log(TAG, "session established (codec=$activeCodecId)")
        livenessJob = scope.launch {
            while (isActive && _phase.value == Phase.IN_SESSION) {
                delay(1_000)
                floor.checkTimeouts()
                val now = clock()
                if (now - lastPeerSeen > Protocol.PEER_TIMEOUT_MS) {
                    endSession(SessionError.PEER_TIMEOUT, notifyPeer = false)
                    return@launch
                }
                if (now - lastPingSent > Protocol.PING_INTERVAL_MS) {
                    lastPingSent = now
                    val ping = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                        .putLong(now).array()
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
        // 3. Replay check — mutates the window only for authenticated frames.
        if (!replay.accept(frame.seq)) {
            _authFailures.value += 1 // duplicate / replayed / too old
            return
        }
        // 4. Authenticated liveness.
        lastPeerSeen = clock()
        // 5. Dispatch using the already-authenticated plaintext.
        when (frame.type) {
            MessageType.AUDIO -> {
                if (floor.holder != FloorController.Holder.PEER) {
                    return // not the floor holder — drop (already authenticated)
                }
                floor.onPeerAudio()
                jitter.push(frame.seq, plaintext)
            }
            MessageType.FLOOR_REQUEST -> {
                val granted = floor.onPeerRequest()
                _floorHolder.value = floor.holder
                val t = if (granted) MessageType.FLOOR_GRANT else MessageType.FLOOR_DENY
                val payload = if (granted) {
                    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(FloorController.PEER_LEASE_MS.toInt()).array()
                } else byteArrayOf(DenyReason.PEER_BUSY)
                enqueueControl(t, payload)
            }
            MessageType.FLOOR_GRANT -> {
                val lease = if (plaintext.size >= 4) {
                    ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN).int.toLong()
                } else FloorController.SELF_LEASE_REQUEST_MS
                floor.onGrant(lease)
            }
            MessageType.FLOOR_RELEASE -> {
                floor.onPeerRelease()
                _floorHolder.value = floor.holder
            }
            MessageType.FLOOR_DENY -> {
                val reason = plaintext.firstOrNull() ?: DenyReason.PEER_BUSY
                floor.onDeny(reason)
            }
            MessageType.PING -> {
                enqueueControl(MessageType.PONG, plaintext)
            }
            MessageType.PONG -> Unit // liveness already refreshed above
            MessageType.SAS_CONFIRM -> {
                _peerSasConfirmed.value = true
            }
            MessageType.DISCONNECT -> {
                val reason = plaintext.firstOrNull()
                    ?: DisconnectReason.USER_HANGUP
                val err = when (reason) {
                    DisconnectReason.AUTH_MISMATCH -> SessionError.AUTH_MISMATCH
                    else -> SessionError.PEER_REJECTED
                }
                endSession(err, notifyPeer = false)
            }
            MessageType.ERROR -> Log.w(TAG, "peer error frame")
            MessageType.KEY_CONFIRM -> {
                // Duplicate confirm inside the session — re-verify transcript.
                verifyKeyConfirmPayload(plaintext)
            }
            else -> Log.w(TAG, "unexpected ${frame.type} in session")
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
            val now = clock()
            if (now - lastLevelPush > 120) {
                lastLevelPush = now
                _voiceLevel.value = level
            }
            if (_phase.value == Phase.IN_SESSION) {
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
    // allocation is deterministic and race-free. Sequence numbers are uint32
    // and wrap after 2^32 frames; the wrap is safe because every frame is
    // AEAD-authenticated with a fresh random nonce (no nonce reuse) and the
    // replay window treats the wrap as a far jump (documented in
    // ReplayProtection).

    private fun nextSeq(): Long = (sendSeq++).and(0xFFFFFFFFL)

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
        if (detail.isNullOrBlank()) {
            AppLog.log(TAG, "session failed: $error")
        } else {
            AppLog.log(TAG, "session failed: $error ($detail)")
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
        if (_isTransmitting.value) {
            setPttPressed(false)
            _error.value = SessionError.MIC_UNAVAILABLE
        }
    }

    companion object {
        private const val TAG = "HamSedaSession"
    }
}
