package com.molotov.clender.sync

import com.molotov.clender.core.model.Event
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.domain.sync.MergeEngine
import com.molotov.clender.domain.sync.SyncRecordStore

class RoomSyncRecordStore(
    private val repository: RoomEventRepository,
    private val mergeEngine: MergeEngine = MergeEngine()
) : SyncRecordStore {
    override suspend fun snapshot(): List<Event> = repository.syncSnapshot()

    override suspend fun applyRemote(events: List<Event>): Boolean =
        repository.applyRemote(events, mergeEngine::candidateWins)
}
