package com.molotov.clender.domain.event

import com.molotov.clender.core.model.Event
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

interface EventRepository {
    suspend fun findById(id: Long): Event?

    suspend fun insert(event: Event): Event

    suspend fun update(event: Event): Event

    fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>>
}
