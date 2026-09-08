package com.molotov.clender.app.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavSyncStateAtomicityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val gate = WebDavOperationGate()
    private var runtime: WebDavSyncRuntime? = null

    @After
    fun close() {
        runtime?.close()
        gate.close()
        scope.cancel()
    }

    @Test
    fun staleIdleCannotOverwriteNewRunningState() {
        preservesConcurrentState(SyncState.Running(pending = false))
    }

    @Test
    fun staleIdleCannotOverwriteNewSuccessState() {
        preservesConcurrentState(SyncState.Success(true, false, 2))
    }

    @Test
    fun staleIdleCannotOverwriteNewFailureState() {
        preservesConcurrentState(SyncState.Failed(SyncFailureCode.INTERNAL))
    }

    @Test
    fun disabledAvailabilityStillStabilizesIdle() {
        val subject = createRuntime(WebDavAvailability(false, false))
        mutableState(subject).value = SyncState.Idle
        stabilize(subject)
        assertEquals(SyncState.Disabled, subject.state.value)
    }

    @Test
    fun missingPasswordStillStabilizesIdle() {
        val subject = createRuntime(WebDavAvailability(true, false))
        mutableState(subject).value = SyncState.Idle
        stabilize(subject)
        assertEquals(SyncState.Unconfigured, subject.state.value)
    }

    private fun preservesConcurrentState(next: SyncState) {
        val subject = createRuntime(WebDavAvailability(true, true))
        val original = mutableState(subject)
        assertEquals(SyncState.Idle, original.value)
        val interleaving = InterleavingStateFlow(original, next)
        subject.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
            .set(subject, interleaving)
        stabilize(subject)
        assertEquals(1, interleaving.injections)
        assertEquals(next, subject.state.value)
    }

    private fun createRuntime(availability: WebDavAvailability): WebDavSyncRuntime {
        val lifecycle = LifecycleRegistry.createUnsafe(
            object : LifecycleOwner {
                override val lifecycle: Lifecycle
                    get() = error("The owner lifecycle must not be queried")
            }
        )
        return WebDavSyncRuntime(
            scope,
            lifecycle,
            gate,
            MutableStateFlow(availability),
            MutableStateFlow(0L)
        ) { error("State-only regression must not start synchronization") }.also { runtime = it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableState(subject: WebDavSyncRuntime): MutableStateFlow<SyncState> =
        subject.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(subject) as MutableStateFlow<SyncState>

    private fun stabilize(subject: WebDavSyncRuntime) {
        subject.javaClass.getDeclaredMethod("stabilizeState").apply { isAccessible = true }
            .invoke(subject)
    }

    private class InterleavingStateFlow(
        private val delegate: MutableStateFlow<SyncState>,
        private val next: SyncState
    ) : MutableStateFlow<SyncState> by delegate {
        var injections = 0
            private set

        override var value: SyncState
            get() {
                val previous = delegate.value
                if (injections == 0) {
                    injections++
                    delegate.value = next
                }
                return previous
            }
            set(value) {
                delegate.value = value
            }
    }
}
