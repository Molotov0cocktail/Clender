package com.molotov.clender.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.molotov.clender.R
import com.molotov.clender.data.network.ai.AiModelCapabilities
import com.molotov.clender.data.settings.ThinkingEffort

internal fun AiSettingsDraft.applyCapabilities(
    capabilities: AiModelCapabilities?
): AiSettingsDraft {
    val suppliedContext = capabilities?.contextWindow?.takeIf { it > 0 }
    val suppliedOutput = capabilities?.maxOutputTokens?.takeIf { it > 0 }
    val context = suppliedContext ?: contextWindow.toIntOrNull()
    val requestedOutput = suppliedOutput ?: maxOutputTokens.toIntOrNull()
    val availableOutput = context?.let { limit ->
        limit - maxOf(MIN_INPUT_RESERVE, limit / INPUT_RESERVE_DIVISOR) -
            maxOf(MIN_SAFETY_RESERVE, limit / SAFETY_RESERVE_DIVISOR)
    }
    val hasSuppliedLimits = suppliedContext != null || suppliedOutput != null
    val hasUsableOutputBudget = availableOutput != null && availableOutput > 0
    val hasRequiredValues = context != null && requestedOutput != null
    return if (!hasSuppliedLimits || !hasRequiredValues || !hasUsableOutputBudget) {
        this
    } else {
        copy(
            contextWindow = requireNotNull(context).toString(),
            maxOutputTokens = minOf(
                requireNotNull(requestedOutput),
                requireNotNull(availableOutput)
            ).toString()
        )
    }
}

@Composable
internal fun ThinkingEffortSelector(
    state: SettingsUiState,
    operationActive: Boolean,
    actions: SettingsActions
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !operationActive,
            modifier = Modifier.fillMaxWidth().taggedTarget("settings_ai_effort")
        ) {
            Text(stringResource(R.string.settings_ai_effort) + ": " + state.ai.thinkingEffort.name)
        }
        DropdownMenu(expanded = expanded && !operationActive, onDismissRequest = {
            expanded = false
        }) {
            ThinkingEffort.entries.forEach { effort ->
                DropdownMenuItem(
                    text = { Text(effort.name) },
                    onClick = {
                        actions.onAiChange(state.ai.copy(thinkingEffort = effort))
                        expanded = false
                    },
                    modifier = Modifier.testTag("settings_ai_effort_${effort.name.lowercase()}")
                )
            }
        }
    }
}

private const val MIN_INPUT_RESERVE = 1_024
private const val MIN_SAFETY_RESERVE = 32
private const val INPUT_RESERVE_DIVISOR = 4
private const val SAFETY_RESERVE_DIVISOR = 10
