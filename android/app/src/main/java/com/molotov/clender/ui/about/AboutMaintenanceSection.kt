package com.molotov.clender.ui.about

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.app.about.AboutMaintenanceController
import com.molotov.clender.app.about.AboutMaintenanceStatus
import com.molotov.clender.diagnostics.DiagnosticMaintenance

@Composable
internal fun AboutMaintenanceSection(maintenance: DiagnosticMaintenance?) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val controller = remember(maintenance, scope) { AboutMaintenanceController(maintenance, scope) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var clearLogs by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (picking) {
            picking = false
            controller.export(
                uri?.let { selected ->
                    {
                        require(selected.scheme == "content") { "Unsupported export destination" }
                        resolver.openOutputStream(selected, "wt")
                    }
                }
            )
        }
    }
    val launch: () -> Unit = {
        if (!picking && !controller.busy) {
            picking = true
            try {
                launcher.launch("clender-diagnostics.txt")
            } catch (_: ActivityNotFoundException) {
                picking = false
                controller.pickerFailed()
            } catch (_: SecurityException) {
                picking = false
                controller.pickerFailed()
            }
        }
    }
    AboutMaintenanceControls(
        busy = picking || controller.busy,
        status = controller.status,
        onExport = launch,
        onClearLogs = { clearLogs = true },
        onClearCache = { clearLogs = false }
    )
    clearLogs?.let { logs ->
        MaintenanceConfirmation(
            logs = logs,
            onDismiss = { clearLogs = null },
            onConfirm = {
                clearLogs = null
                if (logs) controller.clearLogs() else controller.clearTemporaryCache()
            }
        )
    }
}

@Composable
private fun AboutMaintenanceControls(
    busy: Boolean,
    status: AboutMaintenanceStatus,
    onExport: () -> Unit,
    onClearLogs: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.about_maintenance_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            stringResource(R.string.about_maintenance_hint),
            modifier = Modifier.testTag("about_maintenance_hint"),
            style = MaterialTheme.typography.bodyLarge
        )
        MaintenanceButton(R.string.about_export_logs, "about_export_logs", !busy, onExport)
        MaintenanceButton(R.string.about_clear_logs, "about_clear_logs", !busy, onClearLogs)
        MaintenanceButton(R.string.about_clear_cache, "about_clear_cache", !busy, onClearCache)
        MaintenanceStatus(if (busy) AboutMaintenanceStatus.WORKING else status)
    }
}

@Composable
private fun MaintenanceButton(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
    ) { Text(stringResource(label)) }
}

@Composable
private fun MaintenanceStatus(status: AboutMaintenanceStatus) {
    val label = when (status) {
        AboutMaintenanceStatus.IDLE -> return
        AboutMaintenanceStatus.WORKING -> R.string.about_maintenance_busy
        AboutMaintenanceStatus.EXPORTED -> R.string.about_logs_exported
        AboutMaintenanceStatus.LOGS_CLEARED -> R.string.about_logs_cleared
        AboutMaintenanceStatus.CACHE_CLEARED -> R.string.about_cache_cleared
        AboutMaintenanceStatus.FAILED -> R.string.about_maintenance_failed
    }
    Text(
        stringResource(label),
        modifier = Modifier.testTag("about_maintenance_status")
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (status == AboutMaintenanceStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onBackground
        },
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun MaintenanceConfirmation(logs: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (logs) R.string.about_clear_logs else R.string.about_clear_cache)
            )
        },
        text = {
            Text(
                stringResource(
                    if (logs) {
                        R.string.about_clear_logs_confirm
                    } else {
                        R.string.about_clear_cache_confirm
                    }
                ),
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("about_maintenance_confirm")
            ) {
                Text(stringResource(R.string.about_maintenance_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("about_maintenance_cancel")
            ) {
                Text(stringResource(R.string.about_maintenance_cancel))
            }
        }
    )
}
