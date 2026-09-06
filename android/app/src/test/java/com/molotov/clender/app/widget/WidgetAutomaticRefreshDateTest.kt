package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshPolicy
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAutomaticRefreshDateTest {
    @Test(timeout = 5_000)
    fun restoreSchedulesOneTimeMidnightAndMutationDoesNotPostponeIt() = runBlocking {
        withRefreshHarness { h ->
            h.restore()
            val schedule = h.schedules.single()
            assertEquals(WidgetRefreshPolicy.DATE_BOUNDARY_WORK_NAME, schedule.uniqueWorkName)
            assertEquals(Duration.ofHours(12), schedule.delay)
            assertTrue(schedule.oneTime && schedule.replaceExisting && schedule.appWide)
            repeat(5) {
                h.clock.now = h.clock.now.plusSeconds(60)
                h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
                h.drain()
            }
            assertEquals(1, h.schedules.size)
        }
    }

    @Test(timeout = 5_000)
    fun dateBoundaryReplacesEvenWhenItRunsOnSameDate() = runBlocking {
        withRefreshHarness { h ->
            h.restore()
            val receipt = h.runtime.request(WidgetRefreshTrigger.DATE_BOUNDARY)
            h.drain()
            assertEquals(WidgetRefreshCompletion.COMPLETED, receipt.await())
            assertEquals(2, h.schedules.size)
        }
    }

    @Test(timeout = 5_000)
    fun localMidnightUsesActualDstDayLength() = runBlocking {
        listOf("2026-03-08T05:00:00Z" to 23L, "2026-11-01T04:00:00Z" to 25L)
            .forEach { (instant, hours) ->
                withRefreshHarness { h ->
                    h.zone = ZoneId.of("America/New_York")
                    h.clock.now = Instant.parse(instant)
                    h.restore()
                    assertEquals(Duration.ofHours(hours), h.schedules.single().delay)
                }
            }
    }

    @Test(timeout = 5_000)
    fun foregroundRecomputesDynamicZoneAndDate() = runBlocking {
        withRefreshHarness { h ->
            h.restore()
            h.zone = ZoneId.of("Asia/Shanghai")
            h.runtime.request(WidgetRefreshTrigger.FOREGROUND)
            h.drain()
            assertEquals(Duration.ofHours(4), h.schedules.last().delay)
            h.clock.now = Instant.parse("2026-09-06T17:00:00Z")
            h.runtime.request(WidgetRefreshTrigger.FOREGROUND)
            h.drain()
            assertEquals(3, h.schedules.size)
            assertEquals(Duration.ofHours(23), h.schedules.last().delay)
        }
    }

    @Test(timeout = 5_000)
    fun localUpdateFailureStillSchedulesSuccessor() = runBlocking {
        withRefreshHarness { h ->
            h.onUpdate = { error("test local failure") }
            val receipt = h.runtime.request(WidgetRefreshTrigger.DATE_BOUNDARY)
            h.drain()
            assertEquals(WidgetRefreshCompletion.UPDATE_FAILED, receipt.await())
            assertEquals(1, h.schedules.size)
        }
    }

    @Test(timeout = 5_000)
    fun failedEnqueueCanBeAttemptedBySubsequentRestore() = runBlocking {
        withRefreshHarness { h ->
            h.onReplace = { error("test enqueue failure") }
            val failed = h.runtime.restore()
            h.drain()
            assertEquals(WidgetRefreshCompletion.SCHEDULE_FAILED, failed.await())
            h.onReplace = {}
            h.restore()
            assertEquals(1, h.schedules.size)
        }
    }

    @Test(timeout = 5_000)
    fun workerWaiterCancellationDoesNotCancelEnqueueOperation() = runBlocking {
        withRefreshHarness { h ->
            val release = CompletableDeferred<Unit>()
            h.onReplace = { release.await() }
            val receipt = h.runtime.request(WidgetRefreshTrigger.DATE_BOUNDARY)
            h.drain()
            val worker = async(start = CoroutineStart.UNDISPATCHED) { receipt.await() }
            worker.cancelAndJoin()
            release.complete(Unit)
            h.drain()
            assertEquals(WidgetRefreshCompletion.COMPLETED, receipt.await())
            assertEquals(1, h.schedules.size)
        }
    }

    @Test(timeout = 5_000)
    fun deletionWaitsForInflightReplaceThenCancelsSuccessor() = runBlocking {
        withRefreshHarness { h ->
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            h.onReplace = {
                entered.complete(Unit)
                release.await()
            }
            h.runtime.request(WidgetRefreshTrigger.DATE_BOUNDARY)
            h.drain()
            assertTrue(entered.isCompleted)
            h.ids = emptySet()
            val receipt = h.runtime.instancesChanged(setOf(1))
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { receipt.await() }
            h.drain()
            assertFalse(waiter.isCompleted)
            release.complete(Unit)
            h.drain()
            waiter.await()
            assertEquals(listOf("replace", "cancel"), h.workOperations)
        }
    }

    @Test(timeout = 5_000)
    fun normalClosePreservesAlreadyCommittedDateWork() = runBlocking {
        withRefreshHarness { h ->
            h.restore()
            h.runtime.close()
            h.drain()
            h.runtime.awaitClosed()
            assertEquals(listOf("replace"), h.workOperations)
        }
    }

    @Test(timeout = 5_000)
    fun unrepresentableNextBoundaryFailsWithoutEnqueue() = runBlocking {
        withRefreshHarness { h ->
            h.clock.now = Instant.parse("+999999998-01-01T00:00:00Z")
            val receipt = h.runtime.request(WidgetRefreshTrigger.DATE_BOUNDARY)
            h.drain()
            assertEquals(WidgetRefreshCompletion.TIME_OVERFLOW, receipt.await())
            assertTrue(h.schedules.isEmpty())
        }
    }
}
