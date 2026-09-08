package com.molotov.clender.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ConversationContextMigrationTest {
    @Test
    fun migrateAndReopenLegacy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(1, 2).forEach { version ->
            val name = "$version-${UUID.randomUUID()}.db"
            createLegacyDatabase(context, name, version)
            try {
                repeat(2) { opening ->
                    val database = Room.databaseBuilder(context, ClenderDatabase::class.java, name)
                        .addMigrations(ClenderDatabase.MIGRATION_1_2, ClenderDatabase.MIGRATION_2_3)
                        .build()
                    try {
                        assertEquals(3, database.openHelper.writableDatabase.version)
                        val repository = RoomConversationRepository(database)
                        val original = requireNotNull(repository.findConversation("legacy"))
                        assertEquals("旧对话", original.title)
                        assertEquals(42, original.tokenCount)
                        assertEquals(
                            "旧消息",
                            repository.observeMessages("legacy").first().single().content
                        )
                        if (opening == 0) {
                            assertNull(original.lastInputTokenEstimate)
                            assertNull(original.lastContextWindow)
                            assertTrue(repository.recordContextUsage("legacy", 200, 4096))
                        } else {
                            assertEquals(200, original.lastInputTokenEstimate)
                            assertEquals(4096, original.lastContextWindow)
                        }
                    } finally {
                        database.close()
                    }
                }
            } finally {
                assertTrue(context.deleteDatabase(name))
            }
        }
    }

    private fun createLegacyDatabase(context: Context, name: String, version: Int) {
        val schema = JSONObject(
            File("schemas/com.molotov.clender.data.local.ClenderDatabase/$version.json").readText()
        ).getJSONObject("database")
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                repeat(indices.length()) { i ->
                    database.execSQL(
                        indices.getJSONObject(i).getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { database.execSQL(setup.getString(it)) }
            database.execSQL(
                "INSERT INTO conversations VALUES (?, ?, ?, ?)",
                arrayOf<Any>("legacy", "旧对话", "2026-09-08T00:00:00.000000Z", 42)
            )
            database.execSQL(
                "INSERT INTO messages VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(1, "legacy", "user", "旧消息", "2026-09-08T00:00:00.000000Z")
            )
            database.version = version
        }
    }
}
