package com.molotov.clender.testsupport

import android.os.Looper
import androidx.room.RoomDatabase
import androidx.test.core.app.ActivityScenario
import com.molotov.clender.app.AppContainer
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import org.robolectric.Shadows

internal object ProductionActivityTestResources {
    fun close(application: ClenderApplication, scenario: ActivityScenario<MainActivity>) {
        close(application) { scenario.close() }
    }

    fun close(application: ClenderApplication, closeActivity: () -> Unit) {
        val failures = mutableListOf<Throwable>()

        runCleanupStep(failures, closeActivity)
        runCleanupStep(failures) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        runCleanupStep(failures) { application.container.close() }
        runCleanupStep(failures) {
            val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
            val beforeDelete = databaseFile.exists()
            check(
                !databaseFile.exists() ||
                    application.deleteDatabase(AppContainer.DATABASE_NAME)
            ) {
                "Failed to delete the isolated production test database; " +
                    "existedBefore=$beforeDelete; ${databaseCleanupState(application)}"
            }
        }
        runCleanupStep(failures) {
            val dataStoreDirectory = application.filesDir.resolve("datastore")
            check(!dataStoreDirectory.exists() || dataStoreDirectory.deleteRecursively()) {
                "Failed to delete the isolated production test DataStore directory"
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("Production Activity test resource cleanup failed").also { error ->
                failures.forEach(error::addSuppressed)
            }
        }
    }

    private fun databaseCleanupState(application: ClenderApplication): String = try {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        val files = listOf("", "-wal", "-shm", "-journal", ".lck").map { suffix ->
            val file = java.io.File(databaseFile.parentFile, databaseFile.name + suffix)
            val label = suffix.ifEmpty { "db" }
            "$label:exists=${file.exists()},bytes=${file.length()}"
        }
        val holder = AppContainer::class.java.getDeclaredField("databaseHolder").run {
            isAccessible = true
            get(application.container) as Lazy<*>
        }
        val open = if (holder.isInitialized()) (holder.value as RoomDatabase).isOpen else false
        "roomOpenAfterClose=$open; ${files.joinToString(";")}"
    } catch (diagnosticFailure: Throwable) {
        "diagnosticUnavailable=${diagnosticFailure.javaClass.simpleName}"
    }

    private inline fun runCleanupStep(failures: MutableList<Throwable>, step: () -> Unit) {
        try {
            step()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
}
