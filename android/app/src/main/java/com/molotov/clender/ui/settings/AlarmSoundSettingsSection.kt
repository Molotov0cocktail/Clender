package com.molotov.clender.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.event.AlertPermissionSection

@Composable
internal fun AlertSettingsSection() {
    AlarmSoundSettingsSection()
    AlertPermissionSection()
}

@Composable
fun AlarmSoundSettingsSection() {
    val context = LocalContext.current
    val controller = remember(context) { alarmSoundViewModel(context).controller }
    AlarmSoundSettingsContent(controller)
}

@Composable
internal fun AlarmSoundSettingsContent(controller: AlarmSoundController) {
    val resolver = LocalContext.current.contentResolver
    var ticket by remember { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        ticket?.let { request ->
            controller.choose(
                request,
                uri?.let { selected ->
                    {
                        require(selected.scheme == "content") { "Unsupported audio location" }
                        resolver.openInputStream(selected)
                    }
                }
            )
        }
        ticket = null
    }
    DisposableEffect(controller) {
        controller.enter()
        onDispose { controller.leave() }
    }
    AlarmSoundControls(controller) {
        controller.launchSelection { request, types ->
            ticket = request
            launcher.launch(types)
        }
    }
}

@Composable
private fun AlarmSoundControls(controller: AlarmSoundController, onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.alarm_sound_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(stringResource(R.string.alarm_sound_hint), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(
                if (controller.selected) {
                    R.string.alarm_sound_selected
                } else {
                    R.string.alarm_sound_system
                }
            )
        )
        OutlinedButton(
            onClick = onChoose,
            enabled = !controller.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("alarm_sound_choose")
        ) { Text(stringResource(R.string.alarm_sound_choose)) }
        OutlinedButton(
            onClick = controller::remove,
            enabled = controller.selected && !controller.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("alarm_sound_remove")
        ) { Text(stringResource(R.string.alarm_sound_remove)) }
        AlarmSoundOperationStatus(controller.busy, controller.failed)
    }
}

@Composable
private fun AlarmSoundOperationStatus(busy: Boolean, failed: Boolean) {
    if (!busy && !failed) return
    Text(
        stringResource(
            if (busy) R.string.alarm_sound_busy else R.string.alarm_sound_failed
        ),
        color = if (busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        modifier = Modifier
            .testTag(if (busy) "alarm_sound_busy" else "alarm_sound_error")
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}
