package com.molotov.clender.app

import com.molotov.clender.core.model.EventType
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.FieldUpdate
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class AppContainerMutationTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeIsolatedSandboxDatabase() {
        application.deleteDatabase(AppContainer.DATABASE_NAME)
    }

    @Test
    fun writeAssemblyIsLazyAndExposesReadOnlyVersionWithoutOpeningRoom() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        val container = AppContainer(application)

        try {
            assertFalse(databaseFile.exists())
            val repository: EventRepository = container.eventRepository
            val version: StateFlow<Long> = container.scheduleMutationVersion
            container.eventService

            assertTrue(repository is RoomEventRepository)
            assertEquals(0L, version.value)
            assertFalse(databaseFile.exists())
        } finally {
            container.close()
        }
    }

    @Test
    fun eventServiceWritesThroughTheSameRepositoryAndUsesProductionMetadata() = runBlocking {
        val container = AppContainer(application)
        val repository = container.eventRepository
        val before = Instant.now().minusSeconds(1)

        try {
            val first = container.eventService.add(command("first", 9))
            val second = container.eventService.add(command("second", 10))
            val after = Instant.now().plusSeconds(1)

            assertEquals(first, repository.findById(first.id))
            assertEquals(second, repository.findById(second.id))
            assertTrue(first.createdAt >= before && first.createdAt <= after)
            assertTrue(second.createdAt >= before && second.createdAt <= after)
            assertTrue(UID.matches(first.syncUid))
            assertTrue(UID.matches(second.syncUid))
            assertNotEquals(first.syncUid, second.syncUid)
            assertEquals(2L, container.scheduleMutationVersion.value)
        } finally {
            container.close()
        }
    }

    @Test
    fun successfulAddUpdateDeleteEachIncrementOnceAndBatchOnlyOnce() = runBlocking {
        val container = AppContainer(application)

        try {
            val added = container.eventService.add(command("one", 9))
            assertEquals(1L, container.scheduleMutationVersion.value)

            container.eventService.update(
                added.id,
                EventPatch(title = FieldUpdate.Set("updated"))
            )
            assertEquals(2L, container.scheduleMutationVersion.value)

            assertTrue(container.eventService.delete(added.id))
            assertEquals(3L, container.scheduleMutationVersion.value)

            container.eventService.runBatched { batched ->
                batched.add(command("batched-a", 11))
                batched.add(command("batched-b", 12))
            }
            assertEquals(4L, container.scheduleMutationVersion.value)
        } finally {
            container.close()
        }
    }

    @Test
    fun failedRoomWriteDoesNotAdvanceProductionMutationVersion() = runBlocking {
        val container = AppContainer(application)

        try {
            val stored = container.eventService.add(command("committed", 9))
            val versionBeforeFailure = container.scheduleMutationVersion.value
            container.close()

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    container.eventService.update(
                        stored.id,
                        EventPatch(title = FieldUpdate.Set("must fail"))
                    )
                }
            }
            assertEquals(versionBeforeFailure, container.scheduleMutationVersion.value)
        } finally {
            container.close()
        }
    }

    private fun command(title: String, hour: Int) = AddEventCommand(
        eventType = EventType.REMINDER,
        title = title,
        startTime = LocalDateTime.of(2026, 8, 31, hour, 0)
    )

    private companion object {
        val UID = Regex("^[0-9a-f]{32}$")
    }
}
