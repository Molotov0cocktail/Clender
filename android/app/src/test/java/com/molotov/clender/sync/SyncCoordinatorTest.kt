package com.molotov.clender.sync

import com.molotov.clender.domain.sync.FakeStore
import com.molotov.clender.domain.sync.MergeEngine
import com.molotov.clender.domain.sync.RemoteSnapshot
import com.molotov.clender.domain.sync.SyncFailureException
import com.molotov.clender.domain.sync.SyncFailureKind
import com.molotov.clender.domain.sync.SyncRecordStore
import com.molotov.clender.domain.sync.SyncResult
import com.molotov.clender.domain.sync.SyncService
import com.molotov.clender.domain.sync.WebDavDocumentCodec
import com.molotov.clender.domain.sync.WebDavRemote
import com.molotov.clender.domain.sync.syncEvent
import com.molotov.clender.domain.sync.syncUid
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: SyncCoordinator? = null

    @After
    fun tearDown() {
        runBlocking { coordinator?.shutdown() }
        scope.cancel()
    }

    @Test
    fun exposesOnlyConfirmedLocalManualAndForegroundTriggers() {
        assertEquals(
            setOf("LOCAL_CHANGE", "MANUAL", "FOREGROUND"),
            SyncTrigger.entries.mapTo(mutableSetOf()) { it.name }
        )
    }

    @Test
    fun disabledConfigurationRejectsEveryTriggerWithoutLaunchingWork() = runBlocking {
        val runs = AtomicInteger()
        coordinator = coordinator(enabled = { false }) {
            runs.incrementAndGet()
            SyncResult(localChanged = false, uploaded = false, eventCount = 0)
        }

        SyncTrigger.entries.forEach { trigger ->
            assertFalse(coordinator!!.request(trigger))
        }
        coordinator!!.awaitIdle()
        assertEquals(0, runs.get())
    }

    @Test
    fun cancelledParentScopeRejectsWorkWithoutLeavingANonStartingWorker() = runBlocking {
        val runs = AtomicInteger()
        scope.cancel()
        coordinator = coordinator {
            runs.incrementAndGet()
            SyncResult(localChanged = false, uploaded = false, eventCount = 0)
        }

        assertFalse(coordinator!!.request(SyncTrigger.MANUAL))
        withTimeout(500) { coordinator!!.awaitIdle() }

        assertEquals(0, runs.get())
    }

    @Test
    fun allRequestsWhileBusyCoalesceIntoExactlyOnePendingFollowUp() = runBlocking {
        val runs = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        coordinator = coordinator {
            when (runs.incrementAndGet()) {
                1 -> {
                    firstStarted.complete(Unit)
                    firstRelease.await()
                }

                2 -> {
                    secondStarted.complete(Unit)
                    secondRelease.await()
                }

                else -> error("pending work was not coalesced")
            }
            SyncResult(localChanged = false, uploaded = true, eventCount = 1)
        }

        assertTrue(coordinator!!.request(SyncTrigger.LOCAL_CHANGE))
        firstStarted.await()
        assertFalse(coordinator!!.request(SyncTrigger.LOCAL_CHANGE))
        assertFalse(coordinator!!.request(SyncTrigger.MANUAL))
        assertFalse(coordinator!!.request(SyncTrigger.FOREGROUND))
        firstRelease.complete(Unit)
        secondStarted.await()
        secondRelease.complete(Unit)
        coordinator!!.awaitIdle()

        assertEquals(2, runs.get())
    }

    @Test
    fun remoteVisibleRefreshCallbackNeverQueuesAnUploadLoop() = runBlocking {
        val runs = AtomicInteger()
        val refreshes = AtomicInteger()
        var reentrantRequestAccepted: Boolean? = null
        coordinator = coordinator(
            onRemoteVisibleChanged = {
                refreshes.incrementAndGet()
                reentrantRequestAccepted = coordinator!!.request(SyncTrigger.LOCAL_CHANGE)
            }
        ) {
            runs.incrementAndGet()
            SyncResult(localChanged = true, uploaded = false, eventCount = 1)
        }

        assertTrue(coordinator!!.request(SyncTrigger.MANUAL))
        coordinator!!.awaitIdle()

        assertEquals(1, runs.get())
        assertEquals(1, refreshes.get())
        assertFalse(checkNotNull(reentrantRequestAccepted))
    }

    @Test
    fun failureAfterRemoteApplyRefreshesExactlyOnceWithoutUploadLoop() = runBlocking {
        val runs = AtomicInteger()
        val refreshes = AtomicInteger()
        val failures = AtomicInteger()
        var reentrantRequestAccepted: Boolean? = null
        coordinator = SyncCoordinator(
            scope = scope,
            isEnabled = { true },
            runSync = {
                runs.incrementAndGet()
                throw SyncFailureException(
                    remoteVisibleChanged = true,
                    kind = SyncFailureKind.TRANSPORT,
                    httpStatusCode = TEST_HTTP_UNAVAILABLE
                )
            },
            onRemoteVisibleChanged = {
                refreshes.incrementAndGet()
                reentrantRequestAccepted = coordinator!!.request(SyncTrigger.LOCAL_CHANGE)
            },
            onFailure = { failures.incrementAndGet() }
        )

        assertTrue(coordinator!!.request(SyncTrigger.MANUAL))
        coordinator!!.awaitIdle()

        assertEquals(1, runs.get())
        assertEquals(1, refreshes.get())
        assertEquals(1, failures.get())
        assertFalse(checkNotNull(reentrantRequestAccepted))
    }

    @Test
    fun cancellationAfterRemoteApplyRefreshesOnceAndStillPropagatesCancellation() = runBlocking {
        val putStarted = CompletableDeferred<Unit>()
        val putCancelled = CompletableDeferred<Unit>()
        val refreshes = AtomicInteger()
        val failures = AtomicInteger()
        var reentrantRequestAccepted: Boolean? = null
        val store = FakeStore(listOf(syncEvent(uid = syncUid(1), title = "local")))
        val remote = object : WebDavRemote {
            override suspend fun fetch(): RemoteSnapshot = RemoteSnapshot(
                listOf(syncEvent(uid = syncUid(2), title = "remote")),
                etag = "\"v1\"",
                exists = true
            )

            override suspend fun put(payload: ByteArray, etag: String?, exists: Boolean) {
                putStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    putCancelled.complete(Unit)
                }
            }
        }
        coordinator = SyncCoordinator(
            scope = scope,
            isEnabled = { true },
            runSync = {
                SyncService(store, remote, WebDavDocumentCodec(), MergeEngine()).sync()
            },
            onRemoteVisibleChanged = {
                refreshes.incrementAndGet()
                reentrantRequestAccepted = coordinator!!.request(SyncTrigger.LOCAL_CHANGE)
            },
            onFailure = { failures.incrementAndGet() }
        )

        assertTrue(coordinator!!.request(SyncTrigger.MANUAL))
        putStarted.await()
        coordinator!!.shutdown()
        putCancelled.await()

        assertEquals(setOf(syncUid(1), syncUid(2)), store.records.map { it.syncUid }.toSet())
        assertEquals(1, refreshes.get())
        assertEquals(0, failures.get())
        assertFalse(checkNotNull(reentrantRequestAccepted))
        assertFalse(coordinator!!.request(SyncTrigger.FOREGROUND))
    }

    @Test
    fun cancellationBetweenRemoteCommitAndResultDeliveryStillRefreshesOnce() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val allowResultDelivery = CompletableDeferred<Unit>()
        val refreshes = AtomicInteger()
        val failures = AtomicInteger()
        val puts = AtomicInteger()
        var reentrantRequestAccepted: Boolean? = null
        val store = CommitBeforeReturnStore(
            initial = listOf(syncEvent(uid = syncUid(1), title = "local")),
            committed = committed,
            allowResultDelivery = allowResultDelivery
        )
        val remote = object : WebDavRemote {
            override suspend fun fetch(): RemoteSnapshot = RemoteSnapshot(
                listOf(syncEvent(uid = syncUid(2), title = "remote")),
                etag = "\"v1\"",
                exists = true
            )

            override suspend fun put(payload: ByteArray, etag: String?, exists: Boolean) {
                puts.incrementAndGet()
            }
        }
        coordinator = SyncCoordinator(
            scope = scope,
            isEnabled = { true },
            runSync = {
                SyncService(store, remote, WebDavDocumentCodec(), MergeEngine()).sync()
            },
            onRemoteVisibleChanged = {
                refreshes.incrementAndGet()
                reentrantRequestAccepted = coordinator!!.request(SyncTrigger.LOCAL_CHANGE)
            },
            onFailure = { failures.incrementAndGet() }
        )

        assertTrue(coordinator!!.request(SyncTrigger.MANUAL))
        committed.await()
        scope.cancel()
        allowResultDelivery.complete(Unit)
        withTimeout(500) { coordinator!!.awaitIdle() }

        assertEquals(setOf(syncUid(1), syncUid(2)), store.records.map { it.syncUid }.toSet())
        assertEquals(0, puts.get())
        assertEquals(1, refreshes.get())
        assertEquals(0, failures.get())
        assertFalse(checkNotNull(reentrantRequestAccepted))
        assertFalse(coordinator!!.request(SyncTrigger.FOREGROUND))
    }

    @Test
    fun failureReturnsToIdleAndAllowsALaterForegroundAttempt() = runBlocking {
        val runs = AtomicInteger()
        coordinator = coordinator {
            if (runs.incrementAndGet() == 1) error("bounded failure")
            SyncResult(localChanged = false, uploaded = false, eventCount = 0)
        }

        assertTrue(coordinator!!.request(SyncTrigger.MANUAL))
        coordinator!!.awaitIdle()
        assertTrue(coordinator!!.request(SyncTrigger.FOREGROUND))
        coordinator!!.awaitIdle()

        assertEquals(2, runs.get())
    }

    @Test
    fun pendingFollowUpRechecksConfigurationAndShutdownCancelsAndRejectsWork() = runBlocking {
        var enabled = true
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        coordinator = coordinator(enabled = { enabled }) {
            runs.incrementAndGet()
            started.complete(Unit)
            release.await()
            SyncResult(localChanged = false, uploaded = true, eventCount = 1)
        }

        assertTrue(coordinator!!.request(SyncTrigger.LOCAL_CHANGE))
        started.await()
        assertFalse(coordinator!!.request(SyncTrigger.LOCAL_CHANGE))
        enabled = false
        release.complete(Unit)
        coordinator!!.awaitIdle()
        assertEquals(1, runs.get())

        coordinator!!.shutdown()
        assertFalse(coordinator!!.request(SyncTrigger.MANUAL))
    }

    @Test
    fun shutdownCancelsActiveWorkAndDiscardsPendingRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        coordinator = coordinator {
            runs.incrementAndGet()
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        assertTrue(coordinator!!.request(SyncTrigger.MANUAL))
        started.await()
        assertFalse(coordinator!!.request(SyncTrigger.LOCAL_CHANGE))
        coordinator!!.shutdown()
        cancelled.await()

        assertEquals(1, runs.get())
        assertFalse(coordinator!!.request(SyncTrigger.FOREGROUND))
    }

    private fun coordinator(
        enabled: () -> Boolean = { true },
        onRemoteVisibleChanged: () -> Unit = {},
        sync: suspend () -> SyncResult
    ): SyncCoordinator = SyncCoordinator(
        scope = scope,
        isEnabled = enabled,
        runSync = sync,
        onRemoteVisibleChanged = onRemoteVisibleChanged
    )
}

private const val TEST_HTTP_UNAVAILABLE = 503

private class CommitBeforeReturnStore(
    initial: List<com.molotov.clender.core.model.Event>,
    private val committed: CompletableDeferred<Unit>,
    private val allowResultDelivery: CompletableDeferred<Unit>
) : SyncRecordStore {
    var records: List<com.molotov.clender.core.model.Event> = initial
        private set

    override suspend fun snapshot(): List<com.molotov.clender.core.model.Event> = records

    override suspend fun applyRemote(events: List<com.molotov.clender.core.model.Event>): Boolean {
        records = events
        committed.complete(Unit)
        allowResultDelivery.await()
        return true
    }
}
