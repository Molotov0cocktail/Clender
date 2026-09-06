package com.molotov.clender.domain.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.core.model.Event
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.sync.RoomSyncRecordStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class SyncServiceRoomIntegrationTest {
    private lateinit var firstDatabase: ClenderDatabase
    private lateinit var secondDatabase: ClenderDatabase
    private lateinit var firstRepository: RoomEventRepository
    private lateinit var secondRepository: RoomEventRepository
    private val codec = WebDavDocumentCodec()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        firstDatabase = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        secondDatabase = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        firstRepository = RoomEventRepository(firstDatabase)
        secondRepository = RoomEventRepository(secondDatabase)
    }

    @After
    fun tearDown() {
        firstDatabase.close()
        secondDatabase.close()
    }

    @Test
    fun twoIsolatedDatabasesConvergeForLocalRemoteUnionAndTombstone() = runBlocking {
        val first = firstRepository.insert(syncEvent(uid = syncUid(1), title = "first"))
        secondRepository.insert(syncEvent(uid = syncUid(2), title = "second"))
        val remote = InMemoryRemote(codec, emptyList(), exists = false)
        val firstService = SyncService(
            RoomSyncRecordStore(firstRepository),
            remote,
            codec,
            MergeEngine()
        )
        val secondService = SyncService(
            RoomSyncRecordStore(secondRepository),
            remote,
            codec,
            MergeEngine()
        )

        firstService.sync()
        secondService.sync()
        firstService.sync()
        val deletedAt = first.updatedAt.plusSeconds(1)
        RoomSyncRecordStore(firstRepository).applyRemote(
            listOf(first.copy(updatedAt = deletedAt, deletedAt = deletedAt))
        )
        firstService.sync()
        secondService.sync()

        val firstSnapshot = firstRepository.syncSnapshot().normalizedForComparison()
        val secondSnapshot = secondRepository.syncSnapshot().normalizedForComparison()
        assertEquals(firstSnapshot, secondSnapshot)
        assertEquals(firstSnapshot, remote.records.normalizedForComparison())
        assertTrue(firstSnapshot.single { it.syncUid == syncUid(1) }.deletedAt != null)
    }

    @Test
    fun remoteBatchFailureRollsBackEveryRecordBeforeAnyUpload() = runBlocking {
        firstDatabase.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_sync_batch BEFORE INSERT ON events
            WHEN NEW.sync_uid = '${syncUid(7)}'
            BEGIN SELECT RAISE(ABORT, 'forced rollback'); END
            """.trimIndent()
        )
        val remote = InMemoryRemote(
            codec,
            listOf(
                syncEvent(uid = syncUid(6), title = "must-roll-back"),
                syncEvent(uid = syncUid(7), title = "forced-failure")
            ),
            exists = true
        )

        assertThrows(SQLiteException::class.java) {
            runBlocking {
                SyncService(
                    RoomSyncRecordStore(firstRepository),
                    remote,
                    codec,
                    MergeEngine()
                ).sync()
            }
        }
        assertEquals(emptyList<Event>(), firstRepository.syncSnapshot())
        assertEquals(0, remote.putCalls)
    }

    @Test
    fun concurrentNewerLocalUpdateAfterMergeIsNotOverwrittenAndIsUploaded() = runBlocking {
        val original = firstRepository.insert(
            syncEvent(
                uid = syncUid(8),
                title = "before-fetch",
                updatedAt = Instant.parse("2026-08-03T01:00:00Z")
            )
        )
        val remoteOlderThanRace = original.copy(
            id = 0L,
            title = "remote-mid-sync",
            updatedAt = Instant.parse("2026-08-03T02:00:00Z")
        )
        val concurrentLocalWinner = original.copy(
            title = "local-after-snapshot",
            updatedAt = Instant.parse("2026-08-03T03:00:00Z")
        )
        val store = ConcurrentUpdateStore(
            RoomSyncRecordStore(firstRepository),
            firstRepository,
            concurrentLocalWinner
        )
        val remote = InMemoryRemote(codec, listOf(remoteOlderThanRace), exists = true)

        val result = SyncService(store, remote, codec, MergeEngine()).sync()

        assertEquals("local-after-snapshot", firstRepository.syncSnapshot().single().title)
        assertEquals("local-after-snapshot", remote.records.single().title)
        assertTrue(result.uploaded)
        assertFalse(result.localChanged)
    }

    @Test
    fun remoteApplyUsesCanonicalCodePointWinnerForEqualTimestamps() = runBlocking {
        val stamp = Instant.parse("2026-08-03T02:00:00Z")
        val localWinner = firstRepository.insert(
            syncEvent(
                uid = syncUid(9),
                title = "A\nZ",
                description = "涓枃",
                updatedAt = stamp
            )
        )
        val localLoser = firstRepository.insert(
            syncEvent(
                uid = syncUid(10),
                title = "AA",
                description = "涓枃",
                updatedAt = stamp
            )
        )
        val store = RoomSyncRecordStore(firstRepository)

        assertFalse(
            store.applyRemote(listOf(localWinner.copy(id = 0L, title = "AA")))
        )
        assertTrue(
            store.applyRemote(listOf(localLoser.copy(id = 0L, title = "A\nZ")))
        )

        val snapshot = firstRepository.syncSnapshot().associateBy(Event::syncUid)
        assertEquals("A\nZ", snapshot.getValue(syncUid(9)).title)
        assertEquals("A\nZ", snapshot.getValue(syncUid(10)).title)
    }
}

private class ConcurrentUpdateStore(
    private val delegate: RoomSyncRecordStore,
    private val repository: RoomEventRepository,
    private val concurrentWinner: Event
) : SyncRecordStore {
    private var snapshotCalls = 0

    override suspend fun snapshot(): List<Event> {
        val snapshot = delegate.snapshot()
        snapshotCalls += 1
        if (snapshotCalls == 1) repository.update(concurrentWinner)
        return snapshot
    }

    override suspend fun applyRemote(events: List<Event>): Boolean = delegate.applyRemote(events)
}

private fun List<Event>.normalizedForComparison(): List<Event> =
    sortedBy { it.syncUid }.map { it.copy(id = 0L) }
