package com.hamseda.walkie.data.msg

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_1_2 against a realistic v1 database. The v1 tables are
 * built with the exact SQL Room generates (exportSchema is off, so
 * MigrationTestHelper cannot reconstruct v1 itself); the real migration then
 * runs with Room's own schema validation, which fails the test on any drift
 * between the migration SQL and the entity definitions.
 */
@RunWith(AndroidJUnit4::class)
class MessageMigrationTest {

    companion object {
        private const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MessageDatabase::class.java,
    )

    private fun createV1Database() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Exact Room v1 codegen for MessageEntity/OutboxEntity/InboxEntity.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `messages` (" +
                                "`id` TEXT NOT NULL, `peerId` TEXT NOT NULL, " +
                                "`peerName` TEXT NOT NULL, `isFromMe` INTEGER NOT NULL, " +
                                "`senderSeq` INTEGER NOT NULL, `body` TEXT NOT NULL, " +
                                "`timestamp` INTEGER NOT NULL, `state` INTEGER NOT NULL, " +
                                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_messages_peerId` " +
                                "ON `messages` (`peerId`)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_messages_senderSeq` " +
                                "ON `messages` (`senderSeq`)",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `outbox` (" +
                                "`msgId` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                                "`nextRetryAt` INTEGER NOT NULL, PRIMARY KEY(`msgId`), " +
                                "FOREIGN KEY(`msgId`) REFERENCES `messages`(`id`) " +
                                "ON UPDATE NO ACTION ON DELETE CASCADE )",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `inbox` (" +
                                "`msgId` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`msgId`))",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        openHelper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO `messages` " +
                    "(`id`, `peerId`, `peerName`, `isFromMe`, `senderSeq`, `body`, " +
                    "`timestamp`, `state`, `createdAt`) VALUES " +
                    "('m1', 'peer1', 'Peer', 1, 7, 'hello v1', 1700000000000, 2, 1700000000001)",
            )
            db.execSQL(
                "INSERT INTO `outbox` (`msgId`, `attempts`, `nextRetryAt`) " +
                    "VALUES ('m1', 1, 1700000000002)",
            )
        }
        openHelper.close()
    }

    @Test
    fun migrate1To2_preservesDataAndCreatesPeersTable() {
        createV1Database()

        // Runs MIGRATION_1_2 and validates the result against the v2 entities.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, MessageDatabase.MIGRATION_1_2,
        )
        db.use {
            // v1 message row survived with all columns intact.
            it.query("SELECT `id`, `peerId`, `body`, `senderSeq`, `state` FROM `messages`")
                .use { c ->
                    assertTrue("message row must survive migration", c.moveToFirst())
                    assertEquals("m1", c.getString(0))
                    assertEquals("peer1", c.getString(1))
                    assertEquals("hello v1", c.getString(2))
                    assertEquals(7L, c.getLong(3))
                    assertEquals(2, c.getInt(4))
                    assertFalse("exactly one message row", c.moveToNext())
                }
            // v1 outbox row survived.
            it.query("SELECT `attempts` FROM `outbox` WHERE `msgId` = 'm1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            // peers table exists with the exact v2 columns.
            it.query("PRAGMA table_info(`peers`)").use { c ->
                val cols = ArrayList<String>()
                while (c.moveToNext()) cols.add(c.getString(1))
                assertEquals(
                    listOf("fingerprint", "displayName", "photoHash", "photoPath", "lastSeen"),
                    cols,
                )
            }
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DB)
    }
}
