package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshReceiptTest {
    @Test(timeout = 5_000)
    fun cancellingOneWaiterPreservesWorkAndOtherWaiters() = runBlocking {
        withRefreshHarness { h ->
            val release = CompletableDeferred<Unit>()
            h.onUpdate = { release.await() }
            val receipt = h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
            h.drain()
            assertEquals(listOf(1), h.updates)
            val first = async(start = CoroutineStart.UNDISPATCHED) { receipt.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { receipt.await() }
            first.cancelAndJoin()
            assertFalse(second.isCompleted)
            release.complete(Unit)
            h.drain()
            assertEquals(WidgetRefreshCompletion.COMPLETED, second.await())
            assertEquals(WidgetRefreshCompletion.COMPLETED, receipt.await())
            assertTrue(h.ownerJob.isActive)
        }
    }

    @Test(timeout = 5_000)
    fun requestsArrivingDuringUpdateWaitForAnotherPass() = runBlocking {
        withRefreshHarness { h ->
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            h.onUpdate = {
                if (h.updates.size == 1) firstRelease.await() else secondRelease.await()
            }
            val first = h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
            h.drain()
            val second = h.runtime.request(WidgetRefreshTrigger.REMOTE_VISIBLE_CHANGE)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { second.await() }
            firstRelease.complete(Unit)
            h.drain()
            assertEquals(WidgetRefreshCompletion.COMPLETED, first.await())
            assertEquals(listOf(1, 1), h.updates)
            assertFalse(waiter.isCompleted)
            secondRelease.complete(Unit)
            h.drain()
            assertEquals(WidgetRefreshCompletion.COMPLETED, waiter.await())
        }
    }

    @Test(timeout = 5_000)
    fun pendingRequestsCoalesceWithoutLosingReceipts() = runBlocking {
        withRefreshHarness { h ->
            val receipts = List(20) { h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION) }
            h.drain()
            assertEquals(listOf(1), h.updates)
            receipts.forEach {
                assertTrue(it.accepted)
                assertEquals(WidgetRefreshCompletion.COMPLETED, it.await())
            }
        }
    }

    @Test(timeout = 5_000)
    fun closeCompletesPendingReceiptsAndRejectsNewOnesWithoutCancellingOwner() = runBlocking {
        withRefreshHarness { h ->
            val pending = h.runtime.restore()
            h.runtime.close()
            val rejected = h.runtime.restore()
            h.drain()
            h.runtime.awaitClosed()
            assertTrue(pending.accepted)
            assertFalse(rejected.accepted)
            assertEquals(WidgetRefreshCompletion.CLOSED, pending.await())
            assertEquals(WidgetRefreshCompletion.CLOSED, rejected.await())
            assertTrue(h.ownerJob.isActive)
            assertTrue(h.updates.isEmpty())
        }
    }

    @Test(timeout = 5_000)
    fun ownerCancellationCompletesOutstandingReceipt() = runBlocking {
        withRefreshHarness { h ->
            val receipt = h.runtime.restore()
            h.ownerJob.cancel()
            h.drain()
            h.runtime.awaitClosed()
            assertEquals(WidgetRefreshCompletion.CLOSED, receipt.await())
            assertFalse(h.runtime.restore().accepted)
        }
    }
}
