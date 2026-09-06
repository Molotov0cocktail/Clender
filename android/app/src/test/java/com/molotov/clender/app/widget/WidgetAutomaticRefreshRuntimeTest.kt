package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAutomaticRefreshRuntimeTest {
    @Test(timeout = 5_000)
    fun everyTriggerUsesOwnedIdsOrItsSingleTarget() = runBlocking {
        WidgetRefreshTrigger.entries.forEach { trigger ->
            withRefreshHarness { h ->
                h.ids = setOf(9, 0, -1, 2)
                val targeted = trigger == WidgetRefreshTrigger.CONFIGURATION_CHANGE ||
                    trigger == WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH
                val receipt = h.runtime.request(trigger, if (targeted) 9 else null)
                h.drain()
                assertEquals(WidgetRefreshCompletion.COMPLETED, receipt.await())
                assertEquals(if (targeted) listOf(9) else listOf(2, 9), h.updates)
            }
        }
    }

    @Test(timeout = 5_000)
    fun malformedTargetsAreRejectedBeforeAnyPortAccess() = runBlocking {
        withRefreshHarness { h ->
            val receipts = listOf(
                h.runtime.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH),
                h.runtime.request(WidgetRefreshTrigger.CONFIGURATION_CHANGE, 0),
                h.runtime.request(WidgetRefreshTrigger.CONFIGURATION_CHANGE, -1),
                h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION, 1)
            )
            h.drain()
            receipts.forEach {
                assertFalse(it.accepted)
                assertEquals(WidgetRefreshCompletion.INVALID_TARGET, it.await())
            }
            assertEquals(0, h.ownershipReads)
            assertTrue(h.updates.isEmpty())
        }
    }

    @Test(timeout = 5_000)
    fun unownedPositiveTargetDoesNotReachLocalUpdate() = runBlocking {
        withRefreshHarness { h ->
            val receipt = h.runtime.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH, 99)
            h.drain()
            assertEquals(WidgetRefreshCompletion.INVALID_TARGET, receipt.await())
            assertTrue(h.updates.isEmpty())
        }
    }

    @Test(timeout = 5_000)
    fun emptyRestoreDoesNotReachLocalPortsOrEnqueue() = runBlocking {
        withRefreshHarness { h ->
            h.ids = emptySet()
            val receipt = h.runtime.restore()
            h.drain()
            assertEquals(WidgetRefreshCompletion.NO_WIDGETS, receipt.await())
            assertTrue(h.updates.isEmpty())
            assertTrue(h.deletions.isEmpty())
            assertTrue(h.schedules.isEmpty())
            assertEquals(0, h.mutations.subscriptionCount.value)
        }
    }

    @Test(timeout = 5_000)
    fun ownershipFailureIsNotTreatedAsLastInstanceDeletion() = runBlocking {
        withRefreshHarness { h ->
            h.readIds = { error("test ownership failure") }
            val receipt = h.runtime.restore()
            h.drain()
            assertEquals(WidgetRefreshCompletion.OWNERSHIP_FAILED, receipt.await())
            assertEquals(0, h.cancelCount)
            assertTrue(h.updates.isEmpty())
        }
    }

    @Test(timeout = 5_000)
    fun oneFailedInstanceDoesNotPreventOtherInstancesUpdating() = runBlocking {
        withRefreshHarness { h ->
            h.ids = setOf(1, 2)
            h.onUpdate = { if (it == 1) error("test update failure") }
            val receipt = h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
            h.drain()
            assertEquals(listOf(1, 2), h.updates)
            assertEquals(WidgetRefreshCompletion.UPDATE_FAILED, receipt.await())
        }
    }
}

/** Tests control dispatch explicitly; no extra dependency, sleeps or timing retries. */
internal class RefreshQueueDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun drain() {
        var steps = 0
        while (queue.isNotEmpty()) {
            check(++steps <= 10_000) { "Refresh task failed to become idle" }
            queue.removeFirst().run()
        }
    }
}

internal class RefreshMutableClock(var now: Instant = Instant.parse("2026-09-06T12:00:00Z")) :
    Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
}

internal class RefreshHarness {
    val dispatcher = RefreshQueueDispatcher()
    val ownerJob = SupervisorJob()
    val scope = CoroutineScope(ownerJob + dispatcher)
    val mutations = MutableStateFlow(0L)
    val clock = RefreshMutableClock()
    var zone: ZoneId = ZoneOffset.UTC
    var ids: Set<Int> = setOf(1)
    var ownershipReads = 0
    var cancelCount = 0
    val updates = mutableListOf<Int>()
    val invalidations = mutableListOf<Set<Int>>()
    val deletions = mutableListOf<Set<Int>>()
    val schedules = mutableListOf<WidgetDateBoundarySchedule>()
    val workOperations = mutableListOf<String>()
    var readIds: suspend () -> Set<Int> = { ids }
    var onUpdate: suspend (Int) -> Unit = {}
    var onReplace: suspend (WidgetDateBoundarySchedule) -> Unit = {}
    var onPrepare: ((Int) -> (suspend () -> Unit))? = null

    val runtime = WidgetAutomaticRefreshRuntime(
        scope = scope,
        dependencies = WidgetAutomaticRefreshDependencies(
            ownedIds = WidgetOwnedIdsPort {
                ownershipReads++
                readIds()
            },
            localUpdates = object : WidgetLocalUpdatePort {
                override fun invalidate(ids: Set<Int>) {
                    invalidations.add(ids.toSet())
                }

                override suspend fun update(id: Int) {
                    updates.add(id)
                    onUpdate(id)
                }

                override fun prepareUpdate(id: Int): suspend () -> Unit =
                    onPrepare?.invoke(id) ?: super<WidgetLocalUpdatePort>.prepareUpdate(id)

                override suspend fun delete(ids: Set<Int>) {
                    deletions.add(ids.toSet())
                }
            },
            dateWork = object : WidgetDateWorkPort {
                override suspend fun replace(schedule: WidgetDateBoundarySchedule) {
                    onReplace(schedule)
                    schedules.add(schedule)
                    workOperations.add("replace")
                }

                override suspend fun cancel() {
                    cancelCount++
                    workOperations.add("cancel")
                }
            },
            mutationVersion = mutations,
            clock = clock,
            zoneId = { zone }
        )
    )

    fun drain() = dispatcher.drain()

    suspend fun restore() {
        val receipt = runtime.restore()
        drain()
        assertEquals(WidgetRefreshCompletion.COMPLETED, receipt.await())
    }
}

internal suspend fun withRefreshHarness(block: suspend (RefreshHarness) -> Unit) {
    val h = RefreshHarness()
    try {
        block(h)
    } finally {
        h.runtime.close()
        h.drain()
        h.runtime.awaitClosed()
        h.ownerJob.cancel()
        h.drain()
    }
}
