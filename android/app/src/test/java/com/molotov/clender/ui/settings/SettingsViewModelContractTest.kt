package com.molotov.clender.ui.settings

import android.os.Looper
import androidx.lifecycle.ViewModel
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.ui.foundation.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class SettingsViewModelContractTest {
    private val tracked = mutableListOf<SettingsViewModel>()

    @After
    fun clearViewModels() {
        tracked.forEach(::clearViewModel)
        tracked.clear()
        idleMain()
    }

    @Test
    fun constructionIsInactiveAndSensitiveActivationLoadsOnlyOnceWithoutKeyHydration() {
        val port = FakeSettingsPort()
        val viewModel = viewModel(port)

        assertFalse(viewModel.state.value.active)
        assertEquals(0, port.loads)
        viewModel.activate()
        viewModel.activate()
        idleMain()

        assertEquals(1, port.loads)
        assertTrue(viewModel.state.value.active)
        assertTrue(viewModel.state.value.apiKeyConfigured)
        assertTrue(viewModel.state.value.secretInput.isEmpty())
        assertEquals(SettingsStatus.READY, viewModel.state.value.status)
    }

    @Test
    fun showAiSectionIsMemoryOnlyAndDoesNotReloadSensitiveState() {
        val port = FakeSettingsPort()
        val viewModel = viewModel(port)
        viewModel.activate()
        idleMain()

        viewModel.showAiSection()
        viewModel.showAiSection()

        assertEquals(SettingsSection.AI, viewModel.state.value.section)
        assertEquals(1, port.loads)
    }

    @Test
    fun unsavedAppearanceDraftIsDirtyButDoesNotEmitCommittedAppearance() {
        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)
        val committed = viewModel.committedAppearance.value

        viewModel.updateAppearance(
            viewModel.state.value.appearance.copy(themeMode = ThemeMode.DARK, appFontSize = "20")
        )

        assertTrue(viewModel.state.value.dirty)
        assertEquals(committed, viewModel.committedAppearance.value)
        assertEquals(ThemeMode.SYSTEM, committed.themeMode)
    }

    @Test
    fun appearanceSaveSuccessCommitsThemeAndFontWhileFailureKeepsDirty() {
        val successPort = FakeSettingsPort()
        val success = activeViewModel(successPort)
        success.updateAppearance(
            AppearanceSettingsDraft(ThemeMode.DARK, appFontSize = "20", widgetFontSize = "8")
        )
        success.saveAppearance()
        idleMain()

        assertFalse(success.state.value.dirty)
        assertEquals(ThemeMode.DARK, success.committedAppearance.value.themeMode)
        assertEquals(20, success.committedAppearance.value.appFontSizeSp)
        assertEquals(8, success.committedAppearance.value.widgetFontSizeSp)

        val failurePort = FakeSettingsPort(saveDecision = SettingsSaveDecision.SAVE_FAILED)
        val failure = activeViewModel(failurePort)
        failure.updateAppearance(failure.state.value.appearance.copy(appFontSize = "20"))
        failure.saveAppearance()
        idleMain()

        assertTrue(failure.state.value.dirty)
        assertEquals(SettingsStatus.SAVE_FAILED, failure.state.value.status)
        assertEquals(13, failure.committedAppearance.value.appFontSizeSp)
    }

    @Test
    fun invalidDraftDoesNotCallPortAndRapidSaveUsesSingleOperation() {
        val port = FakeSettingsPort(autoCompleteSave = false)
        val viewModel = activeViewModel(port)
        viewModel.updateAppearance(viewModel.state.value.appearance.copy(appFontSize = "7"))
        viewModel.saveAppearance()
        idleMain()
        assertEquals(0, port.appearanceSaves)
        assertEquals(SettingsStatus.VALIDATION_FAILED, viewModel.state.value.status)

        viewModel.updateAppearance(viewModel.state.value.appearance.copy(appFontSize = "20"))
        viewModel.saveAppearance()
        viewModel.saveAppearance()
        idleMain()
        assertEquals(1, port.appearanceSaves)
        port.finishSave(SettingsSaveDecision.SUCCESS)
        idleMain()
    }

    @Test
    fun keyKeepReplaceAndExplicitRemoveConfirmationHaveDistinctMutations() {
        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)

        viewModel.saveAi()
        idleMain()
        assertEquals(ApiKeyMutation.KEEP, port.aiSaves.last().mutation)

        viewModel.updateSecretInput("replacement-key".toCharArray())
        viewModel.saveAi()
        idleMain()
        assertEquals(ApiKeyMutation.REPLACE, port.aiSaves.last().mutation)
        assertTrue(viewModel.state.value.secretInput.isEmpty())

        viewModel.requestApiKeyRemoval()
        assertTrue(viewModel.state.value.removeKeyConfirmation)
        assertEquals(2, port.aiSaves.size)
        viewModel.confirmApiKeyRemoval()
        viewModel.saveAi()
        idleMain()
        assertEquals(ApiKeyMutation.REMOVE, port.aiSaves.last().mutation)
        assertFalse(viewModel.state.value.apiKeyConfigured)
    }

    @Test
    fun cancellingKeyRemovalKeepsConfiguredStateAndDirtySecretInput() {
        val viewModel = activeViewModel(FakeSettingsPort())
        viewModel.updateSecretInput("draft-secret".toCharArray())
        viewModel.requestApiKeyRemoval()
        viewModel.cancelApiKeyRemoval()

        assertFalse(viewModel.state.value.removeKeyConfirmation)
        assertTrue(viewModel.state.value.apiKeyConfigured)
        assertTrue(viewModel.state.value.dirty)
    }

    @Test
    fun modelFetchReportsLoadingSuccessStableDedupEmptyAndFiniteFailure() {
        val port = FakeSettingsPort(
            fetchResults = ArrayDeque(
                listOf(
                    ModelFetchResult(ModelFetchDecision.SUCCESS, listOf("z", "a", "z", "b")),
                    ModelFetchResult(ModelFetchDecision.SUCCESS, emptyList()),
                    ModelFetchResult(ModelFetchDecision.NETWORK)
                )
            )
        )
        val viewModel = activeViewModel(port)

        viewModel.fetchModels()
        assertEquals(SettingsStatus.FETCHING, viewModel.state.value.status)
        idleMain()
        assertEquals(listOf("z", "a", "b"), viewModel.state.value.models)
        assertEquals(SettingsStatus.READY, viewModel.state.value.status)

        viewModel.fetchModels()
        idleMain()
        assertTrue(viewModel.state.value.models.isEmpty())
        assertEquals(SettingsStatus.MODELS_EMPTY, viewModel.state.value.status)

        viewModel.fetchModels()
        idleMain()
        assertEquals(SettingsStatus.NETWORK, viewModel.state.value.status)
    }

    @Test
    fun fetchAndSaveOperationGuardsPreventDuplicateClicks() {
        val port = FakeSettingsPort(autoCompleteFetch = false)
        val viewModel = activeViewModel(port)
        viewModel.fetchModels()
        viewModel.fetchModels()
        viewModel.saveAi()
        idleMain()

        assertEquals(1, port.fetches)
        assertEquals(0, port.aiSaves.size)
        port.finishFetch(ModelFetchResult(ModelFetchDecision.SUCCESS, listOf("only")))
        idleMain()
    }

    @Test
    fun leavingOrClearingCancelsFetchAndWipesSecretWithoutTouchingChat() {
        val port = FakeSettingsPort(autoCompleteFetch = false)
        val viewModel = activeViewModel(port)
        viewModel.updateSecretInput("cancelled-key".toCharArray())
        viewModel.fetchModels()
        idleMain()

        viewModel.leaveSettings()
        idleMain()
        assertEquals(1, port.cancels)
        assertTrue(viewModel.state.value.secretInput.isEmpty())

        viewModel.updateSecretInput("clear-key".toCharArray())
        clearViewModel(viewModel)
        tracked.remove(viewModel)
        idleMain()
        assertEquals(2, port.cancels)
        assertTrue(port.chatCancellationCalls == 0)
    }

    @Test
    fun dirtyNavigationRequiresDiscardAndCancelStaysOnSettings() {
        val viewModel = activeViewModel(FakeSettingsPort())
        var navigations = 0
        viewModel.updateAi(viewModel.state.value.ai.copy(model = "changed"))

        assertFalse(viewModel.requestNavigation { navigations += 1 })
        assertTrue(viewModel.state.value.discardConfirmation)
        viewModel.cancelDiscard()
        assertEquals(0, navigations)
        assertTrue(viewModel.state.value.dirty)

        assertFalse(viewModel.requestNavigation { navigations += 1 })
        viewModel.confirmDiscard()
        assertEquals(1, navigations)
        assertFalse(viewModel.state.value.dirty)
        assertEquals("model-current", viewModel.state.value.ai.model)
        assertTrue(viewModel.state.value.secretInput.isEmpty())
    }

    @Test
    fun backPriorityHandlesRemoveThenDialogThenDrawerThenDirtyBeforeNavigation() {
        val viewModel = activeViewModel(FakeSettingsPort())
        viewModel.updateAi(viewModel.state.value.ai.copy(model = "dirty"))
        viewModel.setDrawerOpen(true)
        viewModel.showDialog(SettingsDialog.OTHER)
        viewModel.requestApiKeyRemoval()

        assertEquals(SettingsBackResult.KEY_REMOVE_CLOSED, viewModel.handleBack())
        assertEquals(SettingsBackResult.DIALOG_CLOSED, viewModel.handleBack())
        assertEquals(SettingsBackResult.DRAWER_CLOSED, viewModel.handleBack())
        assertEquals(SettingsBackResult.DISCARD_REQUIRED, viewModel.handleBack())
    }

    private fun activeViewModel(port: FakeSettingsPort): SettingsViewModel = viewModel(port).also {
        it.activate()
        idleMain()
    }

    private fun viewModel(port: FakeSettingsPort): SettingsViewModel =
        SettingsViewModel(port).also(tracked::add)

    private fun clearViewModel(viewModel: SettingsViewModel) {
        ViewModel::class.java.declaredMethods.single {
            it.name.startsWith("clear") && it.parameterCount == 0
        }.also {
            it.isAccessible = true
        }.invoke(viewModel)
    }

    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private class FakeSettingsPort(
        private val saveDecision: SettingsSaveDecision = SettingsSaveDecision.SUCCESS,
        private val autoCompleteSave: Boolean = true,
        private val autoCompleteFetch: Boolean = true,
        private val fetchResults: ArrayDeque<ModelFetchResult> = ArrayDeque(
            listOf(ModelFetchResult(ModelFetchDecision.SUCCESS, listOf("model-current")))
        )
    ) : SettingsPort {
        var loads = 0
        var appearanceSaves = 0
        val aiSaves = mutableListOf<AiSaveCall>()
        var fetches = 0
        var cancels = 0
        var chatCancellationCalls = 0
        private var pendingSave: CompletableDeferred<SettingsSaveDecision>? = null
        private var pendingFetch: CompletableDeferred<ModelFetchResult>? = null

        override suspend fun load(): PersistedSettings {
            loads += 1
            return persisted()
        }

        override suspend fun saveAppearance(draft: AppearanceSettingsDraft): SettingsSaveDecision {
            appearanceSaves += 1
            if (autoCompleteSave) return saveDecision
            return CompletableDeferred<SettingsSaveDecision>().also { pendingSave = it }.await()
        }

        override suspend fun saveAi(
            draft: AiSettingsDraft,
            mutation: ApiKeyMutation,
            key: CharArray?
        ): SettingsSaveDecision {
            aiSaves += AiSaveCall(draft, mutation, key?.copyOf())
            return saveDecision
        }

        override suspend fun fetchModels(
            draft: AiSettingsDraft,
            mutation: ApiKeyMutation,
            key: CharArray?
        ): ModelFetchResult {
            fetches += 1
            if (autoCompleteFetch) return fetchResults.removeFirst()
            return CompletableDeferred<ModelFetchResult>().also { pendingFetch = it }.await()
        }

        override fun cancelModelFetch() {
            cancels += 1
            pendingFetch?.cancel()
        }

        fun finishSave(value: SettingsSaveDecision) {
            pendingSave?.complete(value)
        }

        fun finishFetch(value: ModelFetchResult) {
            pendingFetch?.complete(value)
        }

        private fun persisted() = PersistedSettings(
            appearance = AppearanceSettingsDraft(ThemeMode.SYSTEM, "13", "13"),
            ai = AiSettingsDraft(
                endpoint = "https://provider.example/v1",
                model = "model-current",
                temperature = "0.7",
                maxOutputTokens = "4096",
                contextWindow = "128000",
                thinkingEnabled = true,
                thinkingEffort = ThinkingEffort.HIGH,
                systemPrompt = "系统 🌏\nline",
                personality = "友好 🙂\nline"
            ),
            apiKeyConfigured = true
        )
    }

    private data class AiSaveCall(
        val draft: AiSettingsDraft,
        val mutation: ApiKeyMutation,
        val key: CharArray?
    )
}
