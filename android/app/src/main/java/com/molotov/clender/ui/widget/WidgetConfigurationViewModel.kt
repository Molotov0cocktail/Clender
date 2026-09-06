package com.molotov.clender.ui.widget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.app.widget.WidgetConfigurationApplicationService
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class WidgetConfigurationDraft(
    val appWidgetId: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val opacityPercent: Int,
    val fontSizeSp: Int,
    val theme: WidgetThemeMode
) {
    fun toConfiguration(): WidgetConfiguration = WidgetConfiguration(
        appWidgetId = appWidgetId,
        startTime = startTime,
        endTime = endTime,
        opacityPercent = opacityPercent,
        fontSizeSp = fontSizeSp,
        theme = theme
    )

    companion object {
        fun from(configuration: WidgetConfiguration): WidgetConfigurationDraft =
            WidgetConfigurationDraft(
                appWidgetId = configuration.appWidgetId,
                startTime = configuration.startTime,
                endTime = configuration.endTime,
                opacityPercent = configuration.opacityPercent,
                fontSizeSp = configuration.fontSizeSp,
                theme = configuration.theme
            )
    }
}

enum class WidgetConfigurationError {
    INVALID_TIME_RANGE,
    LOAD_FAILED,
    SAVE_FAILED
}

sealed interface WidgetConfigurationUiState {
    data object Loading : WidgetConfigurationUiState

    data class Content(
        val baseline: WidgetConfigurationDraft,
        val draft: WidgetConfigurationDraft,
        val dirty: Boolean,
        val saving: Boolean,
        val validation: WidgetConfigurationError?
    ) : WidgetConfigurationUiState

    data class LoadFailed(
        val error: WidgetConfigurationError = WidgetConfigurationError.LOAD_FAILED
    ) : WidgetConfigurationUiState

    data class SaveFailed(
        val baseline: WidgetConfigurationDraft,
        val draft: WidgetConfigurationDraft,
        val dirty: Boolean,
        val error: WidgetConfigurationError = WidgetConfigurationError.SAVE_FAILED
    ) : WidgetConfigurationUiState
}

sealed interface WidgetConfigurationEffect {
    data class Complete(val appWidgetId: Int) : WidgetConfigurationEffect

    data object Cancel : WidgetConfigurationEffect

    data object ConfirmDiscard : WidgetConfigurationEffect
}

class WidgetConfigurationViewModel(
    private val appWidgetId: Int,
    private val service: WidgetConfigurationApplicationService,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val restored = WidgetConfigurationSavedState.decode(savedStateHandle, appWidgetId)
    private val mutableState = MutableStateFlow<WidgetConfigurationUiState>(
        restored?.toContent() ?: WidgetConfigurationUiState.Loading
    )
    val state: StateFlow<WidgetConfigurationUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<WidgetConfigurationEffect>(Channel.BUFFERED)
    val effects: Flow<WidgetConfigurationEffect> = effectChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var operationInProgress = false

    private val loadConfiguration: () -> Unit = {
        mutableState.value = WidgetConfigurationUiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            yield()
            try {
                val draft = WidgetConfigurationDraft.from(service.load(appWidgetId))
                mutableState.value = WidgetConfigurationUiState.Content(
                    baseline = draft,
                    draft = draft,
                    dirty = false,
                    saving = false,
                    validation = null
                )
                WidgetConfigurationSavedState.encode(savedStateHandle, draft, draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = WidgetConfigurationUiState.LoadFailed()
            }
        }
    }

    init {
        require(appWidgetId > 0) { "Widget ID must be positive" }
        if (restored == null) loadConfiguration()
    }

    fun retryLoad() {
        if (mutableState.value !is WidgetConfigurationUiState.LoadFailed || operationInProgress) {
            return
        }
        loadConfiguration()
    }

    fun updateDraft(draft: WidgetConfigurationDraft) {
        if (operationInProgress || draft.appWidgetId != appWidgetId) return
        val baseline = mutableState.value.baselineOrNull() ?: return
        mutableState.value = WidgetConfigurationUiState.Content(
            baseline = baseline,
            draft = draft,
            dirty = draft != baseline,
            saving = false,
            validation = null
        )
        WidgetConfigurationSavedState.encode(savedStateHandle, baseline, draft)
    }

    fun updateStartTime(time: LocalTime) {
        mutableState.value.draftOrNull()?.let { draft ->
            updateDraft(draft.copy(startTime = time.toMinutePrecision()))
        }
    }

    fun updateEndTime(time: LocalTime) {
        mutableState.value.draftOrNull()?.let { draft ->
            updateDraft(draft.copy(endTime = time.toMinutePrecision()))
        }
    }

    fun updateOpacityPercent(opacityPercent: Int) {
        mutableState.value.draftOrNull()?.let { draft ->
            updateDraft(draft.copy(opacityPercent = opacityPercent))
        }
    }

    fun updateFontSizeSp(fontSizeSp: Int) {
        mutableState.value.draftOrNull()?.let { draft ->
            updateDraft(draft.copy(fontSizeSp = fontSizeSp))
        }
    }

    fun updateTheme(theme: WidgetThemeMode) {
        mutableState.value.draftOrNull()?.let { draft ->
            updateDraft(draft.copy(theme = theme))
        }
    }

    fun save() {
        val snapshot = mutableState.value.editableSnapshot()
            ?.takeUnless { operationInProgress }
            ?: return
        val (baseline, draft) = snapshot
        val configuration = runCatching(draft::toConfiguration).getOrNull()
        if (configuration == null) {
            mutableState.value = WidgetConfigurationUiState.Content(
                baseline = baseline,
                draft = draft,
                dirty = draft != baseline,
                saving = false,
                validation = WidgetConfigurationError.INVALID_TIME_RANGE
            )
            WidgetConfigurationSavedState.encode(savedStateHandle, baseline, draft)
        } else {
            operationInProgress = true
            mutableState.value = WidgetConfigurationUiState.Content(
                baseline = baseline,
                draft = draft,
                dirty = draft != baseline,
                saving = true,
                validation = null
            )
            saveJob = viewModelScope.launch {
                yield()
                try {
                    service.save(configuration)
                    mutableState.value = WidgetConfigurationUiState.Content(
                        baseline = draft,
                        draft = draft,
                        dirty = false,
                        saving = false,
                        validation = null
                    )
                    WidgetConfigurationSavedState.encode(savedStateHandle, draft, draft)
                    effectChannel.trySend(WidgetConfigurationEffect.Complete(appWidgetId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = WidgetConfigurationUiState.SaveFailed(
                        baseline = baseline,
                        draft = draft,
                        dirty = draft != baseline
                    )
                    WidgetConfigurationSavedState.encode(savedStateHandle, baseline, draft)
                } finally {
                    operationInProgress = false
                }
            }
        }
    }

    fun cancelOrBack() {
        if (operationInProgress) return
        val dirty = when (val current = mutableState.value) {
            is WidgetConfigurationUiState.Content -> current.dirty

            is WidgetConfigurationUiState.SaveFailed -> current.dirty

            WidgetConfigurationUiState.Loading,
            is WidgetConfigurationUiState.LoadFailed
            -> false
        }
        effectChannel.trySend(
            if (dirty) {
                WidgetConfigurationEffect.ConfirmDiscard
            } else {
                WidgetConfigurationEffect.Cancel
            }
        )
    }

    fun confirmDiscard() {
        val dirty = when (val current = mutableState.value) {
            is WidgetConfigurationUiState.Content -> current.dirty
            is WidgetConfigurationUiState.SaveFailed -> current.dirty
            else -> false
        }
        if (!operationInProgress && dirty) {
            effectChannel.trySend(WidgetConfigurationEffect.Cancel)
        }
    }
}

private data class WidgetConfigurationSavedState(
    val baseline: WidgetConfigurationDraft,
    val draft: WidgetConfigurationDraft
) {
    fun toContent(): WidgetConfigurationUiState.Content = WidgetConfigurationUiState.Content(
        baseline = baseline,
        draft = draft,
        dirty = draft != baseline,
        saving = false,
        validation = null
    )

    companion object {
        fun encode(
            handle: SavedStateHandle,
            baseline: WidgetConfigurationDraft,
            draft: WidgetConfigurationDraft
        ) {
            handle[WIDGET_ID_KEY] = baseline.appWidgetId
            handle[BASELINE_START_KEY] = baseline.startTime.toNanoOfDay()
            handle[BASELINE_END_KEY] = baseline.endTime.toNanoOfDay()
            handle[BASELINE_OPACITY_KEY] = baseline.opacityPercent
            handle[BASELINE_FONT_KEY] = baseline.fontSizeSp
            handle[BASELINE_THEME_KEY] = baseline.theme.name
            handle[DRAFT_START_KEY] = draft.startTime.toNanoOfDay()
            handle[DRAFT_END_KEY] = draft.endTime.toNanoOfDay()
            handle[DRAFT_OPACITY_KEY] = draft.opacityPercent
            handle[DRAFT_FONT_KEY] = draft.fontSizeSp
            handle[DRAFT_THEME_KEY] = draft.theme.name
        }

        fun decode(handle: SavedStateHandle, requestedId: Int): WidgetConfigurationSavedState? =
            runCatching {
                val storedId = handle.get<Int>(WIDGET_ID_KEY)
                    ?.takeIf { it == requestedId }
                    ?: return@runCatching null
                val baseline = WidgetConfigurationDraft(
                    appWidgetId = storedId,
                    startTime = handle.time(BASELINE_START_KEY) ?: return@runCatching null,
                    endTime = handle.time(BASELINE_END_KEY) ?: return@runCatching null,
                    opacityPercent = handle[BASELINE_OPACITY_KEY] ?: return@runCatching null,
                    fontSizeSp = handle[BASELINE_FONT_KEY] ?: return@runCatching null,
                    theme = handle.theme(BASELINE_THEME_KEY) ?: return@runCatching null
                )
                baseline.toConfiguration()
                val draft = WidgetConfigurationDraft(
                    appWidgetId = storedId,
                    startTime = handle.time(DRAFT_START_KEY) ?: return@runCatching null,
                    endTime = handle.time(DRAFT_END_KEY) ?: return@runCatching null,
                    opacityPercent = handle[DRAFT_OPACITY_KEY] ?: return@runCatching null,
                    fontSizeSp = handle[DRAFT_FONT_KEY] ?: return@runCatching null,
                    theme = handle.theme(DRAFT_THEME_KEY) ?: return@runCatching null
                )
                WidgetConfigurationSavedState(baseline, draft)
            }.getOrNull()
    }
}

private fun WidgetConfigurationUiState.baselineOrNull(): WidgetConfigurationDraft? = when (this) {
    is WidgetConfigurationUiState.Content -> baseline
    is WidgetConfigurationUiState.SaveFailed -> baseline
    else -> null
}

private fun WidgetConfigurationUiState.draftOrNull(): WidgetConfigurationDraft? = when (this) {
    is WidgetConfigurationUiState.Content -> draft
    is WidgetConfigurationUiState.SaveFailed -> draft
    else -> null
}

private fun WidgetConfigurationUiState.editableSnapshot(): Pair<
    WidgetConfigurationDraft,
    WidgetConfigurationDraft
    >? =
    baselineOrNull()?.let { baseline ->
        draftOrNull()?.let { draft -> baseline to draft }
    }

private fun LocalTime.toMinutePrecision(): LocalTime = withSecond(0).withNano(0)

private fun SavedStateHandle.time(key: String): LocalTime? = get<Long>(key)
    ?.takeIf { it in 0 until NANOS_PER_DAY }
    ?.let(LocalTime::ofNanoOfDay)

private fun SavedStateHandle.theme(key: String): WidgetThemeMode? = get<String>(key)
    ?.let { raw -> WidgetThemeMode.entries.firstOrNull { it.name == raw } }

private const val NANOS_PER_DAY = 86_400_000_000_000L
private const val KEY_PREFIX = "widget_configuration_editor_"
private const val WIDGET_ID_KEY = "${KEY_PREFIX}id"
private const val BASELINE_START_KEY = "${KEY_PREFIX}baseline_start"
private const val BASELINE_END_KEY = "${KEY_PREFIX}baseline_end"
private const val BASELINE_OPACITY_KEY = "${KEY_PREFIX}baseline_opacity"
private const val BASELINE_FONT_KEY = "${KEY_PREFIX}baseline_font"
private const val BASELINE_THEME_KEY = "${KEY_PREFIX}baseline_theme"
private const val DRAFT_START_KEY = "${KEY_PREFIX}draft_start"
private const val DRAFT_END_KEY = "${KEY_PREFIX}draft_end"
private const val DRAFT_OPACITY_KEY = "${KEY_PREFIX}draft_opacity"
private const val DRAFT_FONT_KEY = "${KEY_PREFIX}draft_font"
private const val DRAFT_THEME_KEY = "${KEY_PREFIX}draft_theme"
