package com.molotov.clender.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RoomAlertSchemaTest {
    @Test
    fun roomVersionTwoPersistsLocalAlertColumnsWithDisabledDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java).build()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals("Local alerts require an explicit v2 schema", 2, sqlite.version)
            val columns = sqlite.query("PRAGMA table_info(events)").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            cursor.getString(4)
                        )
                    }
                }
            }
            listOf("notification_enabled", "alarm_enabled", "timer_minutes").forEach { column ->
                assertTrue("Missing local alert column $column", column in columns)
                assertEquals("0", columns[column])
            }
        } finally {
            database.close()
        }
    }
}
