package com.hamseda.walkie.data.msg

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val due = dao.dueOutbox(Long.MAX_VALUE, 10)
        assertEquals(1, due.size)
        assertEquals("m1", due[0].msgId)
        assertEquals("hello m1", due[0].body)
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
        assertTrue(dao.dueOutbox(System.currentTimeMillis(), 10).isEmpty())
        // Due after the retry timestamp.
        assertEquals(1, dao.dueOutbox(future + 1, 10).size)
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
}
