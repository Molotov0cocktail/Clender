package com.molotov.clender.domain.event

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventServiceTest {
    private val now = Instant.parse("2026-08-07T01:02:03.123456Z")
    private val uid = "fedcba9876543210fedcba9876543210"

    @Test
    fun addInjectsClockAndUidThenEmitsMutationAfterPersistence() = runSuspend {
        val repository = FakeEventRepository()
        val mutations = RecordingMutationSink()
        val service = service(repository, mutations)

        val added = service.add(
            AddEventCommand(
                eventType = EventType.REMINDER,
                title = "  计划 🌏  ",
                startTime = LocalDateTime.of(2024, 2, 29, 23, 59),
                description = "说明",
                estimatedDurationMinutes = 481
            )
        )

        assertEquals(1L, added.id)
        assertEquals("计划 🌏", added.title)
        assertEquals(uid, added.syncUid)
        assertEquals(now, added.createdAt)
        assertEquals(now, added.updatedAt)
        assertNull(added.deletedAt)
        assertEquals(listOf(ScheduleMutation.Added(added)), mutations.values)
    }

    @Test
    fun failedPersistenceNeverEmitsMutation() {
        val repository = FakeEventRepository().apply { failWrites = true }
        val mutations = RecordingMutationSink()
        val service = service(repository, mutations)

        assertThrows(IllegalStateException::class.java) {
            runSuspend {
                service.add(
                    AddEventCommand(
                        eventType = EventType.REMINDER,
                        title = "事项",
                        startTime = LocalDateTime.of(2026, 8, 7, 0, 0)
                    )
                )
            }
        }
        assertTrue(mutations.values.isEmpty())
    }

    @Test
    fun updateSupportsTypeConversionAndExplicitNullableEndPatch() = runSuspend {
        val existing = eventFixture(
            eventType = EventType.TIMESPAN,
            endTime = LocalDateTime.of(2026, 8, 7, 11, 0)
        )
        val repository = FakeEventRepository(existing)
        val mutations = RecordingMutationSink()
        val service = service(repository, mutations)

        val reminder = service.update(
            existing.id,
            EventPatch(eventType = FieldUpdate.Set(EventType.REMINDER))
        )
        assertEquals(EventType.REMINDER, reminder.eventType)
        assertNull(reminder.endTime)
        assertEquals(ScheduleMutation.Updated(reminder), mutations.values.single())

        assertThrows(EventValidationException::class.java) {
            runSuspend {
                service.update(
                    reminder.id,
                    EventPatch(eventType = FieldUpdate.Set(EventType.TIMESPAN))
                )
            }
        }
        val converted = service.update(
            reminder.id,
            EventPatch(
                eventType = FieldUpdate.Set(EventType.TIMESPAN),
                endTime = FieldUpdate.Set(reminder.startTime.plusHours(2))
            )
        )
        assertEquals(EventType.TIMESPAN, converted.eventType)
        assertEquals(reminder.startTime.plusHours(2), converted.endTime)
    }

    @Test
    fun updateRejectsEmptyInvalidMissingAndTombstonedTargets() {
        val existing = eventFixture()
        val repository = FakeEventRepository(existing)
        val service = service(repository, RecordingMutationSink())

        assertThrows(InvalidEventPatchException::class.java) {
            runSuspend { service.update(existing.id, EventPatch()) }
        }
        assertThrows(EventValidationException::class.java) {
            runSuspend {
                service.update(existing.id, EventPatch(title = FieldUpdate.Set(" \t")))
            }
        }
        assertThrows(EventNotFoundException::class.java) {
            runSuspend { service.update(999, EventPatch(title = FieldUpdate.Set("x"))) }
        }
        repository.values[existing.id] = existing.copy(deletedAt = existing.updatedAt)
        assertThrows(TombstonedEventException::class.java) {
            runSuspend { service.update(existing.id, EventPatch(title = FieldUpdate.Set("x"))) }
        }
        assertThrows(EventValidationException::class.java) {
            runSuspend { service.update(0, EventPatch(title = FieldUpdate.Set("x"))) }
        }
    }

    @Test
    fun deleteCreatesEqualUpdatedAndDeletedTombstoneAndProtectsRepeat() = runSuspend {
        val existing = eventFixture(updatedAt = now.minusSeconds(10))
        val repository = FakeEventRepository(existing)
        val mutations = RecordingMutationSink()
        val service = service(repository, mutations)

        assertTrue(service.delete(existing.id))
        val deleted = repository.values.getValue(existing.id)
        assertEquals(now, deleted.updatedAt)
        assertEquals(now, deleted.deletedAt)
        assertEquals(listOf(ScheduleMutation.Deleted(deleted)), mutations.values)
        assertFalse(service.delete(existing.id))
        assertFalse(service.delete(999))
        assertEquals(1, mutations.values.size)
    }

    @Test
    fun batchesSuccessfulMutationsIntoOneNotification() = runSuspend {
        val existing = eventFixture(updatedAt = now.minusSeconds(10))
        val repository = FakeEventRepository(existing)
        val mutations = RecordingMutationSink()
        val service = service(repository, mutations)

        service.runBatched { batched ->
            val updated = batched.update(
                existing.id,
                EventPatch(title = FieldUpdate.Set("批量更新"))
            )
            assertTrue(batched.delete(updated.id))
        }

        val batch = mutations.values.single() as ScheduleMutation.Batch
        assertEquals(2, batch.mutations.size)
        assertTrue(batch.mutations[0] is ScheduleMutation.Updated)
        assertTrue(batch.mutations[1] is ScheduleMutation.Deleted)
    }

    @Test
    fun emptyBatchDoesNotEmitMutation() = runSuspend {
        val mutations = RecordingMutationSink()
        service(FakeEventRepository(), mutations).runBatched { Unit }
        assertTrue(mutations.values.isEmpty())
    }

    @Test
    fun batchEmitsPersistedMutationsBeforePropagatingFailureOrCancellation() {
        listOf(
            IllegalStateException("after persistence"),
            CancellationException("backgrounded")
        ).forEach { failure ->
            val existing = eventFixture(updatedAt = now.minusSeconds(10))
            val mutations = RecordingMutationSink()
            val service = service(FakeEventRepository(existing), mutations)

            assertThrows(failure.javaClass) {
                runSuspend {
                    service.runBatched { batched ->
                        batched.update(
                            existing.id,
                            EventPatch(title = FieldUpdate.Set("已持久化"))
                        )
                        throw failure
                    }
                }
            }

            val batch = mutations.values.single() as ScheduleMutation.Batch
            assertEquals(1, batch.mutations.size)
            assertTrue(batch.mutations.single() is ScheduleMutation.Updated)
        }
    }

    @Test
    fun committedUpdateEmitsMutationBeforeCancellationIsDelivered() = runBlocking {
        val existing = eventFixture(updatedAt = now.minusSeconds(10))
        val committed = CompletableDeferred<Unit>()
        val allowResultDelivery = CompletableDeferred<Unit>()
        val repository = CommitBeforeReturnEventRepository(
            existing,
            committed,
            allowResultDelivery
        )
        val mutations = RecordingMutationSink()
        val service = EventService(
            repository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            uidGenerator = SyncUidGenerator { uid },
            mutationSink = mutations
        )

        val update = launch {
            service.update(existing.id, EventPatch(title = FieldUpdate.Set("已提交")))
        }
        committed.await()
        update.cancel(CancellationException("backgrounded after database commit"))
        allowResultDelivery.complete(Unit)
        update.join()

        assertTrue(update.isCancelled)
        assertEquals("已提交", repository.value.title)
        assertEquals(
            listOf(ScheduleMutation.Updated(repository.value)),
            mutations.values
        )
    }

    @Test
    fun observeDateDelegatesOneStrictLocalWallClockDayAndRangeRejectsInvalidBounds() = runSuspend {
        val first = eventFixture(startTime = LocalDateTime.of(2026, 8, 7, 0, 0))
        val repository = FakeEventRepository(first)
        val service = service(repository, RecordingMutationSink())

        assertEquals(listOf(first), service.observeDate(first.startTime.toLocalDate()).first())
        assertEquals(
            LocalDateTime.of(2026, 8, 7, 0, 0) to LocalDateTime.of(2026, 8, 8, 0, 0),
            repository.observedRanges.single()
        )
        assertThrows(IllegalArgumentException::class.java) {
            service.observeRange(first.startTime, first.startTime)
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.observeRange(first.startTime, first.startTime.minusMinutes(1))
        }
        Unit
    }

    @Test
    fun updateRevalidatesWholeEventAndDoesNotEmitOnRepositoryFailure() {
        val existing = eventFixture()
        val repository = FakeEventRepository(existing)
        val mutations = RecordingMutationSink()
        val service = service(repository, mutations)

        assertThrows(EventValidationException::class.java) {
            runSuspend {
                service.update(
                    existing.id,
                    EventPatch(estimatedDurationMinutes = FieldUpdate.Set(-1))
                )
            }
        }
        repository.failWrites = true
        assertThrows(IllegalStateException::class.java) {
            runSuspend {
                service.update(existing.id, EventPatch(title = FieldUpdate.Set("new")))
            }
        }
        assertTrue(mutations.values.isEmpty())
    }

    @Test
    fun observeMonthCountsClipsCrossMonthAndExcludesMidnightEndDay() = runSuspend {
        val month = YearMonth.of(2026, 8)
        val events = arrayOf(
            eventFixture(id = 1, startTime = LocalDateTime.of(2026, 8, 7, 9, 0)),
            eventFixture(id = 2, startTime = LocalDateTime.of(2026, 8, 7, 10, 0)),
            eventFixture(
                id = 3,
                eventType = EventType.TIMESPAN,
                startTime = LocalDateTime.of(2026, 7, 31, 23, 0),
                endTime = LocalDateTime.of(2026, 8, 2, 0, 0)
            ),
            eventFixture(
                id = 4,
                eventType = EventType.TIMESPAN,
                startTime = LocalDateTime.of(2026, 8, 31, 23, 0),
                endTime = LocalDateTime.of(2026, 9, 2, 0, 0)
            )
        )
        val repository = FakeEventRepository(*events)
        val service = service(repository, RecordingMutationSink())

        assertEquals(
            linkedMapOf(
                java.time.LocalDate.of(2026, 8, 1) to 1,
                java.time.LocalDate.of(2026, 8, 7) to 2,
                java.time.LocalDate.of(2026, 8, 31) to 1
            ),
            service.observeMonthCounts(month).first()
        )
        assertEquals(
            month.atDay(1).atStartOfDay() to month.plusMonths(1).atDay(1).atStartOfDay(),
            repository.observedRanges.single()
        )
    }

    private fun service(repository: FakeEventRepository, mutations: RecordingMutationSink) =
        EventService(
            repository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            uidGenerator = SyncUidGenerator { uid },
            mutationSink = mutations
        )
}

private class FakeEventRepository(vararg initial: Event) : EventRepository {
    val values = initial.associateByTo(linkedMapOf()) { it.id }
    val observedRanges = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    var failWrites = false
    private var nextId = (values.keys.maxOrNull() ?: 0) + 1

    override suspend fun findById(id: Long): Event? = values[id]

    override suspend fun insert(event: Event): Event {
        check(!failWrites) { "write failed" }
        val inserted = event.copy(id = nextId++)
        values[inserted.id] = inserted
        return inserted
    }

    override suspend fun update(event: Event): Event {
        check(!failWrites) { "write failed" }
        check(values.containsKey(event.id)) { "missing event" }
        values[event.id] = event
        return event
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        observedRanges += start to end
        return flowOf(
            values.values.filter { event ->
                event.deletedAt == null && when (event.eventType) {
                    EventType.REMINDER -> !event.startTime.isBefore(start) &&
                        event.startTime.isBefore(end)

                    EventType.TIMESPAN -> event.startTime.isBefore(end) &&
                        requireNotNull(event.endTime).isAfter(start)
                }
            }
        )
    }
}

private class RecordingMutationSink : ScheduleMutationSink {
    val values = mutableListOf<ScheduleMutation>()

    override fun onMutation(mutation: ScheduleMutation) {
        values += mutation
    }
}

private class CommitBeforeReturnEventRepository(
    initial: Event,
    private val committed: CompletableDeferred<Unit>,
    private val allowResultDelivery: CompletableDeferred<Unit>
) : EventRepository {
    var value: Event = initial
        private set

    override suspend fun findById(id: Long): Event? = value.takeIf { it.id == id }

    override suspend fun insert(event: Event): Event = error("insert is not used")

    override suspend fun update(event: Event): Event {
        value = event
        committed.complete(Unit)
        allowResultDelivery.await()
        return event
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        flowOf(listOf(value))
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        }
    )
    return requireNotNull(outcome) { "test block suspended unexpectedly" }.getOrThrow()
}
