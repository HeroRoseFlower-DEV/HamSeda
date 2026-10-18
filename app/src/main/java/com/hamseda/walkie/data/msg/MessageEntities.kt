package com.hamseda.walkie.data.msg

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Delivery states for a chat message. Ordinals are persisted; do not reorder.
 * Transitions only move forward: QUEUED → SENT → DELIVERED → READ.
 * FAILED is terminal (message exceeded retry budget or was rejected).
 */
object MessageState {
    const val QUEUED = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val READ = 3
    const val FAILED = 4
}

/**
 * One chat message, either direction. `id` is the UUIDv7 string shared with
 * the peer (dedup key); `senderSeq` is the per-sender monotonic sequence used
 * for ordering and HELLO resync. `peerId` groups messages into conversations
 * (the peer's identity fingerprint, or transport name fallback).
 */
@Entity(
    tableName = "messages",
    indices = [Index("peerId"), Index("senderSeq")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val peerId: String,
    /** Display name captured at insert time (denormalized for offline list rendering). */
    val peerName: String = "",
    val isFromMe: Boolean,
    val senderSeq: Long,
    val body: String,
    /** Sender wall-clock millis; display only — ordering uses senderSeq. */
    val timestamp: Long,
    val state: Int = MessageState.QUEUED,
    /** Local insert time; conversation ordering key. */
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Transactional outbox: a row here means "must still be delivered".
 * Inserted in the SAME Room transaction as the message; removed ONLY when
 * the peer ACKs (at-least-once). The idempotent inbox on the receiving side
 * turns this into exactly-once effects.
 */
@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["msgId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OutboxEntity(
    @PrimaryKey val msgId: String,
    val attempts: Int = 0,
    /** Epoch millis after which this entry is eligible for (re)send. */
    val nextRetryAt: Long = 0L,
)

/**
 * Dedup inbox: every message ID ever received, kept well beyond any
 * plausible redelivery window (rows are tiny). INSERT … ON CONFLICT IGNORE
 * makes the consumer idempotent.
 */
@Entity(tableName = "inbox")
data class InboxEntity(
    @PrimaryKey val msgId: String,
    val seenAt: Long = System.currentTimeMillis(),
)
