package com.molotov.clender.diagnostics

import com.molotov.clender.domain.sync.SyncResult
import com.molotov.clender.sync.SyncCoordinator
import com.molotov.clender.sync.SyncTrigger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiagnosticsTest {
    @Test
    fun syncFailureIsDiagnosedWithoutExceptionMessage() = runBlocking {
        val records = CopyOnWriteArrayList<DiagnosticRecord>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = SyncCoordinator(
            scope,
            { true },
            { throw IllegalStateException("synthetic secret") },
            {},
            DiagnosticLogger { records += it }
        )
        try {
            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            coordinator.awaitIdle()
            assertTrue(
                records.any {
                    it.event == DiagnosticEvent.SYNC && it.result == DiagnosticResult.FAILURE
                }
            )
        } finally {
            coordinator.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun brokenLoggerDoesNotChangeSuccessfulSyncResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var refreshed = false
        val coordinator = SyncCoordinator(
            scope,
            { true },
            { SyncResult(true, false, 0) },
            { refreshed = true },
            DiagnosticLogger { throw IllegalStateException() }
        )
        try {
            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            coordinator.awaitIdle()
            assertTrue(refreshed)
        } finally {
            coordinator.shutdown()
            scope.cancel()
        }
    }
}
