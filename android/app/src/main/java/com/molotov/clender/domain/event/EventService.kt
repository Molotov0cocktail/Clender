package com.molotov.clender.domain.event

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

fun interface SyncUidGenerator {
    fun generate(): String
}

fun interface ScheduleMutationSink {
    fun onMutation(mutation: ScheduleMutation)
}

sealed interface ScheduleMutation {
    data class Added(val event: Event) : ScheduleMutation

    data class Updated(val event: Event) : ScheduleMutation

    data class Deleted(val event: Event) : ScheduleMutation

    data class Batch(val mutations: List<ScheduleMutation>) : ScheduleMutation {
        init {
            require(mutations.isNotEmpty()) { "A mutation batch cannot be empty" }
        }
    }
}

sealed interface FieldUpdate<out T> {
    data object Unchanged : FieldUpdate<Nothing>

    data class Set<T>(val value: T) : FieldUpdate<T>
}

data class AddEventCommand(
    val eventType: EventType,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime? = null,
    val description: String = "",
    val estimatedDurationMinutes: Int = 0
)

data class EventPatch(
    val eventType: FieldUpdate<EventType> = FieldUpdate.Unchanged,
    val title: FieldUpdate<String> = FieldUpdate.Unchanged,
    val startTime: FieldUpdate<LocalDateTime> = FieldUpdate.Unchanged,
    val endTime: FieldUpdate<LocalDateTime?> = FieldUpdate.Unchanged,
    val description: FieldUpdate<String> = FieldUpdate.Unchanged,
    val estimatedDurationMinutes: FieldUpdate<Int> = FieldUpdate.Unchanged
) {
    fun isEmpty(): Boolean = eventType === FieldUpdate.Unchanged &&
        title === FieldUpdate.Unchanged &&
        startTime === FieldUpdate.Unchanged &&
        endTime === FieldUpdate.Unchanged &&
        description === FieldUpdate.Unchanged &&
        estimatedDurationMinutes === FieldUpdate.Unchanged
}

class InvalidEventPatchException : IllegalArgumentException("Event patch must not be empty")

class EventNotFoundException(id: Long) : NoSuchElementException("Event $id was not found")

class TombstonedEventException(id: Long) : IllegalStateException("Event $id is already deleted")

class EventService(
    private val repository: EventRepository,
    private val clock: Clock,
    private val uidGenerator: SyncUidGenerator,
    private val mutationSink: ScheduleMutationSink
) {
    suspend fun <T> runBatched(block: suspend (EventService) -> T): T {
        val collected = mutableListOf<ScheduleMutation>()
        val scoped = EventService(
            repository = repository,
            clock = clock,
            uidGenerator = uidGenerator,
            mutationSink = ScheduleMutationSink { mutation ->
                when (mutation) {
                    is ScheduleMutation.Batch -> collected += mutation.mutations
                    else -> collected += mutation
                }
            }
        )
        return try {
            block(scoped)
        } finally {
            if (collected.isNotEmpty()) {
                mutationSink.onMutation(ScheduleMutation.Batch(collected.toList()))
            }
        }
    }

    suspend fun add(command: AddEventCommand): Event {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val candidate = EventValidator.validateNew(
            Event(
                id = 0L,
                eventType = command.eventType,
                title = command.title,
                startTime = command.startTime,
                endTime = command.endTime,
                description = command.description,
                estimatedDurationMinutes = command.estimatedDurationMinutes,
                createdAt = now,
                syncUid = uidGenerator.generate(),
                updatedAt = now,
                deletedAt = null
            )
        )
        return mutationSink.persistAndNotify(
            operation = { repository.insert(candidate) },
            mutation = ScheduleMutation::Added
        )
    }

    suspend fun update(id: Long, patch: EventPatch): Event {
        requireNonEmptyPatch(patch)
        val current = findMutableEvent(id)

        val requestedType = patch.eventType.orElse(current.eventType)
        val requestedEnd = patch.endTime.orElse(current.endTime)
        val candidate = current.copy(
            eventType = requestedType,
            title = patch.title.orElse(current.title),
            startTime = patch.startTime.orElse(current.startTime),
            endTime = if (current.eventType != EventType.REMINDER &&
                requestedType == EventType.REMINDER
            ) {
                null
            } else {
                requestedEnd
            },
            description = patch.description.orElse(current.description),
            estimatedDurationMinutes = patch.estimatedDurationMinutes.orElse(
                current.estimatedDurationMinutes
            ),
            updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        )
        return mutationSink.persistAndNotify(
            operation = {
                repository.update(EventValidator.validatePersisted(candidate))
            },
            mutation = ScheduleMutation::Updated
        )
    }

    suspend fun delete(id: Long): Boolean {
        requireValidId(id)
        val current = repository.findById(id) ?: return false
        return if (current.deletedAt != null) {
            false
        } else {
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val tombstone = EventValidator.validatePersisted(
                current.copy(updatedAt = now, deletedAt = now)
            )
            mutationSink.persistAndNotify(
                operation = { repository.update(tombstone) },
                mutation = ScheduleMutation::Deleted
            )
            true
        }
    }

    fun observeDate(date: LocalDate): Flow<List<Event>> =
        observeRange(date.atStartOfDay(), date.plusDays(1).atStartOfDay())

    fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        require(start.isBefore(end)) { "Range start must precede range end" }
        return repository.observeRange(start, end)
    }

    fun observeMonthCounts(month: YearMonth): Flow<Map<LocalDate, Int>> {
        val monthStart = month.atDay(1)
        val monthEnd = month.plusMonths(1).atDay(1)
        return observeRange(monthStart.atStartOfDay(), monthEnd.atStartOfDay()).map { events ->
            buildMonthCounts(events, monthStart, monthEnd)
        }
    }

    private fun requireValidId(id: Long) {
        if (id <= 0L) throw EventValidationException("Event id must be positive")
    }

    private fun requireNonEmptyPatch(patch: EventPatch) {
        if (patch.isEmpty()) throw InvalidEventPatchException()
    }

    private suspend fun findMutableEvent(id: Long): Event {
        requireValidId(id)
        val current = repository.findById(id) ?: throw EventNotFoundException(id)
        if (current.deletedAt != null) throw TombstonedEventException(id)
        return current
    }
}

private suspend fun ScheduleMutationSink.persistAndNotify(
    operation: suspend () -> Event,
    mutation: (Event) -> ScheduleMutation
): Event = withContext(NonCancellable) {
    val stored = EventValidator.validatePersisted(operation())
    onMutation(mutation(stored))
    stored
}

private fun buildMonthCounts(
    events: List<Event>,
    monthStart: LocalDate,
    monthEndExclusive: LocalDate
): Map<LocalDate, Int> {
    val counts = sortedMapOf<LocalDate, Int>()
    for (event in events) {
        when (event.eventType) {
            EventType.REMINDER -> incrementIfInMonth(
                counts,
                event.startTime.toLocalDate(),
                monthStart,
                monthEndExclusive
            )

            EventType.TIMESPAN -> addTimespanDays(counts, event, monthStart, monthEndExclusive)
        }
    }
    return counts
}

private fun addTimespanDays(
    counts: MutableMap<LocalDate, Int>,
    event: Event,
    monthStart: LocalDate,
    monthEndExclusive: LocalDate
) {
    val end = event.endTime ?: return
    var date = maxOf(event.startTime.toLocalDate(), monthStart)
    while (date.isBefore(monthEndExclusive) && date.atStartOfDay().isBefore(end)) {
        val dayEnd = date.plusDays(1).atStartOfDay()
        if (event.startTime.isBefore(dayEnd) && end.isAfter(date.atStartOfDay())) {
            counts[date] = counts.getOrDefault(date, 0) + 1
        }
        date = date.plusDays(1)
    }
}

private fun incrementIfInMonth(
    counts: MutableMap<LocalDate, Int>,
    date: LocalDate,
    monthStart: LocalDate,
    monthEndExclusive: LocalDate
) {
    if (!date.isBefore(monthStart) && date.isBefore(monthEndExclusive)) {
        counts[date] = counts.getOrDefault(date, 0) + 1
    }
}

private fun <T> FieldUpdate<T>.orElse(current: T): T = when (this) {
    FieldUpdate.Unchanged -> current
    is FieldUpdate.Set -> value
}
