package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R

@Composable
fun DeleteConfirmationDialog(
    deleting: Boolean,
    error: EventDeleteErrorCode?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.event_delete_title),
                modifier = Modifier.testTag("event_delete_title")
            )
        },
        text = {
            when {
                deleting -> Text(
                    text = stringResource(R.string.event_deleting),
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("event_delete_busy")
                )

                error != null -> Text(
                    text = stringResource(deleteErrorResource(error)),
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("event_delete_error")
                )

                else -> Text(stringResource(R.string.event_delete_message))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_delete_confirm")
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !deleting,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_delete_cancel")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("event_delete_dialog")
    )
}

@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.event_discard_title),
                modifier = Modifier.testTag("event_discard_title")
            )
        },
        text = { Text(stringResource(R.string.event_discard_message)) },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_discard_confirm")
            ) {
                Text(stringResource(R.string.event_discard_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_discard_keep")
            ) {
                Text(stringResource(R.string.event_discard_keep_editing))
            }
        },
        modifier = Modifier.testTag("event_discard_dialog")
    )
}

private fun deleteErrorResource(error: EventDeleteErrorCode): Int = when (error) {
    EventDeleteErrorCode.NOT_FOUND -> R.string.event_delete_not_found
    EventDeleteErrorCode.DELETE_FAILED -> R.string.event_delete_failed
}
