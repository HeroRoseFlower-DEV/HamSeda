package com.hamseda.walkie.data.msg

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room DAO verification on a real Android runtime (emulator in CI):
 * transactional outbox, idempotent inbox, forward-only state transitions.
 */
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: MessageDatabase
    private lateinit var dao: MessageDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MessageDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.messageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun msg(
        id: String,
        peerId: String = "peer1",
        fromMe: Boolean = true,
        seq: Long = 1L,
        state: Int = MessageState.QUEUED,
    ) = MessageEntity(
        id = id,
        peerId = peerId,
        peerName = "Peer",
        isFromMe = fromMe,
        senderSeq = seq,
        body = "hello $id",
        timestamp = 1_700_000_000_000L,
        state = state,
    )

    @Test
    fun insertOutgoing_isAtomic() = runTest {
        dao.insertOutgoing(msg("m1"), OutboxEntity("m1"))
        assertEquals(1, dao.outboxCount())
        val due = dao.dueOutbox("peer1", Long.MAX_VALUE, 10)
        assertEquals(1, due.size)
        assertEquals("m1", due[0].msgId)
        assertEquals("hello m1", due[0].body)
    }

    /**
     * Round-6 C1 regression: the outbox drain is scoped to the connected
     * peer. A session with peer B must neither see (and thus send) nor be
     * able to drop a message queued for peer A — before the fix the drain
     * query had no peer predicate and applied B's HELLO cursor globally,
     * leaking A's text to B or silently marking it delivered.
     */
    @Test
    fun dueOutbox_neverReturnsOtherPeersRows() = runTest {
        dao.insertOutgoing(msg("forA", peerId = "peerA", seq = 1L), OutboxEntity("forA"))
        dao.insertOutgoing(msg("forB", peerId = "peerB", seq = 1L), OutboxEntity("forB"))

        // A session with B drains only B's row.
        val forB = dao.dueOutbox("peerB", Long.MAX_VALUE, 10)
        assertEquals(listOf("forB"), forB.map { it.msgId })
        // A session with A drains only A's row.
        val forA = dao.dueOutbox("peerA", Long.MAX_VALUE, 10)
        assertEquals(listOf("forA"), forA.map { it.msgId })
        // An unknown peer drains nothing.
        assertTrue(dao.dueOutbox("peerC", Long.MAX_VALUE, 10).isEmpty())
        // Both rows are still queued afterwards: scoping must not have
        // consumed or dropped either one.
        assertEquals(2, dao.outboxCount())
    }

    /**
     * Round-6 M5 regression: the conversation list takes the latest
     * message PER PEER, so a quiet peer's thread can never fall out of a
     * global "recent N" window once other chats get busy.
     */
    @Test
    fun latestPerPeer_coversEveryPeer() = runTest {
        dao.insertIncomingDedup(msg("p1a", peerId = "peer1").copy(createdAt = 100L))
        dao.insertIncomingDedup(msg("p1b", peerId = "peer1").copy(createdAt = 300L))
        dao.insertIncomingDedup(msg("p2a", peerId = "peer2").copy(createdAt = 200L))
        dao.insertIncomingDedup(msg("p3a", peerId = "peer3").copy(createdAt = 50L))

        val latest = dao.observeLatestPerPeer().first()
        assertEquals(3, latest.size)
        // Newest activity first; one row per peer; peer1's row is its
        // LATEST message, not its first.
        assertEquals(listOf("p1b", "p2a", "p3a"), latest.map { it.id })
    }

    @Test
    fun inboxDedup_secondInsertIsIgnored() = runTest {
        assertTrue(dao.insertIncomingDedup(msg("m2", fromMe = false)))
        assertFalse(dao.insertIncomingDedup(msg("m2", fromMe = false)))
        val conv = dao.observeConversation("peer1").first()
        assertEquals(1, conv.size)
    }

    @Test
    fun ackAdvancesForwardOnly() = runTest {
        dao.insertOutgoing(msg("m3"), OutboxEntity("m3"))
        dao.advanceToDelivered("m3")
        assertEquals(
            MessageState.DELIVERED,
            dao.observeConversation("peer1").first().single().state,
        )
        // A READ must never be downgraded by a late ACK.
        dao.setState("m3", MessageState.READ)
        dao.advanceToDelivered("m3")
        assertEquals(
            MessageState.READ,
            dao.observeConversation("peer1").first().single().state,
        )
    }

    @Test
    fun outboxRetryScheduling() = runTest {
        dao.insertOutgoing(msg("m4"), OutboxEntity("m4"))
        val future = System.currentTimeMillis() + 60_000L
        dao.scheduleOutboxRetry("m4", 2, future)
        // Not due yet.
        assertTrue(dao.dueOutbox("peer1", System.currentTimeMillis(), 10).isEmpty())
        // Due after the retry timestamp.
        assertEquals(1, dao.dueOutbox("peer1", future + 1, 10).size)
    }

    @Test
    fun maxSeqTracksPerDirection() = runTest {
        dao.insertOutgoing(msg("a", seq = 1L), OutboxEntity("a"))
        dao.insertOutgoing(msg("b", seq = 2L), OutboxEntity("b"))
        dao.insertIncomingDedup(msg("c", fromMe = false, seq = 5L))
        assertEquals(2L, dao.maxOwnSeq("peer1"))
        assertEquals(5L, dao.maxPeerSeq("peer1"))
    }

    @Test
    fun unreadCountsAndMarkRead() = runTest {
        dao.insertIncomingDedup(msg("u1", fromMe = false))
        dao.insertIncomingDedup(msg("u2", fromMe = false))
        val unread = dao.observeUnread().first()
        assertEquals(1, unread.size)
        assertEquals(2, unread[0].unread)
        assertEquals(listOf("u1", "u2"), dao.unreadIncomingIds("peer1").sorted())
        dao.markPeerRead("peer1")
        assertTrue(dao.observeUnread().first().isEmpty())
    }

    @Test
    fun peer_upsertAndObserve() = runTest {
        val fp = "aa".repeat(32)
        dao.upsertPeer(PeerEntity(fingerprint = fp, displayName = "Ali"))
        val got = dao.getPeer(fp)
        assertEquals("Ali", got?.displayName)
        // Latest received name wins (REPLACE).
        dao.upsertPeer(PeerEntity(fingerprint = fp, displayName = "Ali R."))
        assertEquals("Ali R.", dao.getPeer(fp)?.displayName)
        // Denormalized message names follow the profile.
        dao.insertOutgoing(msg("m9", peerId = fp), OutboxEntity("m9"))
        dao.updatePeerName(fp, "Ali R.")
        val conv = dao.observeConversation(fp).first()
        assertTrue(conv.all { it.peerName == "Ali R." })
    }

    private suspend fun stateOf(id: String, peerId: String = "peer1"): Int =
        dao.observeConversation(peerId).first().single { it.id == id }.state

    @Test
    fun ackDelivered_isAtomicAndForwardOnly() = runTest {
        dao.insertOutgoing(msg("a1"), OutboxEntity("a1"))
        dao.ackDelivered("a1")
        // Both writes happened: outbox drained AND state advanced.
        assertEquals(0, dao.outboxCount())
        assertEquals(MessageState.DELIVERED, stateOf("a1"))
        // A READ is never downgraded by a (late/duplicate) ACK.
        dao.setState("a1", MessageState.READ)
        dao.ackDelivered("a1")
        assertEquals(MessageState.READ, stateOf("a1"))
    }

    @Test
    fun failOutbox_isAtomic() = runTest {
        dao.insertOutgoing(msg("f1"), OutboxEntity("f1"))
        dao.failOutbox("f1")
        assertEquals(0, dao.outboxCount())
        assertEquals(MessageState.FAILED, stateOf("f1"))
    }

    @Test
    fun advanceToSent_neverDowngrades() = runTest {
        dao.insertOutgoing(msg("s1"), OutboxEntity("s1"))
        dao.advanceToSent("s1")
        assertEquals(MessageState.SENT, stateOf("s1"))
        // A resend racing a delivered/read receipt must not move it back.
        dao.setState("s1", MessageState.DELIVERED)
        dao.advanceToSent("s1")
        assertEquals(MessageState.DELIVERED, stateOf("s1"))
        dao.setState("s1", MessageState.READ)
        dao.advanceToSent("s1")
        assertEquals(MessageState.READ, stateOf("s1"))
    }

    @Test
    fun markReadByPeer_onlyAdvancesOwnOutgoing() = runTest {
        // Own SENT message -> READ.
        dao.insertOutgoing(msg("r1", state = MessageState.SENT), OutboxEntity("r1"))
        dao.markReadByPeer("r1")
        assertEquals(MessageState.READ, stateOf("r1"))
        // Idempotent: already READ stays READ.
        dao.markReadByPeer("r1")
        assertEquals(MessageState.READ, stateOf("r1"))
        // Incoming messages are never touched by a peer's read receipt.
        dao.insertIncomingDedup(msg("r2", fromMe = false, state = MessageState.DELIVERED))
        dao.markReadByPeer("r2")
        assertEquals(MessageState.DELIVERED, stateOf("r2"))
        // FAILED is terminal: a read receipt must not resurrect it.
        dao.insertOutgoing(msg("r3", state = MessageState.FAILED), OutboxEntity("r3"))
        dao.markReadByPeer("r3")
        assertEquals(MessageState.FAILED, stateOf("r3"))
        // Unknown id: no-op, no crash.
        dao.markReadByPeer("no-such-id")
    }

    @Test
    fun updatePeerPhoto_linksOnlyOnHashMatch() = runTest {
        val fp = "bb".repeat(32)
        val h1 = ByteArray(32) { 1 }
        val h2 = ByteArray(32) { 2 }
        dao.upsertPeer(PeerEntity(fingerprint = fp, displayName = "Ali", photoHash = h1))
        // Matching hash: links, returns 1.
        assertEquals(1, dao.updatePeerPhoto(fp, "/p/1.jpg", 1000L, h1))
        assertEquals("/p/1.jpg", dao.getPeer(fp)?.photoPath)
        // A PROFILE frame changed the hash mid-transfer. The real
        // onProfileFrame resets photoPath to null on hash change; the stale
        // conditional update must then be a no-op (returns 0) and must not
        // re-link anything.
        dao.upsertPeer(
            PeerEntity(
                fingerprint = fp,
                displayName = "Ali",
                photoHash = h2,
                photoPath = null,
                lastSeen = 2000L,
            ),
        )
        assertEquals(0, dao.updatePeerPhoto(fp, "/p/2.jpg", 2000L, h1))
        assertNull(dao.getPeer(fp)?.photoPath)
        assertTrue(dao.getPeer(fp)?.photoHash.contentEquals(h2) == true)
        // A fresh transfer under the new hash links again.
        assertEquals(1, dao.updatePeerPhoto(fp, "/p/2.jpg", 3000L, h2))
        assertEquals("/p/2.jpg", dao.getPeer(fp)?.photoPath)
        // Unknown peer: no-op.
        assertEquals(0, dao.updatePeerPhoto("zz", "/p/3.jpg", 4000L, h1))
    }
}
