package com.molotov.clender.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [EventEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class ClenderDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao
}
