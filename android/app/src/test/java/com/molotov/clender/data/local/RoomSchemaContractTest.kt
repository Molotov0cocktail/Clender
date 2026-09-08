package com.molotov.clender.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RoomSchemaContractTest {
    private lateinit var context: Context
    private val databaseName = "schema-contract.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun currentEmptyDatabaseOpensWithForeignKeysAndExpectedTables() {
        val database = Room.databaseBuilder(
            context,
            ClenderDatabase::class.java,
            databaseName
        ).build()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(3, sqlite.version)
            val tables = sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue("events" in tables)
            assertTrue("conversations" in tables)
            assertTrue("messages" in tables)
            assertEquals(
                1L,
                sqlite.query("PRAGMA foreign_keys").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun versionOneSchemaIsExportedAndContainsForeignKeyAndIndexes() {
        val schema = File("schemas/com.molotov.clender.data.local.ClenderDatabase/1.json")
        assertTrue("Room v1 schema must be committed", schema.isFile)
        val text = schema.readText(Charsets.UTF_8)

        assertTrue(text.contains("\"tableName\": \"events\""))
        assertTrue(text.contains("\"tableName\": \"conversations\""))
        assertTrue(text.contains("\"tableName\": \"messages\""))
        assertTrue(text.contains("idx_events_sync_uid"))
        assertTrue(text.contains("idx_events_start_time"))
        assertTrue(text.contains("idx_events_deleted_at"))
        assertTrue(text.contains("CASCADE"))
        assertFalse(text.contains("fallbackToDestructiveMigration"))
    }
}
