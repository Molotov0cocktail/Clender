package com.molotov.clender.app.about

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molotov.clender.diagnostics.DiagnosticMaintenance
import com.molotov.clender.diagnostics.MaintenanceResult
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AboutMaintenanceStatus { IDLE, WORKING, EXPORTED, LOGS_CLEARED, CACHE_CLEARED, FAILED }

class AboutMaintenanceController(
    private val maintenance: DiagnosticMaintenance?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var status by mutableStateOf(AboutMaintenanceStatus.IDLE)
        private set
    val busy: Boolean get() = status == AboutMaintenanceStatus.WORKING

    fun export(open: (() -> OutputStream?)?) {
        if (open == null) return
        operate(AboutMaintenanceStatus.EXPORTED) { service ->
            val output = open() ?: return@operate MaintenanceResult.FAILURE
            output.use { service.exportTo(it) }
        }
    }

    fun clearLogs() = operate(AboutMaintenanceStatus.LOGS_CLEARED) { it.clearLogs() }

    fun clearTemporaryCache() = operate(AboutMaintenanceStatus.CACHE_CLEARED) {
        it.clearTemporaryCache()
    }

    fun pickerFailed() {
        if (!busy) status = AboutMaintenanceStatus.FAILED
    }

    @Suppress("TooGenericExceptionCaught")
    private fun operate(
        success: AboutMaintenanceStatus,
        action: suspend (DiagnosticMaintenance) -> MaintenanceResult
    ) {
        if (busy || !scope.isActive) return
        status = AboutMaintenanceStatus.WORKING
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(ioDispatcher) {
                    maintenance?.let { action(it) } ?: MaintenanceResult.FAILURE
                }
                status = if (result == MaintenanceResult.SUCCESS) {
                    success
                } else {
                    AboutMaintenanceStatus.FAILED
                }
            } catch (cancelled: CancellationException) {
                status = AboutMaintenanceStatus.IDLE
                throw cancelled
            } catch (_: IOException) {
                status = AboutMaintenanceStatus.FAILED
            } catch (_: RuntimeException) {
                status = AboutMaintenanceStatus.FAILED
            }
        }
    }
}
