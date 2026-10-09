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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        PROTOCOL_ERROR, MIC_UNAVAILABLE, PEER_REJECTED,
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
    private var selfSasConfirmed = false
    private var myCodecPref: Byte = CodecId.OPUS
    private var activeCodecId: Byte = CodecId.OPUS
    private var codec: AudioCodec? = null

    private var sendSeq: Long = 0
    private val replay = ReplayProtection()
    private val jitter = JitterBuffer()
    private val floor = FloorController(clock, this)

    private var lastPeerSeen: Long = 0
    private var lastLevelPush: Long = 0
    private var pttHeld = false

    init {
        audio.pipelineListener = this
    }

    // ------------------------------------------------------- public control

    /** Outgoing session: connect the transport, then run the handshake. */
    fun startOutgoing(t: Transport, peer: PeerDevice, codecPref: Byte = CodecId.OPUS) {
        if (_phase.value != Phase.IDLE) return
        myCodecPref = codecPref
        attachTransport(t, peer.displayName)
        _phase.value = Phase.CONNECTING_TRANSPORT
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
        if (_phase.value != Phase.IDLE) return
        myCodecPref = codecPref
        attachTransport(t, "")
        _phase.value = Phase.CONNECTING_TRANSPORT
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
        scope.launch { sendSealed(MessageType.SAS_CONFIRM, controlKeyOrThrow(), ByteArray(0)) }
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
                scope.launch {
                    sendSealed(
                        MessageType.FLOOR_REQUEST,
                        controlKeyOrThrow(),
                        ByteArray(0),
                    )
                }
            }
        } else {
            floor.cancelRequest()
            if (_isTransmitting.value) {
                stopTransmit()
                floor.releaseSelf() // SelfReleased event also stops; idempotent
                scope.launch {
                    sendSealed(
                        MessageType.FLOOR_RELEASE,
                        controlKeyOrThrow(),
                        ByteArray(0),
                    )
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** Ends the session, wipes keys, stops audio, disconnects transport. */
    fun endSession(
        error: SessionError? = null,
        notifyPeer: Boolean = true,
        disconnectReason: Byte = DisconnectReason.USER_HANGUP,
    ) {
        if (_phase.value == Phase.IDLE || _phase.value == Phase.ENDED) return
        _phase.value = Phase.ENDED
        val k = keys
        if (notifyPeer && k != null) {
            scope.launch {
                try {
                    sendSealed(MessageType.DISCONNECT, k.controlKey, byteArrayOf(disconnectReason))
                    delay(150) // best-effort flush before socket close
                } catch (_: Exception) {}
                scope.launch { teardown(error) }
            }
        } else {
            scope.launch { teardown(error) }
        }
    }

    private suspend fun teardown(error: SessionError?) {
        stopTransmit()
        audio.stopPlayback()
        livenessJob?.cancel(); livenessJob = null
        floor.reset()
        jitter.reset()
        replay.reset()
        keys?.wipe(); keys = null
        codec?.close(); codec = null
        myKeyPair = null
        peerPubBytes = null; peerNonce = null
        keyExchangeSent = false; keyConfirmSent = false
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

    fun close() {
        scope.launch { teardown(null) }
    }

    // ------------------------------------------------------- handshake

    private fun beginHandshake() {
        _phase.value = Phase.HANDSHAKE
        myKeyPair = SessionCrypto.generateEphemeralKeyPair()
        myPubBytes = SessionCrypto.encodePublicKey(myKeyPair!!.public)
        myNonce = SessionCrypto.newNonce16()
        sendSeq = 0
        lastPeerSeen = clock()
        // HELLO payload: protocol version (1) + codec preference (1)
        val hello = byteArrayOf(Protocol.VERSION, myCodecPref)
        scope.launch { sendPlain(MessageType.HELLO, hello) }
        Log.i(TAG, "handshake started, HELLO sent")
    }

    private fun onRawFrame(bytes: ByteArray) {
        val frame = try {
            Frame.decode(bytes, clock())
        } catch (e: FrameException) {
            Log.w(TAG, "dropping malformed frame: ${e.message}")
            if (_phase.value == Phase.IN_SESSION) _authFailures.value += 1
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
        when (frame.type) {
            MessageType.HELLO -> {
                if (frame.payload.size != 2 || frame.payload[0] != Protocol.VERSION) {
                    fail(SessionError.PROTOCOL_ERROR); return
                }
                val peerPref = frame.payload[1]
                activeCodecId = minOf(myCodecPref, peerPref)
                if (!keyExchangeSent) sendKeyExchange()
            }
            MessageType.KEY_EXCHANGE -> {
                if (peerPubBytes != null) return // duplicate; ignore
                try {
                    onKeyExchange(frame.payload)
                } catch (e: Exception) {
                    Log.w(TAG, "bad KEY_EXCHANGE", e)
                    fail(SessionError.PROTOCOL_ERROR); return
                }
                if (!keyExchangeSent) sendKeyExchange()
                deriveAndConfirm()
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
            else -> Log.w(TAG, "unexpected ${frame.type} during handshake")
        }
    }

    private fun sendKeyExchange() {
        val pub = myPubBytes
        val buf = ByteBuffer.allocate(2 + pub.size + 16).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(pub.size.toShort())
        buf.put(pub)
        buf.put(myNonce)
        keyExchangeSent = true
        scope.launch { sendPlain(MessageType.KEY_EXCHANGE, buf.array()) }
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
            scope.launch { sendSealed(MessageType.KEY_CONFIRM, k.controlKey, transcript) }
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
                openControl(frame, k) // authenticates; payload empty
                _peerSasConfirmed.value = true
                lastPeerSeen = clock()
                maybeEnterSession()
            }
            MessageType.DISCONNECT -> {
                endSession(SessionError.PEER_REJECTED, notifyPeer = false)
            }
            else -> Log.w(TAG, "unexpected ${frame.type} in SAS phase")
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
            Log.i(TAG, "key exchange verified; awaiting SAS comparison")
        }
    }

    private fun maybeEnterSession() {
        if (_phase.value != Phase.AWAITING_SAS_CONFIRM) return
        if (!selfSasConfirmed || !_peerSasConfirmed.value) return
        enterSession()
    }

    private fun enterSession() {
        _phase.value = Phase.IN_SESSION
        codec = AudioCodec.create(activeCodecId).also { it.reset() }
        jitter.reset()
        replay.reset()
        lastPeerSeen = clock()
        // Note: speakerphone routing follows the user's setting, applied by
        // VoiceService — the session never overrides it.
        audio.startPlayback(codec!!, jitter)
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
                    try {
                        sendSealed(MessageType.PING, controlKeyOrThrow(), ping)
                    } catch (_: Exception) {}
                }
            }
        }
        Log.i(TAG, "session established (codec=$activeCodecId)")
    }

    private var lastPingSent: Long = 0

    // ------------------------------------------------------- session frames

    private fun handleSessionFrame(frame: Frame) {
        val k = keys ?: return
        // Session binding: drop frames for a different/old session.
        if (!MessageDigest.isEqual(frame.sessionId, k.sessionId)) {
            _authFailures.value += 1
            return
        }
        if (!replay.accept(frame.seq)) {
            _authFailures.value += 1 // duplicate / replayed / too old
            return
        }
        lastPeerSeen = clock()
        try {
            when (frame.type) {
                MessageType.AUDIO -> {
                    if (floor.holder != FloorController.Holder.PEER) {
                        return // not the floor holder — drop
                    }
                    val pcm = SessionCrypto.open(k.audioKey, aadForReceive(frame), frame.payload)
                    floor.onPeerAudio()
                    jitter.push(frame.seq, pcm)
                }
                MessageType.FLOOR_REQUEST -> {
                    val granted = floor.onPeerRequest()
                    _floorHolder.value = floor.holder
                    val t = if (granted) MessageType.FLOOR_GRANT else MessageType.FLOOR_DENY
                    val payload = if (granted) {
                        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                            .putInt(FloorController.PEER_LEASE_MS.toInt()).array()
                    } else byteArrayOf(DenyReason.PEER_BUSY)
                    scope.launch { sendSealed(t, k.controlKey, payload) }
                }
                MessageType.FLOOR_GRANT -> {
                    val lease = if (frame.payload.size >= 4) {
                        ByteBuffer.wrap(openControl(frame, k)).order(ByteOrder.BIG_ENDIAN).int.toLong()
                    } else FloorController.SELF_LEASE_REQUEST_MS
                    floor.onGrant(lease)
                }
                MessageType.FLOOR_RELEASE -> {
                    openControl(frame, k)
                    floor.onPeerRelease()
                    _floorHolder.value = floor.holder
                }
                MessageType.FLOOR_DENY -> {
                    val reason = openControl(frame, k).firstOrNull() ?: DenyReason.PEER_BUSY
                    floor.onDeny(reason)
                }
                MessageType.PING -> {
                    val ts = openControl(frame, k)
                    scope.launch { sendSealed(MessageType.PONG, k.controlKey, ts) }
                }
                MessageType.PONG -> openControl(frame, k) // liveness proof
                MessageType.SAS_CONFIRM -> {
                    openControl(frame, k)
                    _peerSasConfirmed.value = true
                }
                MessageType.DISCONNECT -> {
                    val reason = openControl(frame, k).firstOrNull()
                        ?: DisconnectReason.USER_HANGUP
                    val err = when (reason) {
                        DisconnectReason.AUTH_MISMATCH -> SessionError.AUTH_MISMATCH
                        else -> SessionError.PEER_REJECTED
                    }
                    endSession(err, notifyPeer = false)
                }
                MessageType.ERROR -> Log.w(TAG, "peer error frame")
                MessageType.KEY_CONFIRM -> {
                    // Duplicate confirm inside the session — re-verify.
                    verifyPeerKeyConfirm(frame)
                }
                else -> Log.w(TAG, "unexpected ${frame.type} in session")
            }
        } catch (e: Exception) {
            // AEAD auth failure, malformed control payload, etc.
            Log.w(TAG, "dropping unauthenticated/malformed frame: ${e.message}")
            _authFailures.value += 1
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
                    scope.launch {
                        try {
                            sendSealed(
                                MessageType.FLOOR_RELEASE,
                                controlKeyOrThrow(),
                                ByteArray(0),
                            )
                        } catch (_: Exception) {}
                    }
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
        val ok = audio.startCapture(c) { encoded, level ->
            val now = clock()
            if (now - lastLevelPush > 120) {
                lastLevelPush = now
                _voiceLevel.value = level
            }
            scope.launch {
                try {
                    val frame = sealedFrame(
                        MessageType.AUDIO, c.codecId, keys!!.audioKey, encoded,
                    )
                    transport?.sendFrame(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "audio send failed: ${e.message}")
                }
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

    // ------------------------------------------------------- frame helpers

    private fun nextSeq(): Long = (sendSeq++).and(0xFFFFFFFFL)

    private fun sendPlain(type: Byte, payload: ByteArray) {
        val frame = Frame(
            type = type,
            sessionId = ByteArray(8),
            seq = nextSeq(),
            timestamp = clock(),
            codecId = 0xFF.toByte(),
            payload = payload,
        )
        scope.launch {
            try {
                transport?.sendFrame(frame.encode())
            } catch (e: Exception) {
                Log.w(TAG, "sendPlain failed: ${e.message}")
            }
        }
    }

    private suspend fun sendSealed(type: Byte, key: ByteArray, plaintext: ByteArray) {
        transport?.sendFrame(sealedFrame(type, 0xFF.toByte(), key, plaintext))
    }

    private fun sealedFrame(type: Byte, codecId: Byte, key: ByteArray, plaintext: ByteArray): ByteArray {
        val k = keys ?: throw IOException("no session keys")
        val frame = Frame(
            type = type,
            sessionId = k.sessionId,
            seq = nextSeq(),
            timestamp = clock(),
            codecId = codecId,
            payload = ByteArray(0), // replaced below
        )
        val aad = frame.headerBytes() + myRole
        val sealed = SessionCrypto.seal(key, aad, plaintext)
        return frame.copy(payload = sealed).encode()
    }

    private fun aadForReceive(frame: Frame): ByteArray =
        frame.headerBytes() + (1 - myRole).toByte()

    private fun openControl(frame: Frame, k: SessionKeys): ByteArray =
        SessionCrypto.open(k.controlKey, aadForReceive(frame), frame.payload)

    private fun controlKeyOrThrow(): ByteArray =
        keys?.controlKey ?: throw IOException("no session keys")

    private fun transportErrorToSession(e: TransportError?): SessionError = when (e) {
        is TransportError.PermissionDenied -> SessionError.TRANSPORT_LOST
        else -> SessionError.TRANSPORT_LOST
    }

    private fun fail(error: SessionError) {
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
