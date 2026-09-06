package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime boundaries only; actual stale render/upsert requires coordinator integration tests. */
class WidgetAutomaticRefreshDeletionTest {
    @Test(timeout = 5_000)
    fun defaultPrepareOnlyInvokesUpdateWhenClosureRuns() = runBlocking {
        var updates = 0
        val local = object : WidgetLocalUpdatePort {
            override fun invalidate(ids: Set<Int>) = Unit

            override suspend fun update(id: Int) {
                assertEquals(1, id)
                updates++
            }

            override suspend fun delete(ids: Set<Int>) = Unit
        }
        val prepared = local.prepareUpdate(1)
        assertEquals(0, updates)
        prepared()
        assertEquals(1, updates)
    }

    @Test(timeout = 5_000)
    fun deletedOrClosedInboxDoesNotCallPrepareSupplier() = runBlocking {
        val inbox = WidgetRefreshInbox()
        try {
            val ticket = checkNotNull(
                inbox.register(WidgetRefreshRequest(WidgetRefreshTrigger.LOCAL_MUTATION))
            )
            inbox.register(
                WidgetRefreshRequest(WidgetRefreshTrigger.FOREGROUND, deletedIds = setOf(1))
            )
            assertNull(inbox.prepare(ticket, 1) { error("Deleted target was admitted") })
            inbox.close()
            assertNull(inbox.prepare(ticket, 2) { error("Closed runtime was admitted") })
        } finally {
            inbox.close()
        }
    }

    @Test(timeout = 5_000)
    fun preparationCapturesGenerationBeforeLaterDeletionInvalidatesIt() = runBlocking {
        val inbox = WidgetRefreshInbox()
        var generation = 0
        var writes = 0
        try {
            val ticket = checkNotNull(
                inbox.register(WidgetRefreshRequest(WidgetRefreshTrigger.LOCAL_MUTATION))
            )
            val prepared = inbox.prepare(ticket, 1) {
                val captured = generation
                val operation: suspend () -> Unit = {
                    if (generation == captured) writes++
                }
                operation
            }
            assertNotNull(prepared)
            assertEquals(0, writes)
            inbox.register(
                WidgetRefreshRequest(WidgetRefreshTrigger.FOREGROUND, deletedIds = setOf(1))
            )
            generation++
            checkNotNull(prepared).invoke()
            assertEquals(0, writes)
        } finally {
            inbox.close()
        }
    }

    @Test(timeout = 5_000)
    fun coalescedRuntimeRequestsUseOnePreparedClosure() = runBlocking {
        withRefreshHarness { h ->
            val preparedIds = mutableListOf<Int>()
            var invoked = 0
            h.onPrepare = { id ->
                preparedIds.add(id)
                val operation: suspend () -> Unit = { invoked++ }
                operation
            }
            val receipts = List(3) { h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION) }
            h.drain()
            assertEquals(listOf(1), preparedIds)
            assertEquals(1, invoked)
            assertTrue(h.updates.isEmpty())
            receipts.forEach {
                assertEquals(WidgetRefreshCompletion.COMPLETED, it.await())
            }
        }
    }

    @Test(timeout = 5_000)
    fun preparationFailureIsFiniteAndDoesNotBlockOtherInstances() = runBlocking {
        withRefreshHarness { h ->
            h.ids = setOf(1, 2)
            val invoked = mutableListOf<Int>()
            h.onPrepare = { id ->
                check(id != 1) { "test preparation failure" }
                val operation: suspend () -> Unit = { invoked.add(id) }
                operation
            }
            val receipt = h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
            h.drain()
            assertEquals(listOf(2), invoked)
            assertEquals(WidgetRefreshCompletion.UPDATE_FAILED, receipt.await())
        }
    }

    @Test(timeout = 5_000)
    fun suspendedPreparedOperationDoesNotDelayDeletionAdmission() = runBlocking {
        withRefreshHarness { h ->
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            h.onPrepare = {
                val operation: suspend () -> Unit = {
                    entered.complete(Unit)
                    release.await()
                }
                operation
            }
            h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
            h.drain()
            assertTrue(entered.isCompleted)
            h.ids = emptySet()
            val deletion = h.runtime.instancesChanged(setOf(1))
            assertEquals(listOf(setOf(1)), h.invalidations)
            h.drain()
            assertEquals(listOf(setOf(1)), h.deletions)
            release.complete(Unit)
            h.drain()
            deletion.await()
        }
    }

    @Test(timeout = 5_000)
    fun deletionInvalidatesImmediatelyWhileUpdateIsSuspended() = runBlocking {
        withRefreshHarness { h ->
            val release = CompletableDeferred<Unit>()
            h.onUpdate = { release.await() }
            h.runtime.request(WidgetRefreshTrigger.LOCAL_MUTATION)
            h.drain()
            h.ids = emptySet()
            val deletion = h.runtime.instancesChanged(setOf(1))
            assertEquals(listOf(setOf(1)), h.invalidations)
            h.drain()
            assertEquals(listOf(setOf(1)), h.deletions)
            release.complete(Unit)
            h.drain()
            deletion.await()
            assertEquals("cancel", h.workOperations.last())
        }
    }

    @Test(timeout = 5_000)
    fun queuedTargetIsSupersededByDeletion() = runBlocking {
        withRefreshHarness { h ->
            val pending = h.runtime.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH, 1)
            h.ids = emptySet()
            h.runtime.instancesChanged(setOf(1))
            h.drain()
            assertEquals(WidgetRefreshCompletion.SUPERSEDED, pending.await())
            assertTrue(h.updates.isEmpty())
        }
    }

    @Test(timeout = 5_000)
    fun staleOwnershipReadCannotResurrectDeletedTarget() = runBlocking {
        withRefreshHarness { h ->
            val release = CompletableDeferred<Unit>()
            h.readIds = {
                if (h.ownershipReads == 1) {
                    release.await()
                    setOf(1)
                } else {
                    h.ids
                }
            }
            val pending = h.runtime.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH, 1)
            h.drain()
            assertEquals(1, h.ownershipReads)
            h.ids = emptySet()
            h.runtime.instancesChanged(setOf(1))
            h.drain()
            release.complete(Unit)
            h.drain()
            assertEquals(WidgetRefreshCompletion.SUPERSEDED, pending.await())
            assertTrue(h.updates.isEmpty())
        }
    }

    @Test(timeout = 5_000)
    fun deletingOneInstancePreservesAnotherAndAllowsLaterLegitimateReuse() = runBlocking {
        withRefreshHarness { h ->
            h.ids = setOf(1, 2)
            h.restore()
            h.updates.clear()
            h.ids = setOf(2)
            h.runtime.instancesChanged(setOf(1))
            h.drain()
            assertTrue(h.updates.all { it == 2 })
            h.ids = setOf(1, 2)
            val installed = h.runtime.instancesChanged()
            h.drain()
            assertEquals(WidgetRefreshCompletion.COMPLETED, installed.await())
            assertTrue(h.updates.contains(1))
        }
    }
}
