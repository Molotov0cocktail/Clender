package com.molotov.clender.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EventEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class ClenderDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE events ADD COLUMN notification_enabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE events ADD COLUMN alarm_enabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN timer_minutes INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
