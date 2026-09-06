package com.molotov.clender.domain.sync

import com.molotov.clender.core.model.Event
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncServiceTest {
    private val codec = WebDavDocumentCodec()

    @Test
    fun appliesUnionThenRereadsLocalBeforeCanonicalConditionalUpload() = runBlocking {
        val local = syncEvent(uid = syncUid(1), title = "local")
        val remoteOnly = syncEvent(uid = syncUid(2), title = "remote")
        val concurrent = syncEvent(uid = syncUid(3), title = "concurrent")
        val store = FakeStore(listOf(local)).apply { injectOnSecondSnapshot = concurrent }
        val remote = InMemoryRemote(codec, listOf(remoteOnly), exists = true)

        val result = SyncService(store, remote, codec, MergeEngine()).sync()

        assertEquals(
            setOf(syncUid(1), syncUid(2), syncUid(3)),
            remote.records.mapTo(mutableSetOf()) { it.syncUid }
        )
        assertTrue(result.localChanged)
        assertTrue(result.uploaded)
        assertEquals(3, result.eventCount)
        assertEquals(1, store.applyCalls)
    }

    @Test
    fun identicalExistingRemoteAvoidsRedundantPut() = runBlocking {
        val localRecord = syncEvent().copy(id = 42L)
        val remoteRecord = localRecord.copy(id = 0L)
        val store = FakeStore(listOf(localRecord))
        val remote = InMemoryRemote(codec, listOf(remoteRecord), exists = true)

        val result = SyncService(store, remote, codec, MergeEngine()).sync()

        assertFalse(result.localChanged)
        assertFalse(result.uploaded)
        assertEquals(0, remote.putCalls)
    }

    @Test
    fun missingRemoteUsesIfNoneMatchAndCreatesCanonicalDocument() = runBlocking {
        val store = FakeStore(listOf(syncEvent()))
        val remote = InMemoryRemote(codec, emptyList(), exists = false)

        val result = SyncService(store, remote, codec, MergeEngine()).sync()

        assertTrue(result.uploaded)
        assertEquals(null, remote.lastPutEtag)
        assertFalse(remote.lastPutExisted)
        assertEquals(store.records.map { it.copy(id = 0L) }, remote.records)
    }

    @Test
    fun conflictRefetchesAndRetriesExactlyOnce() = runBlocking {
        val store = FakeStore(listOf(syncEvent()))
        val remote = InMemoryRemote(codec, emptyList(), exists = true).apply {
            conflictsRemaining = 1
            onConflict = {
                records = listOf(syncEvent(uid = syncUid(2), title = "concurrent remote"))
            }
        }

        val result = SyncService(store, remote, codec, MergeEngine()).sync()

        assertEquals(2, remote.fetchCalls)
        assertEquals(2, remote.putCalls)
        assertEquals(
            setOf(syncUid(1), syncUid(2)),
            remote.records.mapTo(mutableSetOf()) { event -> event.syncUid }
        )
        assertEquals(2, result.eventCount)
    }

    @Test
    fun secondConflictFailsWithoutUnboundedRetry() {
        val store = FakeStore(listOf(syncEvent()))
        val remote = InMemoryRemote(codec, emptyList(), exists = true).apply {
            conflictsRemaining = 2
        }

        assertThrows(WebDavConflictException::class.java) {
            runBlocking { SyncService(store, remote, codec, MergeEngine()).sync() }
        }
        assertEquals(2, remote.fetchCalls)
        assertEquals(2, remote.putCalls)
    }

    @Test
    fun putFailureAfterRemoteApplyUsesSafeVisibleChangeFailure() {
        val store = FakeStore(listOf(syncEvent(uid = syncUid(1), title = "local")))
        val remote = InMemoryRemote(
            codec,
            listOf(syncEvent(uid = syncUid(2), title = "remote")),
            exists = true
        ).apply {
            putFailure = TestBoundedFailure(
                SyncFailureKind.TRANSPORT,
                TEST_HTTP_UNAVAILABLE,
                "private transport detail"
            )
        }

        val error = assertThrows(SyncFailureException::class.java) {
            runBlocking { SyncService(store, remote, codec, MergeEngine()).sync() }
        }

        assertTrue(error.remoteVisibleChanged)
        assertEquals(SyncFailureKind.TRANSPORT, error.kind)
        assertEquals(TEST_HTTP_UNAVAILABLE, error.httpStatusCode)
        assertEquals(null, error.cause)
        assertFalse(error.message.orEmpty().contains("private transport detail"))
        assertEquals(1, remote.putCalls)
        assertEquals(setOf(syncUid(1), syncUid(2)), store.records.map { it.syncUid }.toSet())
    }

    @Test
    fun exhaustedConflictsAfterRemoteApplyUseSafeVisibleChangeFailure() {
        val store = FakeStore(listOf(syncEvent(uid = syncUid(1), title = "local")))
        val remote = InMemoryRemote(
            codec,
            listOf(syncEvent(uid = syncUid(2), title = "remote")),
            exists = true
        ).apply {
            conflictsRemaining = 2
        }

        val error = assertThrows(SyncFailureException::class.java) {
            runBlocking { SyncService(store, remote, codec, MergeEngine()).sync() }
        }

        assertTrue(error.remoteVisibleChanged)
        assertEquals(SyncFailureKind.CONFLICT, error.kind)
        assertEquals(null, error.httpStatusCode)
        assertEquals(null, error.cause)
        assertEquals(2, remote.fetchCalls)
        assertEquals(2, remote.putCalls)
    }

    @Test
    fun newestRemoteTombstoneCannotBeResurrectedByOlderLocalCopy() = runBlocking {
        val older = Instant.parse("2026-08-03T01:00:00Z")
        val newer = Instant.parse("2026-08-03T02:00:00Z")
        val active = syncEvent(updatedAt = older)
        val tombstone = syncEvent(updatedAt = newer, deletedAt = newer)
        val store = FakeStore(listOf(active))
        val remote = InMemoryRemote(codec, listOf(tombstone), exists = true)

        SyncService(store, remote, codec, MergeEngine()).sync()

        assertEquals(newer, store.records.single().deletedAt)
        assertEquals(newer, remote.records.single().deletedAt)
    }
}

internal class FakeStore(initial: List<Event>) : SyncRecordStore {
    var records: List<Event> = initial
    var applyCalls: Int = 0
    var snapshotCalls: Int = 0
    var injectOnSecondSnapshot: Event? = null

    override suspend fun snapshot(): List<Event> {
        snapshotCalls += 1
        if (snapshotCalls == 2) {
            injectOnSecondSnapshot?.let { records = records + it }
        }
        return records
    }

    override suspend fun applyRemote(events: List<Event>): Boolean {
        applyCalls += 1
        val before = records.filter { it.deletedAt == null }.map { it.visibleSignature() }
        records = events
        val after = records.filter { it.deletedAt == null }.map { it.visibleSignature() }
        return before != after
    }
}

internal class InMemoryRemote(
    private val codec: WebDavDocumentCodec,
    initial: List<Event>,
    exists: Boolean
) : WebDavRemote {
    var records: List<Event> = initial.map { it.copy(id = 0L) }
    var exists: Boolean = exists
    var etag: String? = if (exists) "\"v1\"" else null
    var fetchCalls: Int = 0
    var putCalls: Int = 0
    var conflictsRemaining: Int = 0
    var putFailure: RuntimeException? = null
    var onConflict: (() -> Unit)? = null
    var lastPutEtag: String? = null
    var lastPutExisted: Boolean = false

    override suspend fun fetch(): RemoteSnapshot {
        fetchCalls += 1
        return RemoteSnapshot(records, etag, exists)
    }

    override suspend fun put(payload: ByteArray, etag: String?, exists: Boolean) {
        putCalls += 1
        lastPutEtag = etag
        lastPutExisted = exists
        putFailure?.let { throw it }
        if (conflictsRemaining > 0) {
            conflictsRemaining -= 1
            onConflict?.invoke()
            this.etag = "\"conflict-$putCalls\""
            throw WebDavConflictException("conflict")
        }
        if (exists) require(etag == this.etag) else require(etag == null)
        records = codec.decode(payload)
        this.exists = true
        this.etag = "\"v${putCalls + 1}\""
    }
}

private fun Event.visibleSignature(): List<Any?> = listOf(
    eventType,
    title,
    startTime,
    endTime,
    description,
    estimatedDurationMinutes
)

private class TestBoundedFailure(
    override val syncFailureKind: SyncFailureKind,
    override val statusCode: Int?,
    message: String
) : RuntimeException(message),
    BoundedSyncFailure

private const val TEST_HTTP_UNAVAILABLE = 503
