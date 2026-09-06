package com.molotov.clender.ui.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun EventEditorScreen(
    state: EventCrudUiState,
    onFormChange: (EventFormState) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val form = state.form ?: return
    val locale = LocalConfiguration.current.locales[0]
    var pickerTarget by remember { mutableStateOf<EditorPickerTarget?>(null) }
    EditorFormContent(
        state = state,
        form = form,
        locale = locale,
        actions = EditorFormActions(
            onFormChange = onFormChange,
            onSave = onSave,
            onPickerRequested = { pickerTarget = it }
        ),
        modifier = modifier
    )
    EditorPicker(
        target = pickerTarget,
        form = form,
        onFormChange = onFormChange,
        onDismiss = { pickerTarget = null }
    )
}
