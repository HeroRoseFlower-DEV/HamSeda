package com.hamseda.walkie.messaging

import android.content.Context
import android.util.Log
import com.hamseda.walkie.data.ProfilePhotoStore
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.data.msg.MessageDao
import com.hamseda.walkie.data.msg.MessageDatabase
import com.hamseda.walkie.data.msg.MessageEntity
import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.data.msg.OutboxEntity
import com.hamseda.walkie.data.msg.OutboxItem
import com.hamseda.walkie.data.msg.PeerEntity
import com.hamseda.walkie.proto.MessageType
import com.hamseda.walkie.proto.MessagingCodec
import com.hamseda.walkie.proto.MsgCodecException
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.proto.Uuid7
import com.hamseda.walkie.session.SessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline text messaging over the existing E2E-encrypted session pipes
 * (Bluetooth Classic RFCOMM / Wi-Fi Direct). Briar-style architecture:
 * the encrypted session is just a pipe; reliability semantics live here.
 *
 * - **Send:** persist + outbox row in ONE Room transaction → drain when a
 *   transport is up → states QUEUED → SENT → DELIVERED (ACK) → READ.
 * - **Receive:** idempotent inbox (INSERT OR IGNORE) → persist → batched ACK.
 * - **Reconnect:** MSG_HELLO{lastSeenSeq} exchange; outbox replays seq >
 *   lastSeen. Replay protection comes from the session's per-frame sequence
 *   window — every messaging frame passes through it before reaching here.
 * - **Typing:** ephemeral, never persisted, ≤1 frame/3s, 8s TTL, only while
 *   a chat is open (which implies the app is foregrounded).
 */
class MessagingEngine(
    appContext: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val dao: MessageDao = MessageDatabase.get(appContext).messageDao()
    private val photoStore = ProfilePhotoStore(appContext)

    private var manager: SessionManager? = null
    private val sendMutex = Mutex()

    /** Peer of the live session (identity fingerprint, else transport name). */
    private val currentPeerId = MutableStateFlow("")

    /** Public view of the live session peer; empty when offline. */
    val livePeerId: StateFlow<String> = currentPeerId.asStateFlow()

    /** Display name of the live peer, or empty when offline. */
    val livePeerName: StateFlow<String> =
        currentPeerId.map { if (it.isEmpty()) "" else peerDisplayName(it) }
            .stateIn(scope, SharingStarted.Eagerly, "")

    /** Chat currently open in the UI; null when no chat is visible. */
    private val activePeer = MutableStateFlow<String?>(null)

    /**
     * Per-peer HELLO cursors: how far THIS peer has seen of our outbox.
     * Strictly per-peer — senderSeq spaces are independent per peer, so a
     * single global cursor lets one peer's HELLO silently "deliver" (drop)
     * another peer's queued rows (round-6 C1). Skip re-sending seq <= the
     * cursor on drain.
     */
    private val helloLastSeenSeq = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------ observables

    data class ConversationView(
        val peerId: String,
        val peerName: String,
        val lastBody: String,
        val lastTimestamp: Long,
        val lastIsFromMe: Boolean,
        val unread: Int,
    )

    data class MessageView(
        val id: String,
        val isFromMe: Boolean,
        val body: String,
        val timestamp: Long,
        val state: Int,
    )

    /** Conversations ordered by recency, with unread badges. */
    val conversations: StateFlow<List<ConversationView>> =
        combine(
            dao.observeLatestPerPeer(),
            dao.observeUnread(),
        ) { latest, unreadRows ->
            val unreadByPeer = unreadRows.associate { it.peerId to it.unread }
            latest.map { last ->
                ConversationView(
                    peerId = last.peerId,
                    peerName = last.peerName.ifEmpty { shortId(last.peerId) },
                    lastBody = last.body,
                    lastTimestamp = last.timestamp,
                    lastIsFromMe = last.isFromMe,
                    unread = unreadByPeer[last.peerId] ?: 0,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val unreadTotal: StateFlow<Int> =
        dao.observeUnread().map { rows -> rows.sumOf { it.unread } }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** True while the peer's typing indicator is live (open chat only). */
    private val _peerTyping = MutableStateFlow(false)
    val peerTyping: StateFlow<Boolean> = _peerTyping.asStateFlow()
    private var typingTimeoutJob: Job? = null
    /** Jobs launched by attach(); cancelled in detach() so a stale manager is never observed. */
    private val attachJobs = mutableListOf<Job>()

    /** Whether any encrypted session is currently up. */
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    fun observeMessages(peerId: String): Flow<List<MessageView>> =
        dao.observeConversation(peerId).map { list ->
            list.map {
                MessageView(it.id, it.isFromMe, it.body, it.timestamp, it.state)
            }
        }

    // ---------------------------------------------------------------- attach

    fun attach(manager: SessionManager) {
        if (this.manager === manager) return
        detach()
        this.manager = manager
        manager.messagingListener = SessionManager.MessagingFrameListener { type, plaintext ->
            onFrame(type, plaintext)
        }
        startFrameProcessor()
        startAckBatcher()
        attachJobs += scope.launch {
            manager.phase.collect { phase ->
                val up = phase == SessionManager.Phase.IN_SESSION
                _online.value = up
                if (up) {
                    refreshPeerId()
                    // Fresh HELLO exchange on every session: forget this
                    // peer's old cursor until its HELLO re-advertises it.
                    helloLastSeenSeq.remove(currentPeerId.value)
                    sendHello()
                    sendProfile()
                    drainOutbox()
                } else {
                    _peerTyping.value = false
                    typingTimeoutJob?.cancel()
                }
            }
        }
        // Periodic retry sweep for backoff entries while online.
        attachJobs += scope.launch {
            while (true) {
                delay(RETRY_SWEEP_MS)
                if (_online.value) drainOutbox()
            }
        }
    }

    fun detach() {
        attachJobs.forEach { it.cancel() }
        attachJobs.clear()
        manager?.messagingListener = null
        manager = null
        _online.value = false
        // Honor the livePeerId/livePeerName contract ("empty when offline"):
        // otherwise they keep publishing the last fingerprint/name after
        // every session end without an immediate re-attach.
        currentPeerId.value = ""
        // Typing state belongs to the dead session: the phase collector
        // that normally clears it is cancelled by detach() itself, so
        // without this the indicator could outlive the session to its TTL.
        _peerTyping.value = false
        typingTimeoutJob?.cancel()
        helloLastSeenSeq.clear()
        // Drop partial chunk reassemblies: they belong to the old session's
        // key material and must never complete under a new one.
        reassembly.clear()
        photoReassembly.clear()
        // Drain both queues (round-7 I1): entries still queued here were
        // delivered by the dead session and must never be processed or
        // flushed under the next one. The consumer/batcher jobs keep
        // running (they live for the engine's lifetime and must stay
        // single — two frame processors would break wire order), but
        // anything they already dequeued is stopped by the enqueue-time
        // peer tag check, and anything enqueued from now on tags the new
        // peer. Plaintexts are wiped, as the consumer would have done.
        while (true) {
            val stale = frameChannel.tryReceive().getOrNull() ?: break
            stale.plaintext.fill(0)
        }
        while (ackChannel.tryReceive().isSuccess) {
            // ACK ids of the dead session are dropped: their senders
            // retry, and a fresh session re-ACKs on redelivery.
        }
    }

    // ------------------------------------------------------------------ send

    /**
     * Queues a text for [peerId]. Returns the message id. Throws
     * [IllegalArgumentException] for blank or oversize input.
     */
    suspend fun sendText(peerId: String, text: String): String {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty message" }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= Protocol.MSG_MAX_BYTES) {
            "message exceeds ${Protocol.MSG_MAX_BYTES} bytes"
        }
        val msgId = Uuid7.generate()
        val now = System.currentTimeMillis()
        val entity: MessageEntity
        sendMutex.withLock {
            val seq = (dao.maxOwnSeq(peerId) ?: 0L) + 1
            entity = MessageEntity(
                id = msgId.toString(),
                peerId = peerId,
                peerName = peerDisplayName(peerId),
                isFromMe = true,
                senderSeq = seq,
                body = trimmed,
                timestamp = now,
                state = MessageState.QUEUED,
            )
            dao.insertOutgoing(entity, OutboxEntity(msgId = msgId.toString()))
        }
        drainOutbox()
        return entity.id
    }

    /**
     * Drains due outbox entries for the CONNECTED peer only, while its
     * session is up. Serialized. Strictly scoped: a row queued for peer A
     * must never be sent on peer B's session (confidentiality) nor be
     * dropped by B's HELLO cursor (silent data loss) — round-6 C1.
     */
    private suspend fun drainOutbox() {
        val mgr = manager ?: return
        if (!_online.value) return
        val peerId = currentPeerId.value
        if (peerId.isEmpty()) return
        sendMutex.withLock {
            // Re-validate after acquiring the mutex: the snapshot above
            // was taken before any wait, and the session may have been
            // replaced while this drain queued behind another one.
            if (manager !== mgr || !_online.value || currentPeerId.value != peerId) return
            val now = System.currentTimeMillis()
            val due = dao.dueOutbox(peerId, now, DRAIN_BATCH)
            val lastSeen = helloLastSeenSeq[peerId] ?: 0L
            for (item in due) {
                // Re-validate the LIVE session per item (round-7 M3):
                // a drain pass runs up to DRAIN_BATCH items × up to 16
                // chunks, and the session can drop — and a new session
                // to a DIFFERENT peer come up on the same manager —
                // while it runs. The pre-loop snapshot cannot see that;
                // without this check the remaining rows of the old peer
                // would be sent on the new peer's live session.
                if (manager !== mgr || !_online.value || currentPeerId.value != peerId) break
                // Defense in depth: the query already filters by peer,
                // but never let a row cross sessions even if it regresses.
                if (item.peerId != peerId) continue
                if (item.senderSeq <= lastSeen) {
                    // This peer already saw it (its HELLO cursor); delivered.
                    dao.ackDelivered(item.msgId)
                    continue
                }
                val ok = sendItem(mgr, item, peerId)
                if (ok) {
                    // Guarded: a resend racing the ACK/read receipt must not
                    // downgrade DELIVERED/READ back to SENT.
                    dao.advanceToSent(item.msgId)
                    // At-least-once, but with a bounded lifetime: attempts
                    // counts EVERY send, including successful-but-unacked
                    // resends (round-7 L8). A peer that never ACKs (e.g.
                    // it crashed before persisting) would otherwise keep
                    // this row resending every RESEND_DELAY_MS in every
                    // future session, forever, never reaching FAILED.
                    val attempts = item.attempts + 1
                    if (attempts > MAX_ATTEMPTS) {
                        dao.failOutbox(item.msgId)
                    } else {
                        // Keep it queued until the ACK arrives, but pace
                        // resends instead of hammering every drain pass.
                        dao.scheduleOutboxRetry(
                            item.msgId,
                            attempts,
                            now + RESEND_DELAY_MS,
                        )
                    }
                } else {
                    val attempts = item.attempts + 1
                    if (attempts > MAX_ATTEMPTS) {
                        dao.failOutbox(item.msgId)
                    } else {
                        dao.scheduleOutboxRetry(
                            item.msgId,
                            attempts,
                            now + OutboxBackoff.delayMs(attempts),
                        )
                    }
                    // Transport backpressure: stop this pass, retry later.
                    break
                }
            }
        }
    }

    private fun sendItem(
        mgr: SessionManager,
        item: OutboxItem,
        peerId: String,
    ): Boolean {
        val id = try {
            UUID.fromString(item.msgId)
        } catch (e: IllegalArgumentException) {
            return false
        }
        // Single frame when it fits; chunked otherwise.
        val single = try {
            MessagingCodec.encodeText(id, item.senderSeq, item.timestamp, item.body)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (single != null) {
            return mgr.sendMessagingFrame(MessageType.MSG, single)
        }
        val chunks = try {
            MessagingCodec.chunk(id, item.senderSeq, item.timestamp, item.body)
        } catch (e: Exception) {
            Log.w(TAG, "chunk encode failed", e)
            return false
        }
        for (payload in chunks) {
            // A multi-chunk send spans real time: if the session dropped
            // (or was replaced by another peer's) mid-message, stop —
            // the remaining chunks must not land on the new session
            // (round-7 M3). The row stays queued and retries whole.
            if (manager !== mgr || currentPeerId.value != peerId) return false
            if (!mgr.sendMessagingFrame(MessageType.CHUNK, payload)) return false
        }
        return true
    }

    // --------------------------------------------------------------- receive

    /**
     * Inbound frames queue here and are processed strictly in wire order
     * by ONE consumer. Launching a coroutine per frame let the dispatcher
     * process them out of order: a PROFILE_PHOTO chunk could snapshot the
     * peer row before its PROFILE frame landed (stale expectedHash → the
     * new photo silently failed verification), chunks arriving before a
     * first-seen peer's PROFILE were dropped outright, and bursts could
     * insert out of order.
     *
     * Every entry is TAGGED with the peer whose session delivered it,
     * captured at enqueue time (round-7 I1): handlers must attribute by
     * this tag, never by the processing-time [currentPeerId]. Without
     * the tag, frames queued by session A were processed after a swap
     * under session B — A's text landed in B's chat, A's PROFILE
     * overwrote B's row, A's ACK ids were flushed to B. The consumer
     * additionally drops a frame whose tag no longer matches the live
     * peer, and detach() drains both channels, so a stale entry cannot
     * survive into the next session even if it was enqueued mid-teardown.
     *
     * Bounded (round-7 L5): an UNLIMITED queue let a fast peer grow
     * plaintext copies without limit while the DB-bound consumer fell
     * behind. The cap is a high-water safeguard, not flow control —
     * 1024 frames ≈ 2 MB worst case — and a full queue drops + logs
     * rather than ever blocking the session's reader thread.
     */
    private data class TaggedFrame(val type: Byte, val peerId: String, val plaintext: ByteArray)

    private val frameChannel = Channel<TaggedFrame>(FRAME_CHANNEL_CAPACITY)

    @Volatile
    private var frameProcessorStarted = false

    private fun startFrameProcessor() {
        if (frameProcessorStarted) return
        frameProcessorStarted = true
        scope.launch {
            for (frame in frameChannel) {
                // Session-swap guard: a frame delivered by a session that
                // has since ended (or been replaced by another peer's) is
                // stale — drop it instead of processing under the new
                // session. If the SAME peer reconnected, the tag matches
                // and processing is semantically correct.
                if (frame.peerId != currentPeerId.value) {
                    frame.plaintext.fill(0)
                    continue
                }
                processFrame(frame.type, frame.peerId, frame.plaintext)
            }
        }
    }

    private fun onFrame(type: Byte, plaintext: ByteArray) {
        // Enqueue only — never blocks the session's reader thread. The
        // peer tag is captured NOW: by processing time the live session
        // may belong to someone else (round-7 I1).
        val peerId = currentPeerId.value
        if (peerId.isEmpty()) {
            // No live session to attribute this frame to; it cannot be
            // processed safely under any later session.
            plaintext.fill(0)
            return
        }
        if (!frameChannel.trySend(TaggedFrame(type, peerId, plaintext)).isSuccess) {
            // Queue full (consumer hopelessly behind): drop + wipe rather
            // than block the reader thread (round-7 L5).
            Log.w(TAG, "frame queue full; dropping frame type=$type")
            plaintext.fill(0)
        }
    }

    private suspend fun processFrame(type: Byte, peerId: String, plaintext: ByteArray) {
        try {
            when (type) {
                MessageType.MSG -> onTextFrame(MessagingCodec.decodeText(plaintext), peerId)
                MessageType.CHUNK -> onChunkFrame(MessagingCodec.decodeChunk(plaintext), peerId)
                MessageType.MSG_ACK -> onAck(MessagingCodec.decodeId(plaintext), peerId)
                MessageType.MSG_READ -> onRead(MessagingCodec.decodeId(plaintext), peerId)
                MessageType.TYPING -> onTyping(MessagingCodec.decodeTyping(plaintext), peerId)
                MessageType.MSG_HELLO -> onHello(MessagingCodec.decodeHello(plaintext), peerId)
                MessageType.PROFILE -> onProfileFrame(MessagingCodec.decodeProfile(plaintext), peerId)
                MessageType.PROFILE_PHOTO -> onPhotoChunk(MessagingCodec.decodePhotoChunk(plaintext), peerId)
                else -> Unit
            }
        } catch (e: MsgCodecException) {
            Log.w(TAG, "dropping malformed messaging frame", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation: the consumer must actually stop
            // when its scope goes away.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "messaging frame handling failed", e)
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun onTextFrame(decoded: MessagingCodec.DecodedText, peerId: String) {
        val storedName = dao.getPeer(peerId)?.displayName.orEmpty()
        val entity = MessageEntity(
            id = decoded.msgId.toString(),
            peerId = peerId,
            peerName = storedName.ifEmpty { peerDisplayName(peerId) },
            isFromMe = false,
            senderSeq = decoded.senderSeq,
            body = decoded.text,
            timestamp = decoded.timestamp,
            state = MessageState.DELIVERED,
        )
        // Idempotent: duplicates are ACKed but not stored twice.
        dao.insertIncomingDedup(entity)
        if (!ackChannel.trySend(peerId to decoded.msgId.toString()).isSuccess) {
            // A lost ACK is harmless (the sender retries); never block
            // the frame consumer on a full queue (round-7 L5).
            Log.w(TAG, "ack queue full; dropping ack for ${decoded.msgId}")
        }
        if (activePeer.value == peerId) {
            maybeSendReadReceipt(decoded.msgId.toString(), peerId)
        }
    }

    // ---------------------------------------------------------- reassembly

    private data class Reassembly(
        val total: Int,
        val parts: Array<ByteArray?>,
        val senderSeq: Long,
        val timestamp: Long,
        var deadline: Long,
    )

    private val reassembly = ConcurrentHashMap<String, Reassembly>()

    private suspend fun onChunkFrame(chunk: MessagingCodec.DecodedChunk, peerId: String) {
        val key = chunk.msgId.toString()
        val now = System.currentTimeMillis()
        // Prune stale reassemblies on every chunk (cheap: usually 0-1 entries).
        reassembly.entries.removeIf { it.value.deadline < now }
        // Bound the number of DISTINCT in-flight messages, not just the
        // chunks per message (round-7 M5): a peer streaming first-chunks
        // for fresh random ids would otherwise accumulate entries until
        // OOM — expiry pruning alone never fires while chunks keep
        // arriving. Evict the entry closest to its deadline.
        if (!reassembly.containsKey(key) && reassembly.size >= MAX_REASSEMBLY_ENTRIES) {
            reassembly.entries.minByOrNull { it.value.deadline }
                ?.let { reassembly.remove(it.key) }
        }
        val state = reassembly.getOrPut(key) {
            Reassembly(
                total = chunk.total,
                parts = arrayOfNulls(chunk.total),
                senderSeq = chunk.senderSeq,
                timestamp = chunk.timestamp,
                deadline = now + Protocol.MSG_REASSEMBLY_TIMEOUT_MS,
            )
        }
        // Every chunk of one message must agree with the first chunk's
        // header (round-7 L6): text has no final integrity check (photos
        // have SHA-256), so without this, two interleaved streams sharing
        // a msgId would silently merge into one corrupted message. A
        // mismatch means the entry is poisoned — drop it entirely.
        if (state.total != chunk.total ||
            state.senderSeq != chunk.senderSeq ||
            state.timestamp != chunk.timestamp
        ) {
            reassembly.remove(key)
            return
        }
        state.parts[chunk.index] = chunk.bytes
        state.deadline = now + Protocol.MSG_REASSEMBLY_TIMEOUT_MS
        if (state.parts.all { it != null }) {
            reassembly.remove(key)
            var size = 0
            for (p in state.parts) size += p!!.size
            if (size > Protocol.MSG_MAX_BYTES) return
            val bytes = ByteArray(size)
            var off = 0
            for (p in state.parts) {
                p!!.copyInto(bytes, off)
                off += p.size
            }
            onTextFrame(
                MessagingCodec.DecodedText(chunk.msgId, state.senderSeq, state.timestamp, bytes.toString(Charsets.UTF_8)),
                peerId,
            )
        }
    }

    // ------------------------------------------------------ acks and receipts

    /**
     * Coalesces ACK timing: collects for ~200ms then flushes, one frame
     * per id. Entries carry the peer whose session delivered the message
     * (round-7 I1): at flush time an entry is sent only if that peer is
     * STILL the connected one — otherwise the id (a UUIDv7, embedding a
     * timestamp) would leak to a different peer, who never sent the
     * message. Bounded like the frame queue (round-7 L5).
     */
    private val ackChannel = Channel<Pair<String, String>>(ACK_CHANNEL_CAPACITY)

    @Volatile
    private var ackBatcherStarted = false

    private fun startAckBatcher() {
        if (ackBatcherStarted) return
        ackBatcherStarted = true
        scope.launch {
            while (true) {
                val first = ackChannel.receive()
                val batch = mutableListOf(first)
                delay(ACK_BATCH_MS)
                while (true) {
                    batch.add(ackChannel.tryReceive().getOrNull() ?: break)
                }
                // A lost ACK is harmless: the sender retries until it gets one.
                val mgr = manager ?: continue
                for ((peerTag, id) in batch) {
                    if (peerTag != currentPeerId.value) continue
                    runCatching {
                        mgr.sendMessagingFrame(
                            MessageType.MSG_ACK,
                            MessagingCodec.encodeId(UUID.fromString(id)),
                        )
                    }.onFailure { Log.w(TAG, "ack send failed", it) }
                }
            }
        }
    }

    private suspend fun onAck(msgId: UUID, peerId: String) {
        val id = msgId.toString()
        // Scoped to the peer whose session delivered this ACK: it may
        // only move messages that belong to that peer's conversation.
        // (The DAO already guards direction/state; this stops a peer
        // from acking ids of a conversation it is not part of.)
        val msg = dao.getMessage(id) ?: return
        if (msg.peerId != peerId) return
        // Advance forward only: never downgrade a READ back to DELIVERED.
        dao.ackDelivered(id)
    }

    private suspend fun onRead(msgId: UUID, peerId: String) {
        val id = msgId.toString()
        // Same scoping as onAck: read receipts apply only to the sender's
        // own conversation with the peer that sent the receipt.
        val msg = dao.getMessage(id) ?: return
        if (msg.peerId != peerId) return
        // Guarded: a remote peer may only advance our own outgoing messages
        // forward to READ — never incoming, FAILED, or unknown ids.
        dao.markReadByPeer(id)
    }

    /**
     * Marks this peer's incoming messages read and, if the user allows read
     * receipts, tells the peer about each. Called only while the chat thread
     * is visibly open — never from the background or notifications.
     */
    suspend fun markReadVisible(peerId: String) {
        val ids = dao.unreadIncomingIds(peerId)
        if (ids.isNotEmpty()) {
            // Local read state is UNCONDITIONAL: the user has seen these
            // messages. The receipts setting governs only telling the peer —
            // conflating the two left this peer's unread badges stuck
            // forever whenever receipts were off.
            //
            // Mark exactly the snapshot (round-7 L4): a message inserted
            // between the snapshot and this call must not be marked read
            // locally when no receipt will be sent for it until the next
            // visible trigger.
            dao.markReadByIds(ids)
        }
        if (ids.isEmpty()) return
        if (!settings.readReceiptsFlow.first()) return
        // Receipts go only over the session WITH THIS PEER. Sending another
        // conversation's message ids to whoever is connected leaks them
        // (UUIDv7s embed timestamps) and the intended peer never receives
        // its receipts.
        if (currentPeerId.value != peerId) return
        val mgr = manager ?: return
        for (id in ids) {
            runCatching {
                mgr.sendMessagingFrame(
                    MessageType.MSG_READ,
                    MessagingCodec.encodeId(UUID.fromString(id)),
                )
            }.onFailure { Log.w(TAG, "read receipt send failed", it) }
        }
    }

    private suspend fun maybeSendReadReceipt(msgId: String, peerId: String) {
        // The chat is visibly open on this message: it is read locally no
        // matter the receipts setting; only the wire receipt is gated.
        dao.setState(msgId, MessageState.READ)
        if (!settings.readReceiptsFlow.first()) return
        // Re-check the peer AFTER the DataStore suspension (round-7 M6,
        // the same guard markReadVisible has): the session may have
        // switched to another peer while suspended, and sending this
        // receipt then would leak this conversation's message id — a
        // UUIDv7 — to the wrong peer.
        if (currentPeerId.value != peerId) return
        val mgr = manager ?: return
        runCatching {
            mgr.sendMessagingFrame(
                MessageType.MSG_READ,
                MessagingCodec.encodeId(UUID.fromString(msgId)),
            )
        }.onFailure { Log.w(TAG, "read receipt send failed", it) }
    }

    // ---------------------------------------------------------------- typing

    private var lastTypingSentAt = 0L
    private val typingMutex = Mutex()

    /**
     * Called by the chat UI as the user types. Rate-limited to one frame per
     * 3 s; only sent while a chat is open (foreground) and the session is up.
     */
    fun setTyping(typing: Boolean) {
        scope.launch {
            // Checks FIRST (round-7 L10): a typing frame concerns only
            // the CONNECTED peer's own chat — typing in an offline
            // conversation (A) while a session with B is up must not show
            // B a typing indicator for a chat B is not part of. Running
            // these checks after the rate limiter (as before) also let
            // stop frames and other chats' typing stamp the timestamp,
            // suppressing the first real typing frame of the connected
            // chat for up to TYPING_MIN_INTERVAL_MS.
            val active = activePeer.value
            if (active == null || active != currentPeerId.value || !_online.value) {
                return@launch
            }
            val mgr = manager ?: return@launch
            if (typing) {
                // Only a typing=true frame that is actually sent consumes
                // rate-limit budget; stop frames always pass through.
                val send = typingMutex.withLock {
                    val now = System.currentTimeMillis()
                    if (now - lastTypingSentAt < TYPING_MIN_INTERVAL_MS) {
                        false
                    } else {
                        lastTypingSentAt = now
                        true
                    }
                }
                if (!send) return@launch
            }
            runCatching {
                mgr.sendMessagingFrame(
                    MessageType.TYPING,
                    MessagingCodec.encodeTyping(typing),
                )
            }.onFailure { Log.w(TAG, "typing send failed", it) }
        }
    }

    private fun onTyping(typing: Boolean, peerId: String) {
        // Only surface for the open chat, and only when it is the chat of
        // the peer whose session delivered the frame (the enqueue-time
        // tag, round-7 I1) — never leak a live peer's typing into another
        // conversation's thread. Not persisted.
        if (activePeer.value == null || activePeer.value != peerId) return
        typingTimeoutJob?.cancel()
        if (typing) {
            _peerTyping.value = true
            typingTimeoutJob = scope.launch {
                delay(TYPING_TTL_MS)
                _peerTyping.value = false
            }
        } else {
            _peerTyping.value = false
        }
    }

    /** Which chat the UI currently shows; drives typing + read routing. */
    fun setActivePeer(peerId: String?) {
        activePeer.value = peerId
        if (peerId == null) {
            _peerTyping.value = false
            typingTimeoutJob?.cancel()
        }
    }

    // ----------------------------------------------------------------- hello

    private fun sendHello() {
        scope.launch {
            val peerId = currentPeerId.value
            if (peerId.isEmpty()) return@launch
            // Advertise a contiguous WATERMARK, not the max seq: a gap
            // below the max (a receive lost in a crash/race window) must
            // not make the sender drop a message we never actually got.
            val lastSeen = contiguousWatermark(dao.receivedSeqs(peerId))
            runCatching {
                manager?.sendMessagingFrame(
                    MessageType.MSG_HELLO,
                    MessagingCodec.encodeHello(lastSeen),
                )
            }.onFailure { Log.w(TAG, "hello send failed", it) }
        }
    }

    private fun onHello(lastSeenSeq: Long, peerId: String) {
        // The cursor describes the SENDING peer's view of our outbox —
        // store it under the peer whose session delivered the HELLO
        // (the enqueue-time tag, round-7 I1; round-6 C1).
        val prev = helloLastSeenSeq[peerId] ?: 0L
        if (lastSeenSeq > prev) helloLastSeenSeq[peerId] = lastSeenSeq
        scope.launch { drainOutbox() }
    }

    // --------------------------------------------------------------- profile

    /**
     * Sends our PROFILE (name + photo hash) on session setup, then the
     * photo chunks if a photo is set (v1 rule: every session up, <= 12
     * frames — cheap on both transports). All on the sealed control
     * channel; never plaintext.
     */
    private fun sendProfile() {
        scope.launch {
            val mgr = manager ?: return@launch
            val name = settings.profileNameFlow.first()
            // Locked read (round-7 L7): a concurrent photo re-import
            // must not let us hash + send a half-written JPEG whose
            // torn bytes still verify against their own hash.
            val photoBytes = photoStore.readOwnPhotoBytes()
            val hash = if (photoBytes != null) photoStore.sha256(photoBytes)
            else ByteArray(Protocol.PROFILE_HASH_BYTES)
            val payload = runCatching {
                MessagingCodec.encodeProfile(name, hash)
            }.getOrElse {
                Log.w(TAG, "profile encode failed", it)
                return@launch
            }
            if (!mgr.sendMessagingFrame(MessageType.PROFILE, payload)) {
                Log.w(TAG, "profile send failed")
                return@launch
            }
            if (photoBytes != null) sendOwnPhoto(mgr, photoBytes)
        }
    }

    private fun sendOwnPhoto(mgr: SessionManager, photoBytes: ByteArray) {
        if (photoBytes.size > Protocol.PROFILE_PHOTO_MAX_BYTES) {
            Log.w(TAG, "own photo over cap; not sent")
            return
        }
        val chunks = runCatching {
            MessagingCodec.chunkPhoto(Uuid7.generate(), photoBytes)
        }.getOrElse {
            Log.w(TAG, "photo chunk failed", it)
            return
        }
        for (payload in chunks) {
            if (!mgr.sendMessagingFrame(MessageType.PROFILE_PHOTO, payload)) {
                Log.w(TAG, "photo chunk send failed; stopping")
                return
            }
        }
    }

    /**
     * PROFILE receipt: TOFU — first-seen peer is saved; the latest received
     * name always wins. Photo bytes arrive separately and are only stored
     * after hash verification (see [onPhotoChunk]).
     */
    private suspend fun onProfileFrame(profile: MessagingCodec.DecodedProfile, peerId: String) {
        val now = System.currentTimeMillis()
        val existing = dao.getPeer(peerId)
        val hashChanged = existing == null ||
            !existing.photoHash.contentEquals(profile.photoHash)
        dao.upsertPeer(
            PeerEntity(
                fingerprint = peerId,
                displayName = profile.name,
                photoHash = profile.photoHash,
                photoPath = if (hashChanged) null else existing?.photoPath,
                lastSeen = now,
            ),
        )
        if (hashChanged && !profile.hasPhoto) {
            // Peer removed their photo: drop the stale file. AFTER the
            // upsert on purpose: the upsert stores the new (empty) hash
            // and nulls photoPath first, so a photo transfer completing
            // concurrently can no longer re-link the old file afterwards
            // (its conditional link matches the OLD hash, just replaced) —
            // deleting first used to leave exactly that file orphaned.
            photoStore.removePeerPhoto(peerId)
        }
        // Keep the denormalized names on past messages in sync.
        dao.updatePeerName(peerId, profile.name)
        refreshPeerProfile()
    }

    private data class PhotoReassembly(
        val total: Int,
        val parts: Array<ByteArray?>,
        val expectedHash: ByteArray,
        var deadline: Long,
    )

    private val photoReassembly = ConcurrentHashMap<String, PhotoReassembly>()

    /**
     * PROFILE_PHOTO chunk receipt. Reassembles with a 10-minute deadline;
     * on completion verifies SHA-256 against the hash from the peer's
     * PROFILE frame before saving to app-private storage.
     */
    private suspend fun onPhotoChunk(chunk: MessagingCodec.DecodedPhotoChunk, peerId: String) {
        val peer = dao.getPeer(peerId) ?: return
        val key = "${peerId}:${chunk.photoId}"
        val now = System.currentTimeMillis()
        photoReassembly.entries.removeIf { it.value.deadline < now }
        // Entry-count bound, same as text reassembly (round-7 M5): a
        // photo entry can hold ~44 KB, so the cap is tighter.
        if (!photoReassembly.containsKey(key) && photoReassembly.size >= MAX_PHOTO_REASSEMBLY_ENTRIES) {
            photoReassembly.entries.minByOrNull { it.value.deadline }
                ?.let { photoReassembly.remove(it.key) }
        }
        val state = photoReassembly.getOrPut(key) {
            PhotoReassembly(
                total = chunk.total,
                parts = arrayOfNulls(chunk.total),
                expectedHash = peer.photoHash.copyOf(),
                deadline = now + Protocol.MSG_REASSEMBLY_TIMEOUT_MS,
            )
        }
        if (state.total != chunk.total) {
            photoReassembly.remove(key)
            return
        }
        state.parts[chunk.index] = chunk.bytes
        state.deadline = now + Protocol.MSG_REASSEMBLY_TIMEOUT_MS
        if (state.parts.all { it != null }) {
            photoReassembly.remove(key)
            var size = 0
            for (p in state.parts) size += p!!.size
            if (size > Protocol.PROFILE_PHOTO_MAX_BYTES) return
            val bytes = ByteArray(size)
            var off = 0
            for (p in state.parts) {
                p!!.copyInto(bytes, off)
                off += p.size
            }
            val file = photoStore.savePeerPhoto(peerId, bytes, state.expectedHash)
            if (file != null) {
                // Atomic conditional link (WHERE photoHash = :expectedHash):
                // a PROFILE frame that changed or removed the photo mid-save
                // makes this a no-op (0 rows) instead of linking a stale
                // file — the check-then-set race is closed inside SQLite.
                val linked = dao.updatePeerPhoto(
                    peerId,
                    file.absolutePath,
                    now,
                    state.expectedHash,
                )
                if (linked > 0) {
                    refreshPeerProfile()
                } else {
                    // Our bytes are stale. Drop them — but only if the
                    // shared peer path still holds our bytes: a newer
                    // transfer may have overwritten it since our save, and
                    // deleting then would orphan the row's fresh link.
                    photoStore.deleteIfHashMatches(file, state.expectedHash)
                }
            }
        }
    }

    // --------------------------------------------------------- peer profile

    /** UI-facing peer profile: stored name + photo + online state. */
    data class PeerProfile(
        val fingerprint: String,
        val displayName: String,
        val photoPath: String?,
        val online: Boolean,
        val lastSeen: Long,
    )

    private val peerProfileTick = MutableStateFlow(0L)

    private fun refreshPeerProfile() {
        peerProfileTick.value = System.currentTimeMillis()
    }

    /**
     * Observes the stored profile for [fingerprint], enriched with live
     * online state. Emits null when the peer was never seen.
     */
    fun observePeerProfile(fingerprint: String): Flow<PeerProfile?> =
        combine(
            dao.observePeer(fingerprint),
            _online,
            currentPeerId,
            peerProfileTick,
        ) { entity, online, liveId, _ ->
            entity?.let {
                PeerProfile(
                    fingerprint = it.fingerprint,
                    displayName = it.displayName,
                    photoPath = it.photoPath,
                    online = online && liveId == it.fingerprint,
                    lastSeen = it.lastSeen,
                )
            }
        }

    /** Live peer's profile (for the walkie-talkie header). */
    val livePeerProfile: Flow<PeerProfile?> =
        currentPeerId.flatMapLatest { id ->
            if (id.isEmpty()) flowOf(null)
            else observePeerProfile(id)
        }

    // ---------------------------------------------------------------- helpers

    private fun refreshPeerId() {
        val mgr = manager ?: return
        val id = mgr.peerIdentityFingerprint.value?.takeIf { it.isNotEmpty() }
            ?: mgr.peerName.value.takeIf { it.isNotEmpty() }
            ?: "peer"
        currentPeerId.value = id
    }

    private fun peerDisplayName(peerId: String): String {
        val mgr = manager
        val live = mgr?.peerName?.value
        return if (mgr != null && currentPeerId.value == peerId && !live.isNullOrEmpty()) live
        else shortId(peerId)
    }

    companion object {
        private const val TAG = "MessagingEngine"
        private const val DRAIN_BATCH = 20
        private const val MAX_ATTEMPTS = 12
        /** Pacing for re-sending an unacked message (at-least-once). */
        private const val RESEND_DELAY_MS = 15_000L
        private const val RETRY_SWEEP_MS = 30_000L
        private const val ACK_BATCH_MS = 200L
        private const val TYPING_MIN_INTERVAL_MS = 3_000L
        private const val TYPING_TTL_MS = 8_000L
        /** High-water caps for the inbound queues (round-7 L5). */
        private const val FRAME_CHANNEL_CAPACITY = 1024
        private const val ACK_CHANNEL_CAPACITY = 1024
        /** Max distinct in-flight reassemblies (round-7 M5). */
        private const val MAX_REASSEMBLY_ENTRIES = 64
        private const val MAX_PHOTO_REASSEMBLY_ENTRIES = 16

        /** Short, stable display fallback for identity-based peer ids. */
        fun shortId(peerId: String): String =
            if (peerId.length <= 12) peerId else peerId.take(12) + "…"

        /**
         * Highest contiguous watermark of a peer's received seqs: the
         * largest N such that every seq from 1 up to N is present. The
         * anchor is 1, unconditionally: per-peer seq spaces always start
         * at 1 (allocation is maxOwnSeq + 1), so a history whose first
         * surviving seq is above 1 has a genuine GAP at the bottom, not
         * pruned history. It arises when later seqs overtake an earlier
         * one (a transient send failure parks seq 1 in backoff while
         * seqs 2,3 send on a later drain). Anchoring at the first
         * surviving seq — the old behavior — advertised those as
         * contiguous, and the sender then ack-dropped the still-queued
         * seq 1: silent, permanent message loss (round-7 I2). HELLO
         * advertises this watermark (never the bare max), so a gap
         * cannot make the sender skip a message never actually received.
         * Pure — unit tested.
         */
        fun contiguousWatermark(seqsAscending: List<Long>): Long {
            var expected = 1L
            var watermark = 0L
            for (seq in seqsAscending) {
                when {
                    seq == expected -> {
                        watermark = seq
                        expected++
                    }
                    seq > expected -> break
                    // seq < expected: duplicate — dedup makes this
                    // unreachable in practice; skip defensively.
                }
            }
            return watermark
        }

        /**
         * Deterministic backoff base: 1s, 2s, 4s … capped at 60s.
         * Callers add jitter. Pure — unit tested.
         */
        fun baseBackoffMs(attempts: Int): Long {
            require(attempts >= 1)
            var d = 1000L
            repeat(min(attempts - 1, 6)) { d *= 2 }
            return min(d, 60_000L)
        }
    }
}

/** Backoff policy with jitter; pure base is [MessagingEngine.baseBackoffMs]. */
object OutboxBackoff {
    fun delayMs(attempts: Int, random: Random = Random.Default): Long =
        MessagingEngine.baseBackoffMs(attempts) + random.nextLong(0, 1000)
}
