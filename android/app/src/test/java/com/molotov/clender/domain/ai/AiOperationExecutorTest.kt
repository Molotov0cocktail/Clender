package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.FieldUpdate
import com.molotov.clender.domain.event.ScheduleMutation
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiOperationExecutorTest {
    @Test
    fun scheduleOperationsRunInOrderThroughEventServiceAndBatchOnce() = runBlocking {
        val repository = AiEventRepository()
        val mutations = RecordingAiMutationSink()
        val executor = AiOperationExecutor(eventService(repository, mutations))

        val report = executor.execute(
            listOf(
                AiOperation.Add(
                    AddEventCommand(
                        eventType = EventType.TIMESPAN,
                        title = "会议",
                        startTime = LocalDateTime.of(2026, 8, 9, 9, 0),
                        endTime = LocalDateTime.of(2026, 8, 9, 10, 0)
                    )
                ),
                AiOperation.Update(
                    eventId = 1,
                    patch = EventPatch(eventType = FieldUpdate.Set(EventType.REMINDER))
                ),
                AiOperation.Delete(1),
                AiOperation.Reply("完成")
            )
        )

        val stored = requireNotNull(repository.values[1])
        assertEquals(EventType.REMINDER, stored.eventType)
        assertNull(stored.endTime)
        assertEquals(stored.updatedAt, stored.deletedAt)
        assertEquals(listOf("add", "update", "delete", "reply"), report.outcomes.map { it.action })
        assertTrue(report.outcomes.all { it.success })
        assertEquals(listOf("完成"), report.replies)
        assertTrue(report.scheduleChanged)
        val batch = mutations.values.single() as ScheduleMutation.Batch
        assertEquals(3, batch.mutations.size)
    }

    @Test
    fun failedScheduleOperationIsContainedAndLaterReplyStillRuns() = runBlocking {
        val repository = AiEventRepository()
        val mutations = RecordingAiMutationSink()
        val executor = AiOperationExecutor(eventService(repository, mutations))

        val report = executor.execute(
            listOf(
                AiOperation.Update(
                    eventId = 999,
                    patch = EventPatch(title = FieldUpdate.Set("missing"))
                ),
                AiOperation.Reply("仍可回复")
            )
        )

        assertFalse(report.outcomes.first().success)
        assertTrue(report.outcomes.last().success)
        assertEquals(listOf("仍可回复"), report.replies)
        assertFalse(report.scheduleChanged)
        assertFalse(report.outcomes.first().message.contains("Exception"))
        assertFalse(report.outcomes.first().message.contains("missing"))
        assertTrue(mutations.values.isEmpty())
    }

    @Test
    fun directDeleteNeedsNoConfirmationCallbackAndMissingDeleteDoesNotRefresh() = runBlocking {
        val repository = AiEventRepository()
        val mutations = RecordingAiMutationSink()
        val executor = AiOperationExecutor(eventService(repository, mutations))

        val report = executor.execute(listOf(AiOperation.Delete(404)))

        assertFalse(report.outcomes.single().success)
        assertFalse(report.scheduleChanged)
        assertEquals(0, repository.writeCalls)
        assertTrue(mutations.values.isEmpty())
    }

    @Test
    fun cancellationPropagatesWithoutContinuingOrRefreshing() {
        val repository = AiEventRepository().apply {
            nextFailure = CancellationException("backgrounded")
        }
        val mutations = RecordingAiMutationSink()
        val executor = AiOperationExecutor(eventService(repository, mutations))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                executor.execute(
                    listOf(
                        AiOperation.Add(
                            AddEventCommand(
                                eventType = EventType.REMINDER,
                                title = "cancelled",
                                startTime = LocalDateTime.of(2026, 8, 9, 9, 0)
                            )
                        ),
                        AiOperation.Reply("must not continue")
                    )
                )
            }
        }

        assertEquals(1, repository.writeCalls)
        assertTrue(mutations.values.isEmpty())
    }

    @Test
    fun cancellationAfterSuccessPublishesPriorBatchAndStopsRemainingOperations() {
        val repository = AiEventRepository().apply {
            failOnWriteCall = 2
            nextFailure = CancellationException("backgrounded")
        }
        val mutations = RecordingAiMutationSink()
        val executor = AiOperationExecutor(eventService(repository, mutations))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                executor.execute(
                    listOf(
                        reminder("first"),
                        reminder("cancelled"),
                        AiOperation.Reply("must not continue")
                    )
                )
            }
        }

        assertEquals(2, repository.writeCalls)
        assertEquals(listOf("first"), repository.values.values.map(Event::title))
        val batch = mutations.values.single() as ScheduleMutation.Batch
        assertEquals(1, batch.mutations.size)
    }

    private fun reminder(title: String) = AiOperation.Add(
        AddEventCommand(
            eventType = EventType.REMINDER,
            title = title,
            startTime = LocalDateTime.of(2026, 8, 9, 9, 0)
        )
    )

    private fun eventService(
        repository: AiEventRepository,
        mutations: RecordingAiMutationSink
    ): EventService = EventService(
        repository = repository,
        clock = Clock.fixed(Instant.parse("2026-08-09T02:00:00Z"), ZoneOffset.UTC),
        uidGenerator = SyncUidGenerator { "0123456789abcdef0123456789abcdef" },
        mutationSink = mutations
    )
}

private class RecordingAiMutationSink : ScheduleMutationSink {
    val values = mutableListOf<ScheduleMutation>()

    override fun onMutation(mutation: ScheduleMutation) {
        values += mutation
    }
}

private class AiEventRepository : EventRepository {
    val values = linkedMapOf<Long, Event>()
    var writeCalls = 0
    var nextFailure: RuntimeException? = null
    var failOnWriteCall = 1
    private var nextId = 1L

    override suspend fun findById(id: Long): Event? = values[id]

    override suspend fun insert(event: Event): Event {
        writeCalls += 1
        nextFailure?.takeIf { writeCalls == failOnWriteCall }?.let { failure ->
            nextFailure = null
            throw failure
        }
        return event.copy(id = nextId++).also { values[it.id] = it }
    }

    override suspend fun update(event: Event): Event {
        writeCalls += 1
        check(values.containsKey(event.id))
        values[event.id] = event
        return event
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        flowOf(values.values.toList())
}
