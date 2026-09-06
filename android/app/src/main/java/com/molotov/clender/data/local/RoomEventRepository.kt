package com.molotov.clender.data.local

import androidx.room.withTransaction
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventValidator
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomEventRepository(private val database: ClenderDatabase) : EventRepository {
    private val dao: EventDao = database.eventDao()

    override suspend fun findById(id: Long): Event? {
        require(id > 0) { "Event ID must be positive" }
        return dao.findById(id)?.toDomain()
    }

    suspend fun findBySyncUid(syncUid: String): Event? {
        require(SYNC_UID.matches(syncUid)) { "Invalid sync UID" }
        return dao.findBySyncUid(syncUid)?.toDomain()
    }

    override suspend fun insert(event: Event): Event {
        val normalized = normalizeNew(event)
        val id = dao.insert(normalized.toEntity())
        return normalized.copy(id = id)
    }

    override suspend fun update(event: Event): Event {
        val normalized = normalizePersisted(event)
        check(dao.update(normalized.toEntity()) == 1) { "Event does not exist" }
        return normalized
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        require(start < end) { "Range end must be after start" }
        return dao.observeOverlapping(start, end).map { entities ->
            entities.map(EventEntity::toDomain)
        }
    }

    fun observeVisible(): Flow<List<Event>> =
        dao.observeVisible().map { entities -> entities.map(EventEntity::toDomain) }

    suspend fun findOverlapping(start: LocalDateTime, end: LocalDateTime): List<Event> {
        require(start < end) { "Range end must be after start" }
        return dao.findOverlapping(start, end).map(EventEntity::toDomain)
    }

    suspend fun syncSnapshot(): List<Event> = dao.syncSnapshot().map(EventEntity::toDomain)

    suspend fun applyRemote(
        events: List<Event>,
        candidateWins: (candidate: Event, current: Event) -> Boolean
    ): Boolean {
        require(events.map(Event::syncUid).distinct().size == events.size) {
            "Remote batch contains duplicate sync UIDs"
        }
        return database.withTransaction {
            var visibleChanged = false
            events.forEach { incoming ->
                val existing = dao.findBySyncUid(incoming.syncUid)?.toDomain()
                if (existing != null && !candidateWins(incoming, existing)) {
                    return@forEach
                }
                val candidate = incoming.copy(id = existing?.id ?: 0L)
                val stored = if (existing == null) {
                    normalizeNew(candidate)
                } else {
                    normalizePersisted(candidate)
                }
                visibleChanged =
                    visibleChanged || visibleSignature(existing) != visibleSignature(stored)
                if (existing == null) {
                    dao.insert(stored.toEntity())
                } else {
                    check(dao.update(stored.toEntity()) == 1)
                }
            }
            visibleChanged
        }
    }

    private companion object {
        val SYNC_UID = Regex("^[0-9a-f]{32}$")
    }
}

private fun normalizeNew(event: Event): Event =
    EventValidator.validateNew(normalizeInstants(EventValidator.validateNew(event)))

private fun normalizePersisted(event: Event): Event =
    EventValidator.validatePersisted(normalizeInstants(EventValidator.validatePersisted(event)))

private fun normalizeInstants(event: Event): Event = event.copy(
    createdAt = event.createdAt.truncatedTo(ChronoUnit.MICROS),
    updatedAt = event.updatedAt.truncatedTo(ChronoUnit.MICROS),
    deletedAt = event.deletedAt?.truncatedTo(ChronoUnit.MICROS)
)

private fun visibleSignature(event: Event?): VisibleSignature? =
    event?.takeIf { it.deletedAt == null }?.let {
        VisibleSignature(
            eventType = it.eventType,
            title = it.title,
            startTime = it.startTime,
            endTime = it.endTime,
            description = it.description,
            estimatedDurationMinutes = it.estimatedDurationMinutes
        )
    }

private data class VisibleSignature(
    val eventType: EventType,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime?,
    val description: String,
    val estimatedDurationMinutes: Int
)
