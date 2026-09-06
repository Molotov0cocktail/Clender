package com.molotov.clender.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistryOwner
import com.molotov.clender.app.widget.WidgetConfigurationApplicationService
import com.molotov.clender.app.widget.WidgetConfigurationRuntimePort
import com.molotov.clender.domain.widget.WidgetActionSpec
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.theme.ClenderTheme
import com.molotov.clender.ui.widget.WidgetConfigurationActions
import com.molotov.clender.ui.widget.WidgetConfigurationEffect
import com.molotov.clender.ui.widget.WidgetConfigurationScreen
import com.molotov.clender.ui.widget.WidgetConfigurationUiState
import com.molotov.clender.ui.widget.WidgetConfigurationViewModel
import com.molotov.clender.ui.widget.WidgetTimePickerDialog
import kotlinx.coroutines.flow.Flow

interface WidgetConfigurationActivityOwner {
    val widgetConfigurationRuntime: WidgetConfigurationRuntimePort
    val widgetConfigurationAppearance: Flow<AppearanceUiState>
}

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = WidgetConfigurationEntryValidator.validate(
            context = this,
            intent = intent,
            appWidgetManager = AppWidgetManager.getInstance(this)
        )
        if (appWidgetId == null) {
            finishInvalidEntry()
        } else {
            showConfiguration(appWidgetId)
        }
    }

    private fun finishInvalidEntry() {
        setResult(
            Activity.RESULT_CANCELED,
            widgetConfigurationResult(AppWidgetManager.INVALID_APPWIDGET_ID)
        )
        finish()
    }

    private fun showConfiguration(appWidgetId: Int) {
        setResult(Activity.RESULT_CANCELED, widgetConfigurationResult(appWidgetId))
        val runtimeOwner = application as WidgetConfigurationActivityOwner
        val viewModel = configurationViewModel(
            appWidgetId = appWidgetId,
            runtime = runtimeOwner.widgetConfigurationRuntime
        )
        setContent {
            ConfigurationActivityContent(
                viewModel = viewModel,
                appearanceFlow = runtimeOwner.widgetConfigurationAppearance,
                onComplete = ::completeConfiguration,
                onCancel = ::finish
            )
        }
    }

    private fun completeConfiguration(appWidgetId: Int) {
        setResult(Activity.RESULT_OK, widgetConfigurationResult(appWidgetId))
        finish()
    }

    private fun configurationViewModel(
        appWidgetId: Int,
        runtime: WidgetConfigurationRuntimePort
    ): WidgetConfigurationViewModel = ViewModelProvider(
        this,
        WidgetConfigurationViewModelFactory(
            owner = this,
            defaultArgs = null,
            appWidgetId = appWidgetId,
            runtime = runtime
        )
    )[WidgetConfigurationViewModel::class.java]
}

@Composable
private fun ConfigurationActivityContent(
    viewModel: WidgetConfigurationViewModel,
    appearanceFlow: Flow<AppearanceUiState>,
    onComplete: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by appearanceFlow.collectAsStateWithLifecycle(
        initialValue = AppearanceUiState.fromPersisted(null, null, null)
    )
    var discardConfirmation by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<TimeField?>(null) }
    ConfigurationEffects(
        viewModel = viewModel,
        onComplete = onComplete,
        onCancel = onCancel,
        onConfirmDiscard = { discardConfirmation = true }
    )
    BackHandler { viewModel.cancelOrBack() }
    ClenderTheme(appearance) {
        WidgetConfigurationScreen(
            state = state,
            actions = configurationActions(
                state = state,
                viewModel = viewModel,
                setPicker = { picker = it },
                setDiscardConfirmation = { discardConfirmation = it }
            ),
            showDiscardConfirmation = discardConfirmation
        )
    }
    ConfigurationTimePicker(state, picker, viewModel) { picker = null }
}

@Composable
private fun ConfigurationEffects(
    viewModel: WidgetConfigurationViewModel,
    onComplete: (Int) -> Unit,
    onCancel: () -> Unit,
    onConfirmDiscard: () -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WidgetConfigurationEffect.Complete -> onComplete(effect.appWidgetId)
                WidgetConfigurationEffect.Cancel -> onCancel()
                WidgetConfigurationEffect.ConfirmDiscard -> onConfirmDiscard()
            }
        }
    }
}

private fun configurationActions(
    state: WidgetConfigurationUiState,
    viewModel: WidgetConfigurationViewModel,
    setPicker: (TimeField) -> Unit,
    setDiscardConfirmation: (Boolean) -> Unit
) = WidgetConfigurationActions(
    onRetry = {
        if (state is WidgetConfigurationUiState.LoadFailed) {
            viewModel.retryLoad()
        } else {
            viewModel.save()
        }
    },
    onStartTimeClick = { setPicker(TimeField.START) },
    onEndTimeClick = { setPicker(TimeField.END) },
    onOpacityChange = viewModel::updateOpacityPercent,
    onFontSizeChange = viewModel::updateFontSizeSp,
    onThemeChange = viewModel::updateTheme,
    onSave = viewModel::save,
    onCancel = viewModel::cancelOrBack,
    onConfirmDiscard = {
        setDiscardConfirmation(false)
        viewModel.confirmDiscard()
    },
    onKeepEditing = { setDiscardConfirmation(false) }
)

@Composable
private fun ConfigurationTimePicker(
    state: WidgetConfigurationUiState,
    picker: TimeField?,
    viewModel: WidgetConfigurationViewModel,
    onDismiss: () -> Unit
) {
    val draft = state.draftOrNull() ?: return
    val selectedTime = when (picker) {
        TimeField.START -> draft.startTime
        TimeField.END -> draft.endTime
        null -> return
    }
    WidgetTimePickerDialog(
        selectedTime = selectedTime,
        onTimeSelected = { selected ->
            onDismiss()
            if (picker == TimeField.START) {
                viewModel.updateStartTime(selected)
            } else {
                viewModel.updateEndTime(selected)
            }
        },
        onDismiss = onDismiss
    )
}

internal object WidgetConfigurationEntryValidator {
    fun validate(context: Context, intent: Intent?, appWidgetManager: AppWidgetManager): Int? =
        runCatching {
            val extras = intent?.extras
            val appWidgetId = intent?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
            val canonicalData = appWidgetId.takeIf { it > 0 }
                ?.let { WidgetActionSpec.Configure(it).canonicalIdentity }
            val entryIsExact = intent?.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE &&
                extras?.keySet() == setOf(AppWidgetManager.EXTRA_APPWIDGET_ID) &&
                appWidgetId > 0 &&
                (intent.dataString == null || intent.dataString == canonicalData)
            val expectedProvider = ComponentName(context, ClenderWidgetProvider::class.java)
            val providerMatches = entryIsExact &&
                appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider == expectedProvider
            appWidgetId.takeIf { providerMatches }
        }.getOrNull()
}

private class WidgetConfigurationViewModelFactory(
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle?,
    private val appWidgetId: Int,
    private val runtime: WidgetConfigurationRuntimePort
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T = requireNotNull(
        modelClass.cast(
            WidgetConfigurationViewModel(
                appWidgetId = appWidgetId,
                service = WidgetConfigurationApplicationService(runtime),
                savedStateHandle = handle
            )
        )
    )
}

private enum class TimeField {
    START,
    END
}

private fun WidgetConfigurationUiState.draftOrNull() = when (this) {
    is WidgetConfigurationUiState.Content -> draft

    is WidgetConfigurationUiState.SaveFailed -> draft

    WidgetConfigurationUiState.Loading,
    is WidgetConfigurationUiState.LoadFailed -> null
}

private fun widgetConfigurationResult(appWidgetId: Int): Intent = Intent().putExtra(
    AppWidgetManager.EXTRA_APPWIDGET_ID,
    appWidgetId
)
