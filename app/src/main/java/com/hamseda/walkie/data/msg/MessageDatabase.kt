package com.hamseda.walkie.data.msg

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room store for offline messaging. Version 2 adds the `peers` table
 * (user profiles keyed by identity fingerprint).
 */
@Database(
    entities = [MessageEntity::class, OutboxEntity::class, InboxEntity::class, PeerEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: MessageDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peers` (" +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`photoHash` BLOB NOT NULL, " +
                        "`photoPath` TEXT, " +
                        "`lastSeen` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`fingerprint`))",
                )
            }
        }

        fun get(context: Context): MessageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessageDatabase::class.java,
                    "hamseda_messages.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
