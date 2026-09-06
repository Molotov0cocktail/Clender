package com.molotov.clender.app.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.molotov.clender.data.network.webdav.WebDavTransportException
import com.molotov.clender.data.settings.WebDavSectionSnapshot
import com.molotov.clender.sync.SyncTrigger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T46-C2b contract tests for WebDavSyncRuntime: trigger decisions, state
 * transitions, gate exclusivity, snapshot staging, mutation version handling,
 * ProcessLifecycle foreground triggers and close semantics.
 *
 * Tests only use the public constructor surface (scope/lifecycle/gate/
 * availability/mutationVersion/runSync/classifier) and deliberately never
 * touch SyncCoordinator or its not-yet-added requestDiagnosed extension.
 */
class WebDavSyncRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimes = mutableListOf<WebDavSyncRuntime>()
    private val gates = mutableListOf<WebDavOperationGate>()

    @After
    fun tearDown() {
        runtimes.forEach { it.close() }
        gates.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun disabledAvailabilityRejectsEveryTriggerWithZeroRuns() = runBlocking {
        val h = harness(
            availability = MutableStateFlow(
                WebDavAvailability(enabled = false, passwordConfigured = false)
            )
        )
        SyncTrigger.entries.filter { it != SyncTrigger.FOREGROUND }.forEach { trigger ->
            assertEquals(trigger.name, SyncRequestDecision.DISABLED, h.runtime.requestSync(trigger))
        }
        assertEquals(
            SyncRequestDecision.DISABLED,
            h.runtime.requestSync(
                SyncTrigger.MANUAL,
                snapshot(enabled = false, passwordConfigured = true)
            )
        )
        assertEquals(SyncState.Disabled, h.runtime.state.value)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        delay(120)
        assertEquals(0, h.runCount)
        assertEquals(SyncState.Disabled, h.runtime.state.value)
    }

    @Test
    fun unconfiguredAvailabilityRejectsEveryTriggerWithZeroRuns() = runBlocking {
        val h = harness(
            availability = MutableStateFlow(
                WebDavAvailability(enabled = true, passwordConfigured = false)
            )
        )
        SyncTrigger.entries.filter { it != SyncTrigger.FOREGROUND }.forEach { trigger ->
            assertEquals(
                trigger.name,
                SyncRequestDecision.UNCONFIGURED,
                h.runtime.requestSync(trigger)
            )
        }
        assertEquals(
            SyncRequestDecision.UNCONFIGURED,
            h.runtime.requestSync(
                SyncTrigger.MANUAL,
                snapshot(enabled = true, passwordConfigured = false)
            )
        )
        assertEquals(SyncState.Unconfigured, h.runtime.state.value)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        delay(120)
        assertEquals(0, h.runCount)
        assertEquals(SyncState.Unconfigured, h.runtime.state.value)
    }

    @Test
    fun idleManualRequestStartsExactlyOneRunAndCompletesToSuccess() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        latch.awaitCall(1)
        assertEquals(listOf<WebDavSectionSnapshot?>(null), h.bindings)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        assertEquals(1, h.runCount)
        assertNotNull(h.gate.tryAcquire()?.also { it.close() })
    }

    @Test
    fun manualSnapshotIsConsumedByTheNextRunExactlyOnce() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        val snap = snapshot(enabled = true, passwordConfigured = true)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL, snap))
        latch.awaitCall(1)
        assertEquals(listOf(snap), h.bindings)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        latch.awaitCall(2)
        assertEquals(listOf(snap, null), h.bindings)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        assertEquals(2, h.runCount)
    }

    @Test
    fun snapshotStagedWhileBusyIsConsumedByTheSingleFollowUpRun() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        latch.awaitCall(1)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        val snap = snapshot(enabled = true, passwordConfigured = true)
        assertEquals(SyncRequestDecision.COALESCED, h.runtime.requestSync(SyncTrigger.MANUAL, snap))
        assertEquals(SyncState.Running(pending = true), h.runtime.state.value)
        latch.releaseCall(1)
        latch.awaitCall(2)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        assertEquals(listOf(null, snap), h.bindings)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        assertEquals(2, h.runCount)
        assertNoFurtherRuns(latch, 2)
    }

    @Test
    fun requestsWhileBusyCoalesceIntoExactlyOnePendingFollowUp() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.LOCAL_CHANGE))
        latch.awaitCall(1)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        val staged = snapshot(enabled = true, passwordConfigured = true)
        assertEquals(SyncRequestDecision.COALESCED, h.runtime.requestSync(SyncTrigger.LOCAL_CHANGE))
        assertEquals(SyncRequestDecision.COALESCED, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(
            SyncRequestDecision.COALESCED,
            h.runtime.requestSync(SyncTrigger.MANUAL, staged)
        )
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        waitFor { h.runtime.state.value == SyncState.Running(pending = true) }
        assertEquals(SyncRequestDecision.COALESCED, h.runtime.requestSync(SyncTrigger.FOREGROUND))
        latch.releaseCall(1)
        latch.awaitCall(2)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        assertEquals(listOf(null, staged), h.bindings)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        assertEquals(2, h.runCount)
        assertNoFurtherRuns(latch, 2)
    }

    @Test
    fun localChangeFirstEmissionIsIgnoredThenEachIncrementTriggersExactlyOneRun() = runBlocking {
        val mutations = MutableStateFlow(0L)
        val firstDelivery = CompletableDeferred<Unit>()
        val latched = mutations.onEach {
            if (!firstDelivery.isCompleted) firstDelivery.complete(Unit)
        }
        val latch = LatchingRunSync()
        val h = harness(mutationVersion = latched, runSync = latch::invoke)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        latch.awaitCall(1)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        withTimeout(5_000) { firstDelivery.await() }
        assertNoFurtherRuns(latch, 1)
        mutations.value = 1L
        latch.awaitCall(2)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        mutations.value = 2L
        latch.awaitCall(3)
        latch.releaseCall(3)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 3
            )
        }
        assertEquals(3, h.runCount)
        assertNoFurtherRuns(latch, 3)
    }

    @Test
    fun localChangeWhileBusyCoalescesExactlyOnePendingFollowUp() = runBlocking {
        val mutations = MutableStateFlow(0L)
        val firstDelivery = CompletableDeferred<Unit>()
        val latched = mutations.onEach {
            if (!firstDelivery.isCompleted) firstDelivery.complete(Unit)
        }
        val latch = LatchingRunSync()
        val h = harness(mutationVersion = latched, runSync = latch::invoke)
        withTimeout(5_000) { firstDelivery.await() }
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        latch.awaitCall(1)
        mutations.value = 1L
        mutations.value = 2L
        mutations.value = 3L
        waitFor { h.runtime.state.value == SyncState.Running(pending = true) }
        latch.releaseCall(1)
        latch.awaitCall(2)
        assertEquals(2, h.runCount)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        assertEquals(2, h.runCount)
        assertNoFurtherRuns(latch, 2)
    }

    @Test
    fun foregroundColdStartRunsExactlyOnceOnFirstStartAndAgainAfterStop() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        latch.awaitCall(1)
        assertEquals(1, h.runCount)
        assertEquals(listOf<WebDavSectionSnapshot?>(null), h.bindings)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        assertNoFurtherRuns(latch, 1)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertNoFurtherRuns(latch, 1)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        latch.awaitCall(2)
        assertEquals(2, h.runCount)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        assertNoFurtherRuns(latch, 2)
    }

    @Test
    fun onStopDoesNotCancelActiveRunAndTriggersNothingByItself() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        latch.awaitCall(1)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertNoFurtherRuns(latch, 1)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        waitFor { h.runtime.state.value == SyncState.Running(pending = true) }
        latch.releaseCall(1)
        latch.awaitCall(2)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        assertEquals(2, h.runCount)
        latch.releaseCall(2)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
    }

    @Test
    fun manualSnapshotOverridesStaleCachedAvailabilityForExactlyOneRun() = runBlocking {
        val availability = MutableStateFlow(
            WebDavAvailability(enabled = false, passwordConfigured = false)
        )
        val latch = LatchingRunSync()
        val h = harness(availability = availability, runSync = latch::invoke)
        assertEquals(SyncRequestDecision.DISABLED, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(SyncState.Disabled, h.runtime.state.value)
        val staged = snapshot(enabled = true, passwordConfigured = true)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL, staged))
        latch.awaitCall(1)
        assertEquals(listOf(staged), h.bindings)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        assertEquals(SyncRequestDecision.DISABLED, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(SyncState.Disabled, h.runtime.state.value)
        assertEquals(1, h.runCount)
    }

    @Test
    fun snapshotValidityGatesEveryDecisionWithoutRuns() = runBlocking {
        val latch = LatchingRunSync()
        val h = harness(runSync = latch::invoke)
        assertEquals(
            SyncRequestDecision.DISABLED,
            h.runtime.requestSync(
                SyncTrigger.MANUAL,
                snapshot(enabled = false, passwordConfigured = true)
            )
        )
        assertEquals(SyncState.Disabled, h.runtime.state.value)
        assertEquals(
            SyncRequestDecision.UNCONFIGURED,
            h.runtime.requestSync(
                SyncTrigger.MANUAL,
                snapshot(enabled = true, passwordConfigured = false)
            )
        )
        assertEquals(SyncState.Unconfigured, h.runtime.state.value)
        assertEquals(0, h.runCount)
    }

    @Test
    fun availabilityChangesSynchronizeStableStatesButNeverOverwriteRunning() = runBlocking {
        val availability = MutableStateFlow(
            WebDavAvailability(enabled = true, passwordConfigured = true)
        )
        val latch = LatchingRunSync()
        val h = harness(availability = availability, runSync = latch::invoke)
        assertEquals(SyncState.Idle, h.runtime.state.value)
        availability.value = WebDavAvailability(enabled = false, passwordConfigured = false)
        waitFor { h.runtime.state.value == SyncState.Disabled }
        availability.value = WebDavAvailability(enabled = true, passwordConfigured = false)
        waitFor { h.runtime.state.value == SyncState.Unconfigured }
        availability.value = WebDavAvailability(enabled = true, passwordConfigured = true)
        val decision = withTimeout(5_000) {
            var outcome: SyncRequestDecision
            do {
                delay(10)
                outcome = h.runtime.requestSync(SyncTrigger.MANUAL)
            } while (
                outcome != SyncRequestDecision.STARTED &&
                outcome != SyncRequestDecision.DISABLED
            )
            outcome
        }
        assertEquals(SyncRequestDecision.STARTED, decision)
        latch.awaitCall(1)
        availability.value = WebDavAvailability(enabled = false, passwordConfigured = false)
        delay(100)
        assertEquals(SyncState.Running(pending = false), h.runtime.state.value)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        assertEquals(SyncRequestDecision.DISABLED, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(SyncState.Disabled, h.runtime.state.value)
    }

    @Test
    fun disablingWhileActiveDropsPendingWithoutFollowUp() = runBlocking {
        val availability = MutableStateFlow(
            WebDavAvailability(enabled = true, passwordConfigured = true)
        )
        val latch = LatchingRunSync()
        val h = harness(availability = availability, runSync = latch::invoke)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        latch.awaitCall(1)
        availability.value = WebDavAvailability(enabled = false, passwordConfigured = false)
        assertEquals(SyncRequestDecision.COALESCED, h.runtime.requestSync(SyncTrigger.LOCAL_CHANGE))
        assertEquals(SyncState.Running(pending = true), h.runtime.state.value)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        assertNoFurtherRuns(latch, 1)
        assertEquals(1, h.runCount)
        assertEquals(SyncRequestDecision.DISABLED, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(SyncState.Disabled, h.runtime.state.value)
    }

    @Test
    fun runReturningUnconfiguredSettlesToUnconfiguredAndReleasesTheGate() = runBlocking {
        val h = harness(runSync = { WebDavRunResult.Unconfigured })
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        waitFor { h.runtime.state.value == SyncState.Unconfigured }
        assertEquals(1, h.runCount)
        assertNotNull(h.gate.tryAcquire()?.also { it.close() })
    }

    @Test
    fun runFailureMapsThroughClassifierAndLaterRequestRecovers() = runBlocking {
        val failures = AtomicInteger()
        val h = harness(runSync = {
            if (failures.incrementAndGet() == 1) {
                throw WebDavTransportException("rejected by provider", statusCode = 401)
            }
            WebDavRunResult.Completed(uploaded = true, localChanged = false, eventCount = 2)
        })
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        waitFor { h.runtime.state.value == SyncState.Failed(SyncFailureCode.AUTH) }
        assertEquals(1, h.runCount)
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 2
            )
        }
        assertEquals(2, h.runCount)
    }

    @Test
    fun failedStateNeverExposesExceptionBodyOrNetworkSecrets() = runBlocking {
        val h = harness(
            runSync = {
                throw IllegalStateException(
                    "webdav-password-9f3c url=https://example.invalid/dav/ resume=false"
                )
            }
        )
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        waitFor { h.runtime.state.value is SyncState.Failed }
        assertEquals(SyncFailureCode.INTERNAL, (h.runtime.state.value as SyncState.Failed).code)
        val rendered = h.runtime.state.value.toString()
        assertFalse(rendered.contains("webdav-password-9f3c"))
        assertFalse(rendered.contains("https://"))
    }

    @Test
    fun closeIsIdempotentCancelsWorkShutsDownCollectorsAndRejectsRequests() = runBlocking {
        val availability = MutableStateFlow(
            WebDavAvailability(enabled = true, passwordConfigured = true)
        )
        val mutations = MutableStateFlow(0L)
        val runCancelled = CompletableDeferred<Unit>()
        val h = harness(
            availability = availability,
            mutationVersion = mutations,
            runSync = {
                try {
                    awaitCancellation()
                } finally {
                    runCancelled.complete(Unit)
                }
            }
        )
        assertEquals(SyncRequestDecision.STARTED, h.runtime.requestSync(SyncTrigger.MANUAL))
        waitFor { h.runCount == 1 }
        h.runtime.close()
        withTimeout(5_000) { runCancelled.await() }
        assertEquals(1, h.runCount)
        assertEquals(SyncRequestDecision.SHUTDOWN, h.runtime.requestSync(SyncTrigger.MANUAL))
        assertEquals(
            SyncRequestDecision.SHUTDOWN,
            h.runtime.requestSync(
                SyncTrigger.MANUAL,
                snapshot(enabled = true, passwordConfigured = true)
            )
        )
        h.runtime.close()
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        h.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        mutations.value = 1L
        availability.value = WebDavAvailability(enabled = false, passwordConfigured = false)
        delay(100)
        assertEquals(1, h.runCount)
        assertNotNull(h.gate.tryAcquire()?.also { it.close() })
    }

    @Test
    fun requestSyncAwaitsFirstAvailabilityEmissionBeforeDecidingAndRunning() = runBlocking {
        val pending = Channel<WebDavAvailability>(Channel.UNLIMITED)
        val availability = pending.receiveAsFlow()
        val latch = LatchingRunSync()
        val h = harness(availability = availability, runSync = latch::invoke)
        val decision = CompletableDeferred<SyncRequestDecision>()
        val applicant = scope.async(start = CoroutineStart.UNDISPATCHED) {
            decision.complete(h.runtime.requestSync(SyncTrigger.MANUAL))
        }
        assertTrue(runCatching { withTimeout(200) { decision.await() } }.isFailure)
        pending.send(WebDavAvailability(enabled = true, passwordConfigured = true))
        assertEquals(SyncRequestDecision.STARTED, withTimeout(5_000) { decision.await() })
        latch.awaitCall(1)
        assertEquals(listOf<WebDavSectionSnapshot?>(null), h.bindings)
        latch.releaseCall(1)
        waitFor {
            h.runtime.state.value == SyncState.Success(
                uploaded = true,
                localChanged = false,
                eventCount = 1
            )
        }
        applicant.join()
    }

    private fun harness(
        registry: LifecycleRegistry = lifecycleRegistry(),
        availability: Flow<WebDavAvailability> =
            MutableStateFlow(WebDavAvailability(enabled = true, passwordConfigured = true)),
        mutationVersion: Flow<Long> = MutableStateFlow(0L),
        runSync: suspend (WebDavSectionSnapshot?) -> WebDavRunResult = immediateCompletedRun
    ): Harness {
        val gate = WebDavOperationGate()
        gates += gate
        val recorded = RecordingRunSync(runSync)
        val runtime = WebDavSyncRuntime(
            scope = scope,
            lifecycle = registry,
            gate = gate,
            availability = availability,
            mutationVersion = mutationVersion,
            runSync = recorded::invoke
        )
        runtimes += runtime
        return Harness(registry, gate, runtime, recorded)
    }

    private fun lifecycleRegistry(): LifecycleRegistry {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = registry
        }
        registry = LifecycleRegistry.createUnsafe(owner)
        return registry
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    private suspend fun assertNoFurtherRuns(latch: LatchingRunSync, upToRun: Int) {
        runCatching { withTimeout(300) { latch.awaitCall(upToRun + 1) } }
            .onSuccess { fail("Unexpected follow-up WebDAV run started") }
    }

    private class Harness(
        val lifecycle: LifecycleRegistry,
        val gate: WebDavOperationGate,
        val runtime: WebDavSyncRuntime,
        private val recorded: RecordingRunSync
    ) {
        val runCount: Int
            get() = recorded.count
        val bindings: List<WebDavSectionSnapshot?>
            get() = recorded.bindings
    }

    private class RecordingRunSync(
        private val delegate: suspend (WebDavSectionSnapshot?) -> WebDavRunResult
    ) {
        private val recordedBindings = CopyOnWriteArrayList<WebDavSectionSnapshot?>()

        val count: Int
            get() = recordedBindings.size
        val bindings: List<WebDavSectionSnapshot?>
            get() = recordedBindings.toList()

        suspend operator fun invoke(binding: WebDavSectionSnapshot?): WebDavRunResult {
            recordedBindings.add(binding)
            return delegate(binding)
        }
    }

    private class LatchingRunSync {
        private val releaseGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

        suspend operator fun invoke(binding: WebDavSectionSnapshot?): WebDavRunResult {
            val index = releaseGates.size + 1
            val gate = CompletableDeferred<Unit>()
            releaseGates.add(gate)
            gate.await()
            return WebDavRunResult.Completed(
                uploaded = true,
                localChanged = false,
                eventCount = index
            )
        }

        suspend fun awaitCall(call: Int, timeoutMs: Long = 5_000) = withTimeout(timeoutMs) {
            while (releaseGates.size < call) delay(5)
        }

        fun releaseCall(call: Int) {
            releaseGates[call - 1].complete(Unit)
        }
    }

    private fun snapshot(enabled: Boolean, passwordConfigured: Boolean): WebDavSectionSnapshot =
        WebDavSectionSnapshot(
            enabled = enabled,
            url = "https://example.invalid/dav/",
            username = "alice",
            passwordConfigured = passwordConfigured
        )

    private companion object {
        val immediateCompletedRun: suspend (WebDavSectionSnapshot?) -> WebDavRunResult = {
            WebDavRunResult.Completed(uploaded = true, localChanged = false, eventCount = 1)
        }
    }
}
