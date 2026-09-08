package com.molotov.clender.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val CONTEXT_USAGE_SCHEMA_VERSION = 3

@Database(
    entities = [EventEntity::class, ConversationEntity::class, MessageEntity::class],
    version = CONTEXT_USAGE_SCHEMA_VERSION,
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class ClenderDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    companion object {
        val MIGRATION_2_3: Migration = object : Migration(2, CONTEXT_USAGE_SCHEMA_VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN last_input_token_estimate INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN last_context_window INTEGER")
            }
        }

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
