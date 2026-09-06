package com.molotov.clender.ui.widget

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.molotov.clender.app.widget.WidgetConfigurationApplicationService
import com.molotov.clender.app.widget.WidgetConfigurationRuntimePort
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetConfigurationViewModelTest {
    @Test
    fun existingAndMissingConfigurationsReachCleanContentWithoutWriting() {
        val existing = configuration(
            appWidgetId = 12,
            times = LocalTime.of(0, 0) to LocalTime.of(23, 59),
            opacityPercent = 0,
            fontSizeSp = 20,
            theme = WidgetThemeMode.LIGHT
        )
        val existingRuntime = ViewModelRuntime(existing = existing)
        val existingViewModel = viewModel(12, existingRuntime)
        assertEquals(WidgetConfigurationUiState.Loading, existingViewModel.state.value)
        idleMain()

        val existingContent = existingViewModel.state.value as WidgetConfigurationUiState.Content
        assertEquals(WidgetConfigurationDraft.from(existing), existingContent.baseline)
        assertEquals(existingContent.baseline, existingContent.draft)
        assertFalse(existingContent.dirty)
        assertFalse(existingContent.saving)
        assertNull(existingContent.validation)
        assertTrue(existingRuntime.saved.isEmpty())

        val missingRuntime = ViewModelRuntime(fontSizeSp = 8)
        val missingViewModel = viewModel(13, missingRuntime)
        idleMain()
        val missing = missingViewModel.state.value as WidgetConfigurationUiState.Content
        assertEquals(
            WidgetConfigurationDraft.from(WidgetConfiguration.defaults(13, 8)),
            missing.draft
        )
        assertFalse(missing.dirty)
        assertTrue(missingRuntime.saved.isEmpty())
    }

    @Test
    fun pickerUpdatesNormalizeSecondsAndNanosAndDirtyTracksBaseline() {
        val runtime = ViewModelRuntime(existing = configuration(20))
        val viewModel = viewModel(20, runtime)
        idleMain()

        viewModel.updateStartTime(LocalTime.of(9, 17, 42, 123))
        var content = viewModel.state.value as WidgetConfigurationUiState.Content
        assertEquals(LocalTime.of(9, 17), content.draft.startTime)
        assertTrue(content.dirty)

        viewModel.updateStartTime(LocalTime.of(8, 0, 59, 999))
        content = viewModel.state.value as WidgetConfigurationUiState.Content
        assertEquals(LocalTime.of(8, 0), content.draft.startTime)
        assertFalse(content.dirty)
    }

    @Test
    fun allDocumentedBoundariesCanBeSavedExactlyOnce() {
        val runtime = ViewModelRuntime(existing = configuration(31))
        val viewModel = viewModel(31, runtime)
        idleMain()
        val boundary = WidgetConfigurationDraft(
            appWidgetId = 31,
            startTime = LocalTime.of(0, 0),
            endTime = LocalTime.of(23, 59),
            opacityPercent = 0,
            fontSizeSp = 8,
            theme = WidgetThemeMode.DARK
        )

        viewModel.updateDraft(boundary)
        viewModel.save()
        idleMain()

        assertEquals(listOf(boundary.toConfiguration()), runtime.saved)
        assertEquals(listOf(31), runtime.updateCalls)
        assertEquals(WidgetConfigurationEffect.Complete(31), nextEffect(viewModel))
        assertNoEffect(viewModel)
    }

    @Test
    fun invalidTimeOrderPrecisionOpacityAndFontFailClosedWithoutWrites() {
        val invalidDrafts = listOf(
            draft(40, times = LocalTime.of(10, 0) to LocalTime.of(10, 0)),
            draft(40, times = LocalTime.of(10, 1) to LocalTime.of(10, 0)),
            draft(40, times = LocalTime.of(8, 0, 1) to LocalTime.of(22, 0)),
            draft(40, times = LocalTime.of(8, 0) to LocalTime.of(22, 0, 1)),
            draft(40, opacity = -1),
            draft(40, opacity = 101),
            draft(40, font = 7),
            draft(40, font = 21)
        )

        invalidDrafts.forEach { invalid ->
            val runtime = ViewModelRuntime(existing = configuration(40))
            val viewModel = viewModel(40, runtime)
            idleMain()
            viewModel.updateDraft(invalid)
            viewModel.save()
            idleMain()

            val state = viewModel.state.value as WidgetConfigurationUiState.Content
            assertEquals(WidgetConfigurationError.INVALID_TIME_RANGE, state.validation)
            assertEquals(invalid, state.draft)
            assertTrue(runtime.saved.isEmpty())
            assertTrue(runtime.updateCalls.isEmpty())
            assertNoEffect(viewModel)
        }
    }

    @Test
    fun operationGuardRejectsDuplicateSaveCancelAndBackWhileSaving() {
        val gate = CompletableDeferred<Unit>()
        val runtime = ViewModelRuntime(existing = configuration(51), saveGate = gate)
        val viewModel = viewModel(51, runtime)
        idleMain()
        viewModel.updateDraft(draft(51, opacity = 40))

        viewModel.save()
        idleMain()
        assertTrue((viewModel.state.value as WidgetConfigurationUiState.Content).saving)
        viewModel.save()
        viewModel.cancelOrBack()
        viewModel.cancelOrBack()
        assertEquals(1, runtime.saveAttempts)
        assertNoEffect(viewModel)

        gate.complete(Unit)
        idleMain()
        assertEquals(1, runtime.saved.size)
        assertEquals(WidgetConfigurationEffect.Complete(51), nextEffect(viewModel))
        assertNoEffect(viewModel)
    }

    @Test
    fun saveFailureKeepsDirtyDraftAllowsRetryAndLeaksNoExceptionText() {
        val secret = "private datastore file / event title"
        val runtime = ViewModelRuntime(
            existing = configuration(61),
            saveFailure = IllegalStateException(secret)
        )
        val viewModel = viewModel(61, runtime)
        idleMain()
        val edited = draft(61, opacity = 61, font = 20, theme = WidgetThemeMode.DARK)
        viewModel.updateDraft(edited)

        viewModel.save()
        idleMain()

        val failed = viewModel.state.value as WidgetConfigurationUiState.SaveFailed
        assertEquals(WidgetConfigurationError.SAVE_FAILED, failed.error)
        assertEquals(edited, failed.draft)
        assertTrue(failed.dirty)
        assertFalse(failed.toString().contains(secret))
        assertTrue(runtime.updateCalls.isEmpty())
        assertNoEffect(viewModel)

        runtime.saveFailure = null
        viewModel.save()
        idleMain()
        assertEquals(2, runtime.saveAttempts)
        assertEquals(WidgetConfigurationEffect.Complete(61), nextEffect(viewModel))
    }

    @Test
    fun updateFailureStillCompletesAndDoesNotExposeExceptionDetails() {
        val secret = "launcher package and internal path"
        val runtime = ViewModelRuntime(
            existing = configuration(71),
            updateFailure = IllegalStateException(secret)
        )
        val viewModel = viewModel(71, runtime)
        idleMain()
        viewModel.updateDraft(draft(71, font = 20))

        viewModel.save()
        idleMain()

        assertEquals(1, runtime.saved.size)
        assertEquals(WidgetConfigurationEffect.Complete(71), nextEffect(viewModel))
        assertFalse(viewModel.state.value.toString().contains(secret))
    }

    @Test
    fun loadFailureIsFiniteRetryableAndDoesNotLeakExceptionText() {
        val secret = "private preferences path"
        val runtime = ViewModelRuntime(loadFailure = IllegalStateException(secret))
        val viewModel = viewModel(81, runtime)
        idleMain()

        val failed = viewModel.state.value as WidgetConfigurationUiState.LoadFailed
        assertEquals(WidgetConfigurationError.LOAD_FAILED, failed.error)
        assertFalse(failed.toString().contains(secret))
        assertEquals(1, runtime.loadCalls)
        viewModel.cancelOrBack()
        assertEquals(WidgetConfigurationEffect.Cancel, nextEffect(viewModel))

        runtime.loadFailure = null
        viewModel.retryLoad()
        idleMain()
        assertTrue(viewModel.state.value is WidgetConfigurationUiState.Content)
        assertEquals(2, runtime.loadCalls)
        assertTrue(runtime.saved.isEmpty())
    }

    @Test
    fun cleanCancelIsImmediateWhileDirtyCancelRequiresExplicitDiscard() {
        val cleanRuntime = ViewModelRuntime(existing = configuration(91))
        val clean = viewModel(91, cleanRuntime)
        idleMain()
        clean.cancelOrBack()
        assertEquals(WidgetConfigurationEffect.Cancel, nextEffect(clean))
        assertTrue(cleanRuntime.saved.isEmpty())

        val dirtyRuntime = ViewModelRuntime(existing = configuration(92))
        val dirty = viewModel(92, dirtyRuntime)
        idleMain()
        dirty.updateDraft(draft(92, opacity = 73))
        dirty.cancelOrBack()
        assertEquals(WidgetConfigurationEffect.ConfirmDiscard, nextEffect(dirty))
        assertNoEffect(dirty)
        dirty.cancelOrBack()
        assertEquals(WidgetConfigurationEffect.ConfirmDiscard, nextEffect(dirty))
        dirty.confirmDiscard()
        assertEquals(WidgetConfigurationEffect.Cancel, nextEffect(dirty))
        assertTrue(dirtyRuntime.saved.isEmpty())
        assertTrue(dirtyRuntime.updateCalls.isEmpty())
    }

    @Test
    fun recreationRestoresOnlyNonSecretDraftBaselineAndDirtyWithoutReloading() {
        val handle = SavedStateHandle()
        val firstRuntime = ViewModelRuntime(existing = configuration(101))
        val first = viewModel(101, firstRuntime, handle)
        idleMain()
        val edited = draft(
            id = 101,
            times = LocalTime.of(6, 30) to LocalTime.of(20, 45),
            opacity = 35,
            font = 20,
            theme = WidgetThemeMode.LIGHT
        )
        first.updateDraft(edited)

        val recreatedRuntime = ViewModelRuntime(
            existing = configuration(101, opacityPercent = 99, fontSizeSp = 8)
        )
        val recreated = viewModel(101, recreatedRuntime, handle)
        val restored = recreated.state.value as WidgetConfigurationUiState.Content

        assertEquals(WidgetConfigurationDraft.from(configuration(101)), restored.baseline)
        assertEquals(edited, restored.draft)
        assertTrue(restored.dirty)
        assertFalse(restored.saving)
        assertNull(restored.validation)
        assertEquals(0, recreatedRuntime.loadCalls)
        val savedKeys = handle.keys()
        assertTrue(savedKeys.isNotEmpty())
        assertFalse(
            savedKeys.any { key ->
                key.contains("secret", ignoreCase = true) ||
                    key.contains("password", ignoreCase = true) ||
                    key.contains("event", ignoreCase = true) ||
                    key.contains("conversation", ignoreCase = true) ||
                    key.contains("throwable", ignoreCase = true)
            }
        )
    }

    @Test
    fun restoreRejectsSnapshotForAnotherWidgetAndLoadsRequestedInstance() {
        val handle = SavedStateHandle()
        val first = viewModel(111, ViewModelRuntime(existing = configuration(111)), handle)
        idleMain()
        first.updateDraft(draft(111, opacity = 11))
        val runtime = ViewModelRuntime(existing = configuration(112, opacityPercent = 12))

        val second = viewModel(112, runtime, handle)
        idleMain()

        val content = second.state.value as WidgetConfigurationUiState.Content
        assertEquals(112, content.draft.appWidgetId)
        assertEquals(12, content.draft.opacityPercent)
        assertEquals(1, runtime.loadCalls)
    }

    private fun viewModel(
        appWidgetId: Int,
        runtime: ViewModelRuntime,
        handle: SavedStateHandle = SavedStateHandle()
    ): WidgetConfigurationViewModel = WidgetConfigurationViewModel(
        appWidgetId = appWidgetId,
        service = WidgetConfigurationApplicationService(runtime),
        savedStateHandle = handle
    )

    private fun nextEffect(viewModel: WidgetConfigurationViewModel): WidgetConfigurationEffect =
        runBlocking { withTimeout(1_000) { viewModel.effects.first() } }

    private fun assertNoEffect(viewModel: WidgetConfigurationViewModel) {
        val result = runCatching {
            runBlocking { withTimeout(25) { viewModel.effects.first() } }
        }
        assertTrue("Unexpected Widget configuration effect", result.isFailure)
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

private class ViewModelRuntime(
    private val existing: WidgetConfiguration? = null,
    private val fontSizeSp: Int = 13,
    var loadFailure: RuntimeException? = null,
    var saveFailure: RuntimeException? = null,
    var updateFailure: RuntimeException? = null,
    private val saveGate: CompletableDeferred<Unit>? = null
) : WidgetConfigurationRuntimePort {
    var loadCalls = 0
    var saveAttempts = 0
    val saved = mutableListOf<WidgetConfiguration>()
    val updateCalls = mutableListOf<Int>()

    override suspend fun loadConfiguration(appWidgetId: Int): WidgetConfiguration? {
        loadCalls += 1
        loadFailure?.let { throw it }
        return existing
    }

    override suspend fun widgetFontSizeSp(): Int = fontSizeSp

    override suspend fun saveConfiguration(configuration: WidgetConfiguration) {
        saveAttempts += 1
        saveGate?.await()
        saveFailure?.let { throw it }
        saved += configuration
    }

    override suspend fun requestWidgetUpdate(appWidgetId: Int) {
        updateCalls += appWidgetId
        updateFailure?.let { throw it }
    }
}

private fun draft(
    id: Int,
    times: Pair<LocalTime, LocalTime> = LocalTime.of(8, 0) to LocalTime.of(22, 0),
    opacity: Int = 100,
    font: Int = 13,
    theme: WidgetThemeMode = WidgetThemeMode.SYSTEM
): WidgetConfigurationDraft = WidgetConfigurationDraft(
    appWidgetId = id,
    startTime = times.first,
    endTime = times.second,
    opacityPercent = opacity,
    fontSizeSp = font,
    theme = theme
)

private fun configuration(
    appWidgetId: Int,
    times: Pair<LocalTime, LocalTime> = LocalTime.of(8, 0) to LocalTime.of(22, 0),
    opacityPercent: Int = 100,
    fontSizeSp: Int = 13,
    theme: WidgetThemeMode = WidgetThemeMode.SYSTEM
): WidgetConfiguration = WidgetConfiguration(
    appWidgetId = appWidgetId,
    startTime = times.first,
    endTime = times.second,
    opacityPercent = opacityPercent,
    fontSizeSp = fontSizeSp,
    theme = theme
)
