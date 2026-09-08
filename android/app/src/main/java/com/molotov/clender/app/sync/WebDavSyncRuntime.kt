package com.molotov.clender.app.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.molotov.clender.data.settings.WebDavSectionSnapshot
import com.molotov.clender.domain.sync.SyncCancellationException
import com.molotov.clender.domain.sync.SyncResult
import com.molotov.clender.sync.SyncCoordinator
import com.molotov.clender.sync.SyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

sealed interface WebDavRunResult {
    data class Completed(val uploaded: Boolean, val localChanged: Boolean, val eventCount: Int) :
        WebDavRunResult

    data object Unconfigured : WebDavRunResult
}

data class WebDavSyncSignals(
    val availability: Flow<WebDavAvailability>,
    val mutationVersion: Flow<Long>,
    val onRemoteVisibleChanged: () -> Unit = {}
)

/**
 * App-scoped WebDAV synchronization runtime observing exactly three sources:
 * non-secret availability (enabled/passwordConfigured), the local mutation
 * version signal and ProcessLifecycle foreground transitions. The active sync
 * run exclusivity comes from [gate] and work/wait/queue semantics come from an
 * internal [SyncCoordinator] (its new `requestDiagnosed` extension is public
 * for sync-package consumers but this runtime does not use it).
 *
 * Guarantees:
 * - No secret/network/Room access before the first availability emission and
 *   none at all while disabled or unconfigured.
 * - The initial mutation emission is ignored as a baseline; every subsequent
 *   increase triggers exactly one LOCAL_CHANGE request (coalesced while busy).
 * - Each onStart triggers one FOREGROUND request; onStop never cancels an
 *   active run and never triggers.
 * - A Settings-provided snapshot is staged and consumed by exactly one run.
 */
class WebDavSyncRuntime(
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
    private val gate: WebDavOperationGate,
    signals: WebDavSyncSignals,
    private val runSync: suspend (WebDavSectionSnapshot?) -> WebDavRunResult
) {
    constructor(
        scope: CoroutineScope,
        lifecycle: Lifecycle,
        gate: WebDavOperationGate,
        availability: Flow<WebDavAvailability>,
        mutationVersion: Flow<Long>,
        runSync: suspend (WebDavSectionSnapshot?) -> WebDavRunResult
    ) : this(scope, lifecycle, gate, WebDavSyncSignals(availability, mutationVersion), runSync)

    private val classifier = WebDavFailureClassifier()
    private val lock = Any()
    private val mutableState = MutableStateFlow<SyncState>(SyncState.Idle)

    val state: StateFlow<SyncState> = mutableState

    private val firstEmission = CompletableDeferred<Unit>()

    @Volatile
    private var cachedAvailability = WebDavAvailability(enabled = false, passwordConfigured = false)

    @Volatile
    private var stagedBinding: WebDavSectionSnapshot? = null

    @Volatile
    private var closed = false

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            scope.launch { requestSync(SyncTrigger.FOREGROUND) }
        }
    }

    private val coordinator = SyncCoordinator(
        scope = scope,
        isEnabled = { effectiveEnabled() },
        runSync = { executeRun() },
        onRemoteVisibleChanged = signals.onRemoteVisibleChanged,
        onFailure = { error -> mutableState.value = SyncState.Failed(classifier.classify(error)) }
    )

    private val availabilityJob: Job
    private val mutationJob: Job

    init {
        lifecycle.addObserver(lifecycleObserver)
        availabilityJob = scope.launch {
            signals.availability.collect { value ->
                cachedAvailability = value
                if (!firstEmission.isCompleted) firstEmission.complete(Unit)
                stabilizeState()
            }
        }
        mutationJob = scope.launch {
            signals.mutationVersion.drop(1).collect {
                requestSync(SyncTrigger.LOCAL_CHANGE)
            }
        }
    }

    suspend fun requestSync(
        trigger: SyncTrigger,
        snapshot: WebDavSectionSnapshot? = null
    ): SyncRequestDecision {
        firstEmission.await()
        return synchronized(lock) { decideAndStart(trigger, snapshot) }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stagedBinding = null
        }
        lifecycle.removeObserver(lifecycleObserver)
        availabilityJob.cancel()
        mutationJob.cancel()
        runBlocking { coordinator.shutdown() }
    }

    private fun decideAndStart(
        trigger: SyncTrigger,
        snapshot: WebDavSectionSnapshot?
    ): SyncRequestDecision = when {
        closed -> SyncRequestDecision.SHUTDOWN

        mutableState.value is SyncState.Running -> {
            if (snapshot != null) stagedBinding = snapshot
            coordinator.request(trigger)
            mutableState.value = SyncState.Running(pending = true)
            SyncRequestDecision.COALESCED
        }

        else -> startFresh(trigger, snapshot)
    }

    private fun startFresh(
        trigger: SyncTrigger,
        snapshot: WebDavSectionSnapshot?
    ): SyncRequestDecision {
        val effective = if (snapshot != null) {
            WebDavAvailability(
                enabled = snapshot.enabled,
                passwordConfigured = snapshot.passwordConfigured
            )
        } else {
            cachedAvailability
        }
        return when {
            !effective.enabled -> unavailable(SyncRequestDecision.DISABLED)

            !effective.passwordConfigured -> unavailable(SyncRequestDecision.UNCONFIGURED)

            else -> {
                if (snapshot != null) stagedBinding = snapshot
                mutableState.value = SyncState.Running(pending = false)
                if (coordinator.request(trigger)) {
                    SyncRequestDecision.STARTED
                } else {
                    mutableState.value = SyncState.Idle
                    SyncRequestDecision.SHUTDOWN
                }
            }
        }
    }

    private fun unavailable(decision: SyncRequestDecision): SyncRequestDecision {
        stagedBinding = null
        mutableState.value = if (decision == SyncRequestDecision.DISABLED) {
            SyncState.Disabled
        } else {
            SyncState.Unconfigured
        }
        return decision
    }

    private suspend fun executeRun(): SyncResult {
        val binding = synchronized(lock) {
            val current = stagedBinding
            stagedBinding = null
            current
        }
        mutableState.value = SyncState.Running(pending = false)
        return try {
            val result = gate.withExclusive { runSync(binding) }
            when (result) {
                is WebDavRunResult.Completed -> {
                    mutableState.value = SyncState.Success(
                        uploaded = result.uploaded,
                        localChanged = result.localChanged,
                        eventCount = result.eventCount
                    )
                    SyncResult(
                        localChanged = result.localChanged,
                        uploaded = result.uploaded,
                        eventCount = result.eventCount
                    )
                }

                WebDavRunResult.Unconfigured -> {
                    mutableState.value = SyncState.Unconfigured
                    SyncResult(localChanged = false, uploaded = false, eventCount = 0)
                }
            }
        } catch (error: SyncCancellationException) {
            mutableState.value = SyncState.Failed(SyncFailureCode.CANCELLED)
            throw error
        }
    }

    private fun effectiveEnabled(): Boolean =
        cachedAvailability.enabled || (stagedBinding?.enabled == true)

    private fun stabilizeState() {
        mutableState.update { current ->
            if (current !is SyncState.Idle &&
                current !is SyncState.Disabled &&
                current !is SyncState.Unconfigured
            ) {
                current
            } else {
                when {
                    !cachedAvailability.enabled -> SyncState.Disabled
                    !cachedAvailability.passwordConfigured -> SyncState.Unconfigured
                    else -> current
                }
            }
        }
    }
}
