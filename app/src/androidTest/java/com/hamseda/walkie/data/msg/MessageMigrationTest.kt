package com.hamseda.walkie.data.msg

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v1 schema as Room itself generates it: the same entity classes the
 * app shipped in v1 (the v1→v2 migration only ADDS the `peers` table),
 * pinned at version 1. Building the fixture through this class means the
 * DDL, identity hash and `room_master_table` all come from Room's real
 * codegen — not from hand-written SQL "matching" it.
 */
@Database(
    entities = [MessageEntity::class, OutboxEntity::class, InboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class V1MessageDatabase : RoomDatabase()

/**
 * Verifies MIGRATION_1_2 through Room's real open path (round-7 L23).
 *
 * The previous version of this test created the v1 database with
 * hand-copied DDL and invoked the migration directly on a raw
 * SupportSQLiteDatabase with an empty onUpgrade — if the hand-written
 * DDL ever drifted from real v1 codegen, or Room's own open path would
 * have rejected the migrated database (identity-hash / TableInfo
 * validation), the test still passed. Here the v1 file is built by
 * Room, then opened with the production [MessageDatabase]: Room runs
 * the migration itself and validates the result against the v2 schema
 * before any assertion runs.
 */
@RunWith(AndroidJUnit4::class)
class MessageMigrationTest {

    companion object {
        private const val TEST_DB = "migration-test.db"
    }

    @Test
    fun migrate1To2_viaRoomOpenPath_preservesDataAndCreatesPeersTable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)

        // Build a genuine v1 database file with Room and seed one row.
        val v1 = Room.databaseBuilder(context, V1MessageDatabase::class.java, TEST_DB)
            .build()
        v1.openHelper.writableDatabase.apply {
            execSQL(
                "INSERT INTO `messages` " +
                    "(`id`, `peerId`, `peerName`, `isFromMe`, `senderSeq`, `body`, " +
                    "`timestamp`, `state`, `createdAt`) VALUES " +
                    "('m1', 'peer1', 'Peer', 1, 7, 'hello v1', 1700000000000, 2, 1700000000001)",
            )
            execSQL(
                "INSERT INTO `outbox` (`msgId`, `attempts`, `nextRetryAt`) " +
                    "VALUES ('m1', 1, 1700000000002)",
            )
        }
        v1.close()

        // Open the same file with the production database. This is the
        // moment of truth: Room's onUpgrade runs MIGRATION_1_2 and then
        // validates the migrated schema against v2's expected TableInfo
        // — a wrong peers table (or any drift) throws here, before the
        // assertions below ever run.
        val v2 = Room.databaseBuilder(context, MessageDatabase::class.java, TEST_DB)
            .addMigrations(MessageDatabase.MIGRATION_1_2)
            .build()
        val dao = v2.messageDao()

        // v1 message row survived with all columns intact.
        val conv = dao.observeConversation("peer1").first()
        assertEquals(1, conv.size)
        val m1 = conv.single()
        assertEquals("m1", m1.id)
        assertEquals("hello v1", m1.body)
        assertEquals(7L, m1.senderSeq)
        assertEquals(2, m1.state)
        // v1 outbox row survived.
        assertEquals(1, dao.outboxCount())

        // peers table exists with the exact v2 columns.
        v2.openHelper.writableDatabase
            .query("PRAGMA table_info(`peers`)")
            .use { c ->
                val cols = ArrayList<String>()
                while (c.moveToNext()) cols.add(c.getString(1))
                assertEquals(
                    listOf("fingerprint", "displayName", "photoHash", "photoPath", "lastSeen"),
                    cols,
                )
            }
        // And it is fully usable through the production DAO.
        dao.upsertPeer(PeerEntity(fingerprint = "peer1", displayName = "Peer"))
        assertEquals("Peer", dao.getPeer("peer1")?.displayName)

        v2.close()
        context.deleteDatabase(TEST_DB)
    }
}
