package com.molotov.clender.sync

import com.molotov.clender.domain.sync.SyncCancellationException
import com.molotov.clender.domain.sync.SyncFailureException
import com.molotov.clender.domain.sync.SyncResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncTrigger {
    LOCAL_CHANGE,
    MANUAL,
    FOREGROUND
}

enum class SyncCoordinatorDecision {
    STARTED,
    COALESCED,
    DISABLED,
    UNCONFIGURED,
    REFRESHING,
    SHUTDOWN
}

class SyncCoordinator(
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val runSync: suspend () -> SyncResult,
    private val onRemoteVisibleChanged: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val stateLock = Any()
    private val runMutex = Mutex()
    private var worker: Job? = null
    private var pending = false
    private var shuttingDown = false
    private var remoteRefreshActive = false

    @Suppress("UNUSED_PARAMETER")
    fun request(trigger: SyncTrigger): Boolean = synchronized(stateLock) {
        when {
            shuttingDown || remoteRefreshActive -> false

            scope.coroutineContext[Job]?.isActive == false || !isEnabled() -> false

            worker?.isActive == true -> {
                pending = true
                false
            }

            else -> startWorkerLocked()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun requestDiagnosed(trigger: SyncTrigger): SyncCoordinatorDecision = synchronized(stateLock) {
        when {
            shuttingDown -> SyncCoordinatorDecision.SHUTDOWN

            remoteRefreshActive -> SyncCoordinatorDecision.REFRESHING

            scope.coroutineContext[Job]?.isActive == false || !isEnabled() ->
                SyncCoordinatorDecision.DISABLED

            worker?.isActive == true -> SyncCoordinatorDecision.COALESCED

            startWorkerLocked() -> SyncCoordinatorDecision.STARTED

            else -> SyncCoordinatorDecision.SHUTDOWN
        }
    }

    private fun startWorkerLocked(): Boolean {
        val newWorker = scope.launch(start = CoroutineStart.LAZY) { runLoop() }
        worker = newWorker
        newWorker.invokeOnCompletion {
            synchronized(stateLock) {
                if (worker === newWorker) worker = null
            }
        }
        if (!newWorker.start()) {
            if (worker === newWorker) worker = null
            return false
        }
        return true
    }

    suspend fun awaitIdle() {
        while (true) {
            val active = synchronized(stateLock) { worker } ?: return
            active.join()
        }
    }

    suspend fun shutdown() {
        val active = synchronized(stateLock) {
            shuttingDown = true
            pending = false
            worker
        }
        active?.cancelAndJoin()
        synchronized(stateLock) {
            if (worker === active) worker = null
        }
    }

    private suspend fun runLoop() {
        try {
            while (true) {
                val result = runOneSync()
                if (result?.localChanged == true) notifyRemoteVisibleChanged()
                val continuePending = synchronized(stateLock) {
                    val shouldContinue = pending && !shuttingDown && isEnabled()
                    pending = false
                    if (!shouldContinue) worker = null
                    shouldContinue
                }
                if (!continuePending) return
            }
        } finally {
            val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(stateLock) {
                if (worker === currentJob) {
                    pending = false
                    worker = null
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runOneSync(): SyncResult? = try {
        runMutex.withLock { runSync() }
    } catch (error: SyncCancellationException) {
        if (error.remoteVisibleChanged) notifyRemoteVisibleChanged()
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: SyncFailureException) {
        if (error.remoteVisibleChanged) notifyRemoteVisibleChanged()
        onFailure(error)
        null
    } catch (error: Exception) {
        onFailure(error)
        null
    }

    private fun notifyRemoteVisibleChanged() {
        synchronized(stateLock) { remoteRefreshActive = true }
        try {
            onRemoteVisibleChanged()
        } finally {
            synchronized(stateLock) { remoteRefreshActive = false }
        }
    }
}
