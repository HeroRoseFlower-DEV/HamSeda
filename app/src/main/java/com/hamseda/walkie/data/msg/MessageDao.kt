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

    @Query(
        "SELECT m.id AS msgId, m.peerId AS peerId, m.senderSeq AS senderSeq, " +
            "m.body AS body, m.timestamp AS timestamp, o.attempts AS attempts, " +
            "o.nextRetryAt AS nextRetryAt " +
            "FROM outbox o INNER JOIN messages m ON o.msgId = m.id " +
            "WHERE o.nextRetryAt <= :now ORDER BY m.senderSeq ASC LIMIT :limit",
    )
    suspend fun dueOutbox(now: Long, limit: Int): List<OutboxItem>

    @Query("DELETE FROM outbox WHERE msgId = :msgId")
    suspend fun deleteOutbox(msgId: String)

    @Query("UPDATE outbox SET attempts = :attempts, nextRetryAt = :nextRetryAt WHERE msgId = :msgId")
    suspend fun scheduleOutboxRetry(msgId: String, attempts: Int, nextRetryAt: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun outboxCount(): Int

    // ----------------------------------------------------------------- state

    @Query("UPDATE messages SET state = :state WHERE id = :msgId")
    suspend fun setState(msgId: String, state: Int)

    /**
     * Advances to DELIVERED only if the message has not already moved past
     * it (a READ must never be downgraded by a late ACK).
     */
    @Query(
        "UPDATE messages SET state = " + MessageState.DELIVERED +
            " WHERE id = :msgId AND state < " + MessageState.DELIVERED,
    )
    suspend fun advanceToDelivered(msgId: String)

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

    // ------------------------------------------------------------------ read

    /** One conversation's messages, oldest first. */
    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAt ASC, id ASC")
    fun observeConversation(peerId: String): Flow<List<MessageEntity>>

    /** Recent messages across peers for building the conversation list. */
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEntity>>

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
}
