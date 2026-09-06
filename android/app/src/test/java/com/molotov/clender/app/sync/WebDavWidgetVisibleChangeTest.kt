package com.molotov.clender.app.sync

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.molotov.clender.domain.sync.SyncCancellationException
import com.molotov.clender.domain.sync.SyncFailureException
import com.molotov.clender.domain.sync.SyncFailureKind
import com.molotov.clender.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Visible remote commits refresh locally even if the subsequent upload fails or is cancelled. */
class WebDavWidgetVisibleChangeTest {
    @Test
    fun successfulVisibleApplyNotifiesAfterReleasingSyncGateWithoutLocalMutation() = runBlocking {
        withHarness({ WebDavRunResult.Completed(true, true, 1) }) { h ->
            assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
            assertEquals(1, h.refreshes)
            assertEquals(1, h.runs)
            assertEquals(0L, h.mutations.value)
            assertEquals(SyncState.Success(true, true, 1), h.runtime.state.value)
        }
    }

    @Test
    fun unchangedAndUnconfiguredRunsDoNotNotify() = runBlocking {
        listOf(WebDavRunResult.Completed(false, false, 0), WebDavRunResult.Unconfigured)
            .forEach { result ->
                withHarness({ result }) { h ->
                    h.runtime.requestSync(SyncTrigger.MANUAL)
                    assertEquals(0, h.refreshes)
                    assertEquals(1, h.runs)
                    assertEquals(0L, h.mutations.value)
                }
            }
    }

    @Test
    fun failedUploadNotifiesOnlyIfRemoteChangesWereCommitted() = runBlocking {
        listOf(false, true).forEach { changed ->
            withHarness(
                { throw SyncFailureException(changed, SyncFailureKind.TRANSPORT, 503) }
            ) { h ->
                h.runtime.requestSync(SyncTrigger.MANUAL)
                assertEquals(if (changed) 1 else 0, h.refreshes)
                assertEquals(SyncState.Failed(SyncFailureCode.TRANSPORT), h.runtime.state.value)
                assertEquals(0L, h.mutations.value)
                assertEquals(1, h.runs)
            }
        }
    }

    @Test
    fun cancelledUploadNotifiesOnlyIfRemoteChangesWereCommitted() = runBlocking {
        listOf(false, true).forEach { changed ->
            withHarness({ throw SyncCancellationException(changed) }) { h ->
                h.runtime.requestSync(SyncTrigger.MANUAL)
                assertEquals(if (changed) 1 else 0, h.refreshes)
                assertEquals(SyncState.Failed(SyncFailureCode.CANCELLED), h.runtime.state.value)
                assertEquals(0L, h.mutations.value)
                assertEquals(1, h.runs)
            }
        }
    }

    @Test
    fun ordinaryFailureDoesNotInventRemoteChanges() = runBlocking {
        withHarness({ error("synthetic failure") }) { h ->
            h.runtime.requestSync(SyncTrigger.MANUAL)
            assertEquals(0, h.refreshes)
            assertEquals(SyncState.Failed(SyncFailureCode.INTERNAL), h.runtime.state.value)
        }
    }

    @Test
    fun disabledAvailabilityDoesNotRunOrNotify() = runBlocking {
        withHarness({ WebDavRunResult.Completed(true, true, 1) }, enabled = false) { h ->
            assertEquals(SyncRequestDecision.DISABLED, h.runtime.requestSync(SyncTrigger.MANUAL))
            assertEquals(0, h.refreshes)
            assertEquals(0, h.runs)
        }
    }

    @Test
    fun closePreservesOneAlreadyAppliedRemoteNotificationWithoutStartingAnotherRun() = runBlocking {
        withHarness({
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                throw SyncCancellationException(true)
            }
        }) { h ->
            h.runtime.requestSync(SyncTrigger.MANUAL)
            assertEquals(1, h.runs)
            h.runtime.close()
            assertEquals(1, h.refreshes)
            assertEquals(SyncRequestDecision.SHUTDOWN, h.runtime.requestSync(SyncTrigger.MANUAL))
            assertEquals(1, h.runs)
            assertEquals(0L, h.mutations.value)
        }
    }
}

private class VisibleChangeHarness(run: suspend () -> WebDavRunResult, enabled: Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val gate = WebDavOperationGate()
    val mutations = MutableStateFlow(0L)
    var runs = 0
    var refreshes = 0
    val runtime = WebDavSyncRuntime(
        scope = scope,
        lifecycle = owner.lifecycle,
        gate = gate,
        signals = WebDavSyncSignals(
            availability = MutableStateFlow(WebDavAvailability(enabled, enabled)),
            mutationVersion = mutations,
            onRemoteVisibleChanged = {
                assertNotNull(gate.tryAcquire()?.also { it.close() })
                refreshes++
            }
        ),
        runSync = {
            runs++
            run()
        }
    )

    fun close() {
        runtime.close()
        gate.close()
        scope.cancel()
    }
}

private suspend fun withHarness(
    run: suspend () -> WebDavRunResult,
    enabled: Boolean = true,
    block: suspend (VisibleChangeHarness) -> Unit
) {
    val harness = VisibleChangeHarness(run, enabled)
    try {
        block(harness)
    } finally {
        harness.close()
    }
}
