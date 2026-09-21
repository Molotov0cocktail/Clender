package com.molotov.clender.app.about

import com.molotov.clender.diagnostics.DiagnosticMaintenance
import com.molotov.clender.diagnostics.MaintenanceResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutMaintenanceControllerTest {
    @Test
    fun exportClosesItsStreamAndOnlyClaimsSuccessAfterTheIoCompletes() = runTest {
        val maintenance = FakeMaintenance()
        val controller = AboutMaintenanceController(
            maintenance,
            this,
            StandardTestDispatcher(testScheduler)
        )
        val output = TrackedOutput()
        var opened = false
        controller.export {
            opened = true
            output
        }
        assertTrue(controller.busy)
        assertFalse(opened)
        advanceUntilIdle()
        assertTrue(opened)
        assertTrue(output.closed)
        assertEquals("diagnostic codes", output.toString("UTF-8"))
        assertEquals(AboutMaintenanceStatus.EXPORTED, controller.status)
        assertFalse(controller.busy)
    }

    @Test
    fun pickerCancellationNeverOpensStorageOrInvokesAnyMaintenance() = runTest {
        val maintenance = FakeMaintenance()
        val controller = AboutMaintenanceController(
            maintenance,
            this,
            StandardTestDispatcher(testScheduler)
        )
        controller.export(null)
        advanceUntilIdle()
        assertEquals(0, maintenance.calls)
        assertEquals(AboutMaintenanceStatus.IDLE, controller.status)
        assertFalse(controller.busy)
    }

    @Test
    fun exportOpenAndCloseFailuresStaySafeAndCanBeRetried() = runTest {
        val maintenance = FakeMaintenance()
        val controller = AboutMaintenanceController(
            maintenance,
            this,
            StandardTestDispatcher(testScheduler)
        )
        controller.export { throw IOException("private credentials and content") }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        controller.export { null }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        controller.export { TrackedOutput(failClose = true) }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        controller.export { TrackedOutput() }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.EXPORTED, controller.status)
    }

    @Test
    fun busyRejectsReentryAndCleanupResultsRemainDistinct() = runTest {
        val maintenance = FakeMaintenance().apply { pending = CompletableDeferred() }
        val controller = AboutMaintenanceController(
            maintenance,
            this,
            StandardTestDispatcher(testScheduler)
        )
        controller.clearLogs()
        runCurrent()
        controller.clearLogs()
        controller.clearTemporaryCache()
        controller.export { error("busy must not open output") }
        assertEquals(1, maintenance.calls)
        assertTrue(controller.busy)
        maintenance.pending?.complete(Unit)
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.LOGS_CLEARED, controller.status)
        maintenance.pending = null
        controller.clearTemporaryCache()
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.CACHE_CLEARED, controller.status)
        assertEquals(2, maintenance.calls)
    }

    @Test
    fun failureAndUnavailableServiceAreVisibleAndNeverClaimCleanupSucceeded() = runTest {
        val maintenance = FakeMaintenance().apply { result = MaintenanceResult.FAILURE }
        val controller = AboutMaintenanceController(
            maintenance,
            this,
            StandardTestDispatcher(testScheduler)
        )
        controller.clearLogs()
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        maintenance.result = MaintenanceResult.SUCCESS
        controller.clearLogs()
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.LOGS_CLEARED, controller.status)
        controller.pickerFailed()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        val missing = AboutMaintenanceController(null, this, StandardTestDispatcher(testScheduler))
        missing.clearTemporaryCache()
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, missing.status)
    }

    @Test
    fun leavingDuringExportClosesTheStreamWithoutReportingSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val maintenance = FakeMaintenance().apply { pending = CompletableDeferred() }
        val controller = AboutMaintenanceController(maintenance, scope, dispatcher)
        val output = TrackedOutput()
        controller.export { output }
        runCurrent()
        assertTrue(controller.busy)
        scope.cancel()
        advanceUntilIdle()
        assertTrue(output.closed)
        assertFalse(controller.busy)
        assertEquals(AboutMaintenanceStatus.IDLE, controller.status)
    }

    @Test
    fun providerRuntimeOpenAndCloseFailuresAreSafeAndRetryable() = runTest {
        val maintenance = FakeMaintenance()
        val controller = AboutMaintenanceController(
            maintenance,
            this,
            StandardTestDispatcher(testScheduler)
        )
        controller.export { throw UnsupportedOperationException("private provider open detail") }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        controller.export {
            object : ByteArrayOutputStream() {
                override fun close(): Unit =
                    throw UnsupportedOperationException("private provider close detail")
            }
        }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.FAILED, controller.status)
        controller.export { TrackedOutput() }
        advanceUntilIdle()
        assertEquals(AboutMaintenanceStatus.EXPORTED, controller.status)
    }
}

private class FakeMaintenance : DiagnosticMaintenance {
    var calls = 0
    var pending: CompletableDeferred<Unit>? = null
    var result = MaintenanceResult.SUCCESS

    override suspend fun exportTo(output: OutputStream): MaintenanceResult {
        calls += 1
        pending?.await()
        output.write("diagnostic codes".toByteArray())
        return result
    }

    override suspend fun clearLogs(): MaintenanceResult {
        calls += 1
        pending?.await()
        return result
    }

    override suspend fun clearTemporaryCache(): MaintenanceResult = clearLogs()
}

private class TrackedOutput(private val failClose: Boolean = false) : ByteArrayOutputStream() {
    var closed = false

    override fun close() {
        closed = true
        if (failClose) throw IOException("synthetic private output location")
        super.close()
    }
}
