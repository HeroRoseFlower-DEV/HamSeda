package com.hamseda.walkie.data.msg

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One outbox row joined with its message, for the drain worker. */
data class OutboxItem(
    val msgId: String,
    val peerId: String,
    val senderSeq: Long,
    val body: String,
    val timestamp: Long,
    val attempts: Int,
    val nextRetryAt: Long,
)

/** Per-peer unread badge counts for the conversation list. */
data class UnreadRow(
    val peerId: String,
    val unread: Int,
)

@Dao
interface MessageDao {
    // ---------------------------------------------------------- transactional

    /**
     * Stores an outgoing message AND its outbox row atomically: a message is
     * never visible without a delivery obligation, and never queued without
     * being visible.
     */
    @Transaction
    suspend fun insertOutgoing(msg: MessageEntity, outbox: OutboxEntity) {
        insertMessage(msg)
        insertOutbox(outbox)
    }

    /**
     * Idempotent receive: returns the row id, or -1 if this msgId was seen
     * before. Callers must still ACK duplicates (at-least-once sender).
     */
    @Transaction
    suspend fun insertIncomingDedup(msg: MessageEntity): Boolean {
        val inboxRow = insertInbox(InboxEntity(msg.id))
        if (inboxRow == -1L) return false
        insertMessage(msg)
        return true
    }

    // -------------------------------------------------------------- inserts

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(msg: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInbox(entry: InboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(entry: OutboxEntity)

    // ---------------------------------------------------------------- outbox

    /**
     * Due outbox rows for ONE peer only. Delivery is strictly per-peer:
     * senderSeq spaces are independent per peer, and a queued text must
     * never be drained onto another peer's session (it would deliver A's
     * private message to B, or be dropped as "already seen" by B's HELLO
     * cursor). The drain in MessagingEngine relies on this predicate.
     */
    @Query(
        "SELECT m.id AS msgId, m.peerId AS peerId, m.senderSeq AS senderSeq, " +
            "m.body AS body, m.timestamp AS timestamp, o.attempts AS attempts, " +
            "o.nextRetryAt AS nextRetryAt " +
            "FROM outbox o INNER JOIN messages m ON o.msgId = m.id " +
            "WHERE m.peerId = :peerId AND o.nextRetryAt <= :now " +
            "ORDER BY m.senderSeq ASC LIMIT :limit",
    )
    suspend fun dueOutbox(peerId: String, now: Long, limit: Int): List<OutboxItem>

    @Query("DELETE FROM outbox WHERE msgId = :msgId")
    suspend fun deleteOutbox(msgId: String)

    /**
     * Atomically removes the outbox entry and advances the message state.
     * The two writes must not be separated: a crash between them would leave
     * the message queued forever (first) or lose the redelivery guard (second).
     */
    @Transaction
    suspend fun ackDelivered(msgId: String) {
        deleteOutbox(msgId)
        advanceToDelivered(msgId)
    }

    @Transaction
    suspend fun failOutbox(msgId: String) {
        deleteOutbox(msgId)
        setState(msgId, MessageState.FAILED)
    }

    @Query("UPDATE outbox SET attempts = :attempts, nextRetryAt = :nextRetryAt WHERE msgId = :msgId")
    suspend fun scheduleOutboxRetry(msgId: String, attempts: Int, nextRetryAt: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun outboxCount(): Int

    // ----------------------------------------------------------------- state

    @Query("UPDATE messages SET state = :state WHERE id = :msgId")
    suspend fun setState(msgId: String, state: Int)

    /**
     * Advances to SENT only from QUEUED (a READ/DELIVERED must never be
     * downgraded by a resend that raced the ACK/read receipt).
     */
    @Query(
        "UPDATE messages SET state = " + MessageState.SENT +
            " WHERE id = :msgId AND state < " + MessageState.SENT,
    )
    suspend fun advanceToSent(msgId: String)

    /**
     * Advances to DELIVERED only if the message has not already moved past
     * it (a READ must never be downgraded by a late ACK).
     */
    @Query(
        "UPDATE messages SET state = " + MessageState.DELIVERED +
            " WHERE id = :msgId AND state < " + MessageState.DELIVERED,
    )
    suspend fun advanceToDelivered(msgId: String)

    /**
     * Applies a peer's MSG_READ receipt. Guarded so a remote peer can only
     * move OUR OWN outgoing messages forward to READ: incoming messages,
     * terminal FAILED rows, and unknown ids are never touched.
     */
    @Query(
        "UPDATE messages SET state = " + MessageState.READ +
            " WHERE id = :msgId AND isFromMe = 1 AND state < " + MessageState.READ,
    )
    suspend fun markReadByPeer(msgId: String)

    @Query(
        "SELECT id FROM messages WHERE peerId = :peerId AND isFromMe = 0 " +
            "AND state < " + MessageState.READ + " ORDER BY createdAt ASC",
    )
    suspend fun unreadIncomingIds(peerId: String): List<String>

    /**
     * Marks every incoming message of this peer up to READ, except ones the
     * user never saw. Returns the number of rows changed.
     */
    @Query(
        "UPDATE messages SET state = " + MessageState.READ +
            " WHERE peerId = :peerId AND isFromMe = 0 AND state < " + MessageState.READ,
    )
    suspend fun markPeerRead(peerId: String): Int

    @Query("SELECT MAX(senderSeq) FROM messages WHERE peerId = :peerId AND isFromMe = 1")
    suspend fun maxOwnSeq(peerId: String): Long?

    @Query("SELECT MAX(senderSeq) FROM messages WHERE peerId = :peerId AND isFromMe = 0")
    suspend fun maxPeerSeq(peerId: String): Long?

    /** All seqs received from this peer, ascending — HELLO watermark input. */
    @Query(
        "SELECT senderSeq FROM messages WHERE peerId = :peerId AND isFromMe = 0 " +
            "ORDER BY senderSeq ASC",
    )
    suspend fun receivedSeqs(peerId: String): List<Long>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    // ------------------------------------------------------------------ read

    /** One conversation's messages, oldest first. */
    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAt ASC, id ASC")
    fun observeConversation(peerId: String): Flow<List<MessageEntity>>

    /**
     * The latest message of EVERY peer (one row per peer), newest first.
     * The conversation list is built from this — a global "recent N"
     * window would silently drop a peer's whole thread once N newer
     * messages exist elsewhere.
     */
    @Query(
        "SELECT * FROM messages m WHERE m.id = (" +
            "SELECT id FROM messages WHERE peerId = m.peerId " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1" +
            ") ORDER BY m.createdAt DESC, m.id DESC",
    )
    fun observeLatestPerPeer(): Flow<List<MessageEntity>>

    @Query(
        "SELECT peerId, COUNT(*) AS unread FROM messages " +
            "WHERE isFromMe = 0 AND state < " + MessageState.READ +
            " GROUP BY peerId",
    )
    fun observeUnread(): Flow<List<UnreadRow>>

    // ----------------------------------------------------------------- peers

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    suspend fun getPeer(fingerprint: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    fun observePeer(fingerprint: String): Flow<PeerEntity?>

    /** Refreshes the denormalized display name on a peer's messages. */
    @Query("UPDATE messages SET peerName = :name WHERE peerId = :peerId")
    suspend fun updatePeerName(peerId: String, name: String)

    /**
     * Links a verified photo file to a peer without touching the other
     * columns. Used after photo reassembly so a concurrent PROFILE update
     * (new name / changed hash) is never clobbered by a stale snapshot.
     *
     * The link is conditional on [expectedHash]: a PROFILE frame that
     * changed or removed the photo between the save and this update makes
     * it a no-op (returns 0) instead of linking a stale file — this closes
     * the check-then-set race atomically in SQLite. Returns the number of
     * rows updated.
     */
    @Query(
        "UPDATE peers SET photoPath = :photoPath, lastSeen = :lastSeen " +
            "WHERE fingerprint = :fingerprint AND photoHash = :expectedHash",
    )
    suspend fun updatePeerPhoto(
        fingerprint: String,
        photoPath: String?,
        lastSeen: Long,
        expectedHash: ByteArray,
    ): Int
}
