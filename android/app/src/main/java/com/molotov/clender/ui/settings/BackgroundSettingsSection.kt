package com.molotov.clender.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.theme.LocalBackgroundController
import kotlin.math.roundToInt

@Composable
fun BackgroundSettingsSection() {
    val controller = LocalBackgroundController.current ?: return
    val resolver = LocalContext.current.contentResolver
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) controller.choose(resolver, uri)
        }
    var strength by remember(controller.selection.strength, controller.busy) {
        mutableFloatStateOf(controller.selection.strength.toFloat())
    }
    val label = stringResource(R.string.background_strength, strength.roundToInt())
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.background_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(R.string.background_local_hint),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = { launcher.launch(arrayOf("image/*")) },
            enabled = !controller.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background_choose")
        ) {
            Text(stringResource(R.string.background_choose))
        }
        Text(label)
        Slider(
            value = strength,
            onValueChange = { strength = it },
            onValueChangeFinished = { controller.setStrength(strength.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !controller.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("background_strength").semantics { contentDescription = label }
        )
        if (controller.selection.fileName != null) {
            OutlinedButton(
                onClick = controller::remove,
                enabled = !controller.busy,
                modifier = Modifier.fillMaxWidth().heightIn(
                    min = 48.dp
                ).testTag("background_remove")
            ) {
                Text(stringResource(R.string.background_remove))
            }
        }
        BackgroundOperationStatus(controller.busy, controller.failed)
    }
}

@Composable
private fun BackgroundOperationStatus(busy: Boolean, failed: Boolean) {
    if (!busy && !failed) return
    Text(
        stringResource(if (busy) R.string.background_busy else R.string.background_failed),
        color = if (busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(if (busy) "background_busy" else "background_error").semantics {
            liveRegion = LiveRegionMode.Polite
        }
    )
}
