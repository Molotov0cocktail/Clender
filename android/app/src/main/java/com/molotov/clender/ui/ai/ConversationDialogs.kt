package com.molotov.clender.ui.ai

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R

private data class ConfirmationDialogSpec(
    val dialogTag: String,
    val titleTag: String,
    val title: String,
    val message: String,
    val confirmTag: String,
    val cancelTag: String,
    val destructive: Boolean
)

@Composable
fun RenameConversationDialog(
    currentTitle: String,
    operationInProgress: Boolean = false,
    operationFailed: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(currentTitle) { mutableStateOf(currentTitle) }
    val trimmed = input.trim()
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        modifier = Modifier.testTag("conversation_rename_dialog"),
        title = {
            Text(
                text = stringResource(R.string.conversation_rename_title),
                modifier = Modifier.testTag("conversation_rename_title")
            )
        },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                enabled = !operationInProgress,
                singleLine = true,
                label = { Text(stringResource(R.string.conversation_rename_label)) },
                supportingText = if (operationFailed) {
                    {
                        Text(
                            text = stringResource(R.string.conversation_operation_failed),
                            modifier = Modifier.testTag("conversation_dialog_error")
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.testTag("conversation_rename_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = !operationInProgress && trimmed.isNotEmpty(),
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("conversation_rename_confirm")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            DialogCancelButton(
                tag = "conversation_rename_cancel",
                enabled = !operationInProgress,
                onDismiss = onDismiss
            )
        }
    )
}

@Composable
fun ClearConversationDialog(
    operationInProgress: Boolean = false,
    operationFailed: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        spec = ConfirmationDialogSpec(
            dialogTag = "conversation_clear_dialog",
            titleTag = "conversation_clear_title",
            title = stringResource(R.string.conversation_clear_title),
            message = stringResource(R.string.conversation_clear_message),
            confirmTag = "conversation_clear_confirm",
            cancelTag = "conversation_clear_cancel",
            destructive = false
        ),
        operationInProgress = operationInProgress,
        operationFailed = operationFailed,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
fun DeleteConversationDialog(
    operationInProgress: Boolean = false,
    operationFailed: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        spec = ConfirmationDialogSpec(
            dialogTag = "conversation_delete_dialog",
            titleTag = "conversation_delete_title",
            title = stringResource(R.string.conversation_delete_title),
            message = stringResource(R.string.conversation_delete_message),
            confirmTag = "conversation_delete_confirm",
            cancelTag = "conversation_delete_cancel",
            destructive = true
        ),
        operationInProgress = operationInProgress,
        operationFailed = operationFailed,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun ConfirmationDialog(
    spec: ConfirmationDialogSpec,
    operationInProgress: Boolean,
    operationFailed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        modifier = Modifier.testTag(spec.dialogTag),
        title = { Text(spec.title, Modifier.testTag(spec.titleTag)) },
        text = {
            Text(
                text = if (operationFailed) {
                    stringResource(R.string.conversation_operation_failed)
                } else {
                    spec.message
                },
                modifier = if (operationFailed) {
                    Modifier.testTag("conversation_dialog_error")
                } else {
                    Modifier
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !operationInProgress,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag(spec.confirmTag)
            ) {
                Text(
                    stringResource(
                        if (spec.destructive) {
                            R.string.action_delete
                        } else {
                            R.string.action_confirm
                        }
                    )
                )
            }
        },
        dismissButton = {
            DialogCancelButton(spec.cancelTag, !operationInProgress, onDismiss)
        }
    )
}

@Composable
private fun DialogCancelButton(tag: String, enabled: Boolean, onDismiss: () -> Unit) {
    TextButton(
        onClick = onDismiss,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(tag)
    ) {
        Text(stringResource(R.string.action_cancel))
    }
}
