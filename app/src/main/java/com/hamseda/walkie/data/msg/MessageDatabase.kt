package com.hamseda.walkie.data.msg

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room store for offline messaging. Fresh-install base at version 1 — the
 * app shipped no Room database before, so no migration is needed.
 */
@Database(
    entities = [MessageEntity::class, OutboxEntity::class, InboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: MessageDatabase? = null

        fun get(context: Context): MessageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessageDatabase::class.java,
                    "hamseda_messages.db",
                ).build().also { instance = it }
            }
    }
}
