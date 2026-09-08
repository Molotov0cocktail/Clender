package com.molotov.clender.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.UtcInstantCodec
import com.molotov.clender.core.model.WallClockCodec
import com.molotov.clender.core.model.eventFixture
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RoomAlertMigrationTest {
    @Test
    fun versionOneDataMigratesWithAllAlertsOffAndSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "alerts-migration-${UUID.randomUUID()}.db"
        val original = eventFixture(title = "Legacy reminder 保留")
        createVersionOne(context, name, original)
        try {
            repeat(2) {
                val database = Room.databaseBuilder(context, ClenderDatabase::class.java, name)
                    .addMigrations(ClenderDatabase.MIGRATION_1_2).build()
                try {
                    assertEquals(2, database.openHelper.writableDatabase.version)
                    assertEquals(original, RoomEventRepository(database).findById(original.id))
                } finally {
                    database.close()
                }
            }
        } finally {
            assertTrue(context.deleteDatabase(name))
        }
    }

    @Test
    fun remoteUpdatesPreserveLocalAlertsAndNewRemoteIdsStartDisabled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java).build()
        try {
            val repository = RoomEventRepository(database)
            val local = repository.insert(
                eventFixture(
                    id = 0
                ).copy(notificationEnabled = true, alarmEnabled = true, timerMinutes = 25)
            )
            val incoming = local.copy(
                title = "Remote title",
                updatedAt = local.updatedAt.plusSeconds(10)
            )
            assertTrue(repository.applyRemote(listOf(incoming)) { _, _ -> true })
            val stored = requireNotNull(repository.findById(local.id))
            assertEquals("Remote title", stored.title)
            assertTrue(stored.notificationEnabled)
            assertTrue(stored.alarmEnabled)
            assertEquals(25, stored.timerMinutes)
            val newIncoming = incoming.copy(syncUid = "fedcba9876543210fedcba9876543210")
            assertTrue(repository.applyRemote(listOf(newIncoming)) { _, _ -> true })
            val newStored = requireNotNull(repository.findBySyncUid(newIncoming.syncUid))
            assertFalse(newStored.notificationEnabled)
            assertFalse(newStored.alarmEnabled)
            assertEquals(0, newStored.timerMinutes)
            val beforeFailure = repository.syncSnapshot()
            val invalid = newIncoming.copy(
                syncUid = "11111111111111111111111111111111",
                title = " "
            )
            try {
                repository.applyRemote(listOf(incoming.copy(title = "Must roll back"), invalid)) {
                        _,
                        _
                    ->
                    true
                }
                error("Invalid remote batch must fail")
            } catch (_: IllegalArgumentException) {
                assertEquals(beforeFailure, repository.syncSnapshot())
            }
        } finally {
            database.close()
        }
    }

    private fun createVersionOne(context: Context, name: String, event: Event) {
        val schema = JSONObject(
            File("schemas/com.molotov.clender.data.local.ClenderDatabase/1.json").readText()
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
                        indices.getJSONObject(
                            i
                        ).getString("createSql").replace("\${TABLE_NAME}", table)
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { database.execSQL(setup.getString(it)) }
            database.execSQL(
                "INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    event.id, event.eventType.wireValue, event.title,
                    WallClockCodec.format(
                        event.startTime
                    ),
                    null, event.description, event.estimatedDurationMinutes,
                    UtcInstantCodec.format(
                        event.createdAt
                    ),
                    event.syncUid, UtcInstantCodec.format(event.updatedAt), null
                )
            )
            database.version = 1
        }
    }
}
