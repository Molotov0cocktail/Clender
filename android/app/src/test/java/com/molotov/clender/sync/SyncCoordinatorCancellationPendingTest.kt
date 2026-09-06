package com.molotov.clender.sync

import com.molotov.clender.domain.sync.SyncCancellationException
import com.molotov.clender.domain.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorCancellationPendingTest {
    @Test
    fun cancelledRunDiscardsOldPendingBeforeIndependentRequest() = runBlocking {
        val owner = SupervisorJob()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        var notifications = 0
        var failures = 0
        val coordinator = SyncCoordinator(
            scope = CoroutineScope(coroutineContext + owner),
            isEnabled = { true },
            runSync = {
                runs += 1
                if (runs == 1) {
                    entered.complete(Unit)
                    release.await()
                    throw SyncCancellationException(remoteVisibleChanged = true)
                }
                SyncResult(localChanged = false, uploaded = true, eventCount = 1)
            },
            onRemoteVisibleChanged = { notifications += 1 },
            onFailure = { failures += 1 }
        )
        try {
            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            entered.await()
            assertFalse(coordinator.request(SyncTrigger.LOCAL_CHANGE))
            release.complete(Unit)
            coordinator.awaitIdle()
            assertEquals(1, runs)
            assertEquals(1, notifications)
            assertTrue(owner.isActive)

            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            coordinator.awaitIdle()

            assertEquals(2, runs)
            assertEquals(1, notifications)
            assertEquals(0, failures)
        } finally {
            release.complete(Unit)
            coordinator.shutdown()
            owner.cancelAndJoin()
        }
    }

    @Test
    fun oldCancelledFinallyPreservesNewWorkerAndItsPendingRequest() = runBlocking {
        val owner = SupervisorJob()
        val oldJob = CompletableDeferred<Job>()
        val oldRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        var runs = 0
        var notifications = 0
        var failures = 0
        val coordinator = SyncCoordinator(
            scope = CoroutineScope(coroutineContext + owner),
            isEnabled = { true },
            runSync = {
                runs += 1
                when (runs) {
                    1 -> cancelledOldRun(oldJob, oldRelease)

                    2 -> {
                        secondEntered.complete(Unit)
                        secondRelease.await()
                    }
                }
                SyncResult(localChanged = false, uploaded = true, eventCount = 1)
            },
            onRemoteVisibleChanged = { notifications += 1 },
            onFailure = { failures += 1 }
        )
        try {
            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            val cancelledJob = oldJob.await()
            cancelledJob.cancel()
            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            assertFalse(coordinator.request(SyncTrigger.LOCAL_CHANGE))
            oldRelease.complete(Unit)
            secondEntered.await()
            cancelledJob.join()
            secondRelease.complete(Unit)
            coordinator.awaitIdle()

            assertEquals(3, runs)
            assertEquals(1, notifications)
            assertEquals(0, failures)
            assertTrue(owner.isActive)
        } finally {
            oldRelease.complete(Unit)
            secondRelease.complete(Unit)
            coordinator.shutdown()
            owner.cancelAndJoin()
        }
    }
}

private suspend fun cancelledOldRun(
    job: CompletableDeferred<Job>,
    release: CompletableDeferred<Unit>
): Nothing {
    val currentJob = checkNotNull(currentCoroutineContext()[Job])
    try {
        withContext(NonCancellable) {
            job.complete(currentJob)
            release.await()
        }
    } catch (_: CancellationException) {
        throw SyncCancellationException(remoteVisibleChanged = true)
    }
    throw SyncCancellationException(remoteVisibleChanged = true)
}
