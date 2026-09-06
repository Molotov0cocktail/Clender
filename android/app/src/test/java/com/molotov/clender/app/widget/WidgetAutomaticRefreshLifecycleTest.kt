package com.molotov.clender.app.widget

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAutomaticRefreshLifecycleTest {
    @Test(timeout = 5_000)
    fun restoreStartsOneCollectorAndIgnoresInitialVersion() = runBlocking {
        withRefreshHarness { h ->
            h.mutations.value = 8L
            h.restore()
            assertEquals(listOf(1), h.updates)
            h.restore()
            assertEquals(1, h.mutations.subscriptionCount.value)
            h.updates.clear()
            h.mutations.value = 9L
            h.drain()
            assertEquals(listOf(1), h.updates)
        }
    }

    @Test(timeout = 5_000)
    fun mutationDuringFirstRestoreIsNotLost() = runBlocking {
        withRefreshHarness { h ->
            h.onUpdate = {
                if (h.updates.size == 1) h.mutations.value = 1L
            }
            h.restore()
            assertEquals(listOf(1, 1), h.updates)
        }
    }

    @Test(timeout = 5_000)
    fun lastDeletionStopsMutationCollectionAndLaterInstallRestartsIt() = runBlocking {
        withRefreshHarness { h ->
            h.restore()
            h.ids = emptySet()
            h.runtime.instancesChanged(setOf(1))
            h.drain()
            assertEquals(0, h.mutations.subscriptionCount.value)
            h.updates.clear()
            h.mutations.value = 1L
            h.drain()
            assertTrue(h.updates.isEmpty())
            h.ids = setOf(2)
            h.runtime.instancesChanged()
            h.drain()
            assertEquals(1, h.mutations.subscriptionCount.value)
            assertEquals(listOf(2), h.updates)
        }
    }

    @Test(timeout = 5_000)
    fun closeCancelsSuspendedPortsWithoutMainDispatcherOrOwnerCancellation() = runBlocking {
        listOf("ownership", "update", "schedule").forEach { stage ->
            withRefreshHarness { h ->
                var entered = false
                var released = false
                val suspendPort: suspend () -> Unit = {
                    entered = true
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                }
                when (stage) {
                    "ownership" -> h.readIds = {
                        suspendPort()
                        h.ids
                    }

                    "update" -> h.onUpdate = { suspendPort() }

                    else -> h.onReplace = { suspendPort() }
                }
                val receipt = h.runtime.restore()
                h.drain()
                assertTrue(stage, entered)
                h.runtime.close()
                h.drain()
                h.runtime.awaitClosed()
                assertTrue(stage, released)
                assertTrue(h.ownerJob.isActive)
                assertEquals(WidgetRefreshCompletion.CLOSED, receipt.await())
                assertFalse(h.runtime.restore().accepted)
            }
        }
    }
}
