package com.molotov.clender.app

import android.database.sqlite.SQLiteDatabase
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.domain.event.EventRepository
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class AppContainerTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeIsolatedSandboxDatabase() {
        application.deleteDatabase(AppContainer.DATABASE_NAME)
    }

    @Test
    fun applicationStartupDoesNotCreateOrOpenTheDatabase() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)

        assertFalse(databaseFile.exists())
        application.container
        assertFalse(databaseFile.exists())
    }

    @Test
    fun fixedDatabasePathIsInsideAndroidApplicationSandbox() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME).canonicalFile
        val sandbox = File(requireNotNull(application.applicationInfo.dataDir)).canonicalFile
        val repositoryRoot = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val windowsData = File(repositoryRoot, "../data").canonicalFile
        val packagedData = File(repositoryRoot, "../dist/data").canonicalFile

        assertTrue(databaseFile.toPath().startsWith(sandbox.toPath()))
        assertFalse(databaseFile.toPath().startsWith(windowsData.toPath()))
        assertFalse(databaseFile.toPath().startsWith(packagedData.toPath()))
    }

    @Test
    fun containerExposesOnlyRepositoryAbstractionAndDefersRoomOpeningUntilQuery() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        val container = AppContainer(application)

        assertFalse(databaseFile.exists())
        val repository: EventRepository = container.eventRepository
        assertTrue(repository is RoomEventRepository)
        assertFalse(databaseFile.exists())

        try {
            runBlocking {
                repository.observeRange(
                    LocalDateTime.of(2026, 8, 1, 0, 0),
                    LocalDateTime.of(2026, 8, 2, 0, 0)
                ).first()
            }
            assertTrue(databaseFile.exists())
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use {
                val cursor = it.rawQuery("SELECT COUNT(*) FROM events", null)
                cursor.use {
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getInt(0) == 0)
                }
            }
        } finally {
            container.close()
        }
    }

    @Test
    fun newerUnknownDatabaseFailsClosedWithoutDestructiveFallback() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.version = 2
            database.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)")
            database.execSQL("INSERT INTO sentinel(value) VALUES ('preserve')")
        }
        val container = AppContainer(application)
        val repository = container.eventRepository

        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    repository.observeRange(
                        LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 2, 0, 0)
                    ).first()
                }
            }

            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use {
                val cursor = it.rawQuery("SELECT value FROM sentinel", null)
                cursor.use {
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0) == "preserve")
                }
            }
        } finally {
            container.close()
        }
    }
}
