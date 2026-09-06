package com.molotov.clender.ui.settings

import android.os.Looper
import androidx.lifecycle.ViewModel
import com.molotov.clender.ui.foundation.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * T46-C2b Agent C phase 1: SettingsViewModel WebDAV section contract. The port contract (five new
 * SettingsPort members) and all WebDAV ui models are missing by design; compile errors are the red
 * light evidence and the phase 2 implementation surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WebDavSettingsViewModelContractTest {
    private val tracked = mutableListOf<SettingsViewModel>()

    @After
    fun clearViewModels() {
        tracked.forEach(::clearViewModel)
        tracked.clear()
        idleMain()
    }

    @Test
    fun activateBackfillsWebDavDraftAndPasswordPresenceIdempotently() {
        val port = FakeSettingsPort()
        val viewModel = viewModel(port)

        assertFalse(viewModel.state.value.active)
        viewModel.activate()
        viewModel.activate()
        idleMain()

        assertEquals(1, port.loads)
        assertTrue(viewModel.state.value.active)
        val state = viewModel.state.value
        assertTrue(state.webDav.enabled)
        assertEquals("https://dav.example.com/calendar/", state.webDav.url)
        assertEquals("alice", state.webDav.username)
        assertTrue(state.webDavPasswordConfigured)
        assertTrue(state.webDavSecretInput.isEmpty())
        assertFalse(state.webDavPasswordRemoveConfirmation)
        assertFalse(state.webDavPasswordRemovePending)
        assertNull(state.connectionResult)
        assertNull(state.syncNowResult)
        assertEquals(WebDavSyncStatusUi.Idle, state.webDavSyncStatus)
        assertEquals(SettingsStatus.READY, state.status)
    }

    @Test
    fun webDavDraftEditsBecomeDirtyAndDiscardRestoresPersisted() {
        val viewModel = activeViewModel(FakeSettingsPort())
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(enabled = false))

        assertTrue(viewModel.state.value.dirty)
        assertTrue(!viewModel.state.value.webDav.enabled)
        viewModel.confirmDiscard()
        assertFalse(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.webDav.enabled)
    }

    @Test
    fun saveWebDavUsesKeepWhenNoInputAndReplaceWhenTypedThenWipesEverySecretCopy() {
        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)

        viewModel.saveWebDav()
        idleMain()
        assertEquals(1, port.webDavSaves.size)
        assertEquals(ApiKeyMutation.KEEP, port.webDavSaves.last().mutation)
        assertNull(port.webDavSaves.last().passwordReference)
        assertFalse(viewModel.state.value.dirty)
        assertEquals("https://dav.example.com/calendar/", viewModel.state.value.webDav.url)

        val typed = "replacement-secret".toCharArray()
        viewModel.updateWebDavSecretInput(typed)
        assertTrue(typed.all { it == '\u0000' })
        assertTrue(viewModel.state.value.dirty)

        viewModel.saveWebDav()
        idleMain()
        assertEquals(2, port.webDavSaves.size)
        val replaceCall = port.webDavSaves.last()
        assertEquals(ApiKeyMutation.REPLACE, replaceCall.mutation)
        assertNotNull(replaceCall.passwordCopy)
        assertEquals("replacement-secret", concat(replaceCall.passwordCopy!!))
        assertTrue(replaceCall.passwordReference!!.all { it == '\u0000' })
        assertTrue(viewModel.state.value.webDavSecretInput.isEmpty())
        assertFalse(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.webDavPasswordConfigured)
    }

    @Test
    fun removeWebDavPasswordRequiresConfirmationAndExecutesSingleRemoveSave() {
        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)

        viewModel.requestWebDavPasswordRemoval()
        assertTrue(viewModel.state.value.webDavPasswordRemoveConfirmation)
        assertFalse(viewModel.state.value.dirty)

        viewModel.cancelWebDavPasswordRemoval()
        assertFalse(viewModel.state.value.webDavPasswordRemoveConfirmation)
        assertTrue(viewModel.state.value.webDavPasswordConfigured)
        assertFalse(viewModel.state.value.webDavPasswordRemovePending)

        viewModel.requestWebDavPasswordRemoval()
        viewModel.confirmWebDavPasswordRemoval()
        assertFalse(viewModel.state.value.webDavPasswordRemoveConfirmation)
        assertTrue(viewModel.state.value.webDavPasswordRemovePending)
        assertTrue(viewModel.state.value.webDavSecretInput.isEmpty())
        assertTrue(viewModel.state.value.dirty)

        viewModel.updateWebDav(viewModel.state.value.webDav.copy(enabled = false))
        viewModel.saveWebDav()
        idleMain()
        val call = port.webDavSaves.last()
        assertEquals(ApiKeyMutation.REMOVE, call.mutation)
        assertNull(call.passwordReference)
        assertFalse(viewModel.state.value.webDavPasswordConfigured)
        assertFalse(viewModel.state.value.webDavPasswordRemovePending)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun removalRequestIsIgnoredWithoutConfiguredPresence() {
        val port = FakeSettingsPort(webDavPasswordConfigured = false)
        val viewModel = activeViewModel(port)

        viewModel.requestWebDavPasswordRemoval()

        assertFalse(viewModel.state.value.webDavPasswordRemoveConfirmation)
    }

    @Test
    fun saveWebDavValidationAndFailureKeepDirtyWithoutCallingPort() {
        val failurePort = FakeSettingsPort(saveDecision = WebDavSaveDecision.SAVE_FAILED)
        val failureViewModel = activeViewModel(failurePort)
        failureViewModel.updateWebDav(
            failureViewModel.state.value.webDav.copy(username = "renamed")
        )
        failureViewModel.saveWebDav()
        idleMain()

        assertTrue(failureViewModel.state.value.dirty)
        assertEquals(SettingsStatus.SAVE_FAILED, failureViewModel.state.value.status)

        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(url = "not a url"))
        viewModel.saveWebDav()
        idleMain()

        assertEquals(0, port.webDavSaves.size)
        assertTrue(viewModel.state.value.dirty)
        assertEquals(SettingsStatus.VALIDATION_FAILED, viewModel.state.value.status)
    }

    @Test
    fun testWebDavConnectionStoresResultWithoutClearingDirtyOrPersisted() {
        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(username = "draft-user"))
        assertTrue(viewModel.state.value.dirty)

        viewModel.testWebDavConnection()
        idleMain()

        assertEquals(WebDavConnectionDecision.SUCCESS, viewModel.state.value.connectionResult)
        assertTrue(viewModel.state.value.dirty)
        assertEquals("draft-user", viewModel.state.value.webDav.username)
        assertEquals(SettingsStatus.READY, viewModel.state.value.status)
        assertEquals(1, port.webDavTests.size)
        assertEquals(0, port.webDavSaves.size)
        assertEquals(0, port.webDavSyncs.size)

        viewModel.confirmDiscard()
        assertEquals("alice", viewModel.state.value.webDav.username)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun testWebDavConnectionPassesFiniteFailuresThroughAndValidationSkipsPort() {
        val results = ArrayDeque(
            listOf(
                WebDavConnectionDecision.AUTH,
                WebDavConnectionDecision.TRANSPORT,
                WebDavConnectionDecision.SECRET,
                WebDavConnectionDecision.CANCELLED,
                WebDavConnectionDecision.INTERNAL,
                WebDavConnectionDecision.UNCONFIGURED
            )
        )
        val port = FakeSettingsPort(testResults = results)
        val viewModel = activeViewModel(port)
        listOf(
            WebDavConnectionDecision.AUTH,
            WebDavConnectionDecision.TRANSPORT,
            WebDavConnectionDecision.SECRET,
            WebDavConnectionDecision.CANCELLED,
            WebDavConnectionDecision.INTERNAL,
            WebDavConnectionDecision.UNCONFIGURED
        ).forEach { decision ->
            viewModel.testWebDavConnection()
            idleMain()
            assertEquals(decision, viewModel.state.value.connectionResult)
            assertEquals(SettingsStatus.READY, viewModel.state.value.status)
        }
        assertFalse(viewModel.state.value.dirty)
        assertEquals(6, port.webDavTests.size)

        val validationPort = FakeSettingsPort()
        val validationViewModel = activeViewModel(validationPort)
        validationViewModel.updateWebDav(validationViewModel.state.value.webDav.copy(url = ""))
        validationViewModel.testWebDavConnection()
        idleMain()

        assertEquals(0, validationPort.webDavTests.size)
        assertEquals(
            WebDavConnectionDecision.VALIDATION_FAILED,
            validationViewModel.state.value.connectionResult
        )
        assertEquals(SettingsStatus.VALIDATION_FAILED, validationViewModel.state.value.status)
    }

    @Test
    fun syncWebDavNowStartedAndCoalescedTreatAsSaveAndClearDirty() {
        val startedPort = FakeSettingsPort(
            syncResults = ArrayDeque(listOf(WebDavSyncNowDecision.SYNC_STARTED))
        )
        val started = activeViewModel(startedPort)
        started.updateWebDav(started.state.value.webDav.copy(username = "bob"))
        started.syncWebDavNow()
        idleMain()

        assertFalse(started.state.value.dirty)
        assertEquals(WebDavSyncNowDecision.SYNC_STARTED, started.state.value.syncNowResult)
        assertEquals("bob", started.state.value.webDav.username)
        assertEquals(SettingsStatus.READY, started.state.value.status)
        assertEquals(1, startedPort.webDavSyncs.size)

        val coalescedPort = FakeSettingsPort(
            syncResults = ArrayDeque(listOf(WebDavSyncNowDecision.SYNC_COALESCED))
        )
        val coalesced = activeViewModel(coalescedPort)
        coalesced.updateWebDav(coalesced.state.value.webDav.copy(username = "carol"))
        coalesced.syncWebDavNow()
        idleMain()

        assertFalse(coalesced.state.value.dirty)
        assertEquals(WebDavSyncNowDecision.SYNC_COALESCED, coalesced.state.value.syncNowResult)
    }

    @Test
    fun syncWebDavNowDisabledAndUnconfiguredAlsoClearDirty() {
        listOf(
            WebDavSyncNowDecision.DISABLED,
            WebDavSyncNowDecision.UNCONFIGURED
        ).forEach { decision ->
            val port = FakeSettingsPort(syncResults = ArrayDeque(listOf(decision)))
            val viewModel = activeViewModel(port)
            viewModel.updateWebDav(
                viewModel.state.value.webDav.copy(username = "draft-${decision.name}")
            )
            viewModel.syncWebDavNow()
            idleMain()

            assertFalse("$decision must clear dirty", viewModel.state.value.dirty)
            assertEquals(decision, viewModel.state.value.syncNowResult)
        }
    }

    @Test
    fun syncWebDavNowFailuresKeepDirtyAndValidationSkipsPort() {
        listOf(
            WebDavSyncNowDecision.SAVE_FAILED,
            WebDavSyncNowDecision.INTERNAL
        ).forEach { decision ->
            val port = FakeSettingsPort(syncResults = ArrayDeque(listOf(decision)))
            val viewModel = activeViewModel(port)
            viewModel.updateWebDav(
                viewModel.state.value.webDav.copy(username = "draft-${decision.name}")
            )
            viewModel.syncWebDavNow()
            idleMain()

            assertTrue("$decision must keep dirty", viewModel.state.value.dirty)
            assertEquals(decision, viewModel.state.value.syncNowResult)
        }

        val validationPort = FakeSettingsPort()
        val validationViewModel = activeViewModel(validationPort)
        validationViewModel.updateWebDav(
            validationViewModel.state.value.webDav.copy(url = "bad url")
        )
        validationViewModel.syncWebDavNow()
        idleMain()

        assertEquals(0, validationPort.webDavSyncs.size)
        assertEquals(
            WebDavSyncNowDecision.VALIDATION_FAILED,
            validationViewModel.state.value.syncNowResult
        )
        assertTrue(validationViewModel.state.value.dirty)
        assertEquals(SettingsStatus.VALIDATION_FAILED, validationViewModel.state.value.status)
    }

    @Test
    fun webDavOperationsShareTheSingleOperationGuardWithC2aActions() {
        val port = FakeSettingsPort(autoCompleteTest = false)
        val viewModel = activeViewModel(port)

        viewModel.testWebDavConnection()
        viewModel.saveWebDav()
        viewModel.syncWebDavNow()
        viewModel.saveAppearance()
        viewModel.saveAi()
        viewModel.fetchModels()
        idleMain()

        assertEquals(1, port.webDavTests.size)
        assertEquals(0, port.webDavSaves.size)
        assertEquals(0, port.webDavSyncs.size)
        assertEquals(0, port.appearanceSaves)
        assertEquals(0, port.aiSaves.size)
        assertEquals(0, port.fetches)

        port.finishTest(WebDavConnectionDecision.SUCCESS)
        idleMain()
        assertEquals(WebDavConnectionDecision.SUCCESS, viewModel.state.value.connectionResult)

        viewModel.saveWebDav()
        idleMain()
        assertEquals(1, port.webDavSaves.size)
    }

    @Test
    fun doubleClickSaveWebDavCallsPortExactlyOnce() {
        val port = FakeSettingsPort(autoCompleteSave = false)
        val viewModel = activeViewModel(port)

        viewModel.saveWebDav()
        viewModel.saveWebDav()
        idleMain()
        assertEquals(1, port.webDavSaves.size)

        port.finishSave(WebDavSaveDecision.SUCCESS)
        idleMain()
        assertFalse(viewModel.state.value.dirty)

        viewModel.saveWebDav()
        idleMain()
        assertEquals(2, port.webDavSaves.size)
    }

    @Test
    fun leavingSettingsCancelsProbeWipesSecretRestoresDraftAndClearsConfirmation() {
        val port = FakeSettingsPort(autoCompleteTest = false)
        val viewModel = activeViewModel(port)
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(enabled = false))
        viewModel.updateWebDavSecretInput("leave-probe".toCharArray())
        viewModel.requestWebDavPasswordRemoval()
        assertTrue(viewModel.state.value.webDavPasswordRemoveConfirmation)
        viewModel.testWebDavConnection()
        idleMain()
        val inFlight = port.webDavTests.last()
        assertNotNull(inFlight.passwordReference)

        viewModel.leaveSettings()
        idleMain()

        assertEquals(1, port.webDavProbeCancels)
        assertTrue(viewModel.state.value.webDavSecretInput.isEmpty())
        assertFalse(viewModel.state.value.webDavPasswordRemoveConfirmation)
        assertFalse(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.webDav.enabled)
        assertEquals("alice", viewModel.state.value.webDav.username)
        assertTrue(inFlight.passwordReference!!.all { it == '\u0000' })

        viewModel.updateWebDavSecretInput("clear-probe".toCharArray())
        viewModel.testWebDavConnection()
        idleMain()
        clearViewModel(viewModel)
        tracked.remove(viewModel)
        idleMain()
        assertEquals(2, port.webDavProbeCancels)
        assertTrue(port.webDavTests.last().passwordReference!!.all { it == '\u0000' })
    }

    @Test
    fun syncStateFlowDrivesUiStateMonotonically() {
        val port = FakeSettingsPort()
        val viewModel = activeViewModel(port)

        port.emitSync(WebDavSyncStatusUi.Running(pending = false))
        idleMain()
        assertEquals(WebDavSyncStatusUi.Running(false), viewModel.state.value.webDavSyncStatus)

        port.emitSync(WebDavSyncStatusUi.Running(pending = true))
        idleMain()
        assertEquals(WebDavSyncStatusUi.Running(true), viewModel.state.value.webDavSyncStatus)

        port.emitSync(
            WebDavSyncStatusUi.Success(uploaded = true, localChanged = false, eventCount = 3)
        )
        idleMain()
        assertEquals(
            WebDavSyncStatusUi.Success(uploaded = true, localChanged = false, eventCount = 3),
            viewModel.state.value.webDavSyncStatus
        )

        port.emitSync(WebDavSyncStatusUi.Failed(WebDavSyncFailureCodeUi.AUTH))
        idleMain()
        assertEquals(
            WebDavSyncStatusUi.Failed(WebDavSyncFailureCodeUi.AUTH),
            viewModel.state.value.webDavSyncStatus
        )
    }

    @Test
    fun handleBackClosesWebDavRemoveConfirmationBeforeOtherLayers() {
        val viewModel = activeViewModel(FakeSettingsPort())
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(enabled = false))
        viewModel.setDrawerOpen(true)
        viewModel.showDialog(SettingsDialog.OTHER)
        viewModel.requestWebDavPasswordRemoval()

        val first = viewModel.handleBack()
        assertNotEquals(SettingsBackResult.NAVIGATE, first)
        assertFalse(viewModel.state.value.webDavPasswordRemoveConfirmation)
        assertEquals(SettingsBackResult.DIALOG_CLOSED, viewModel.handleBack())
        assertEquals(SettingsBackResult.DRAWER_CLOSED, viewModel.handleBack())
        assertEquals(SettingsBackResult.DISCARD_REQUIRED, viewModel.handleBack())
        assertTrue(viewModel.state.value.dirty)
    }

    @Test
    fun dirtyWebDavDiscardClearsEverySecretStateAndConfirmRestoresPersisted() {
        val viewModel = activeViewModel(FakeSettingsPort())
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(enabled = false))
        viewModel.updateWebDavSecretInput("wipe-me".toCharArray())
        viewModel.requestWebDavPasswordRemoval()
        viewModel.confirmWebDavPasswordRemoval()
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.webDavPasswordRemovePending)

        viewModel.confirmDiscard()
        assertFalse(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.webDav.enabled)
        assertTrue(viewModel.state.value.webDavSecretInput.isEmpty())
        assertFalse(viewModel.state.value.webDavPasswordRemovePending)
    }

    @Test
    fun dirtyWebDavNavigationRequiresDiscardAndConfirmNavigates() {
        val viewModel = activeViewModel(FakeSettingsPort())
        var navigations = 0
        viewModel.updateWebDav(viewModel.state.value.webDav.copy(enabled = false))

        assertFalse(viewModel.requestNavigation { navigations += 1 })
        assertTrue(viewModel.state.value.discardConfirmation)
        assertTrue(viewModel.state.value.dirty)
        viewModel.cancelDiscard()
        assertEquals(0, navigations)
        assertFalse(viewModel.state.value.discardConfirmation)
        assertTrue(viewModel.state.value.dirty)

        assertFalse(viewModel.requestNavigation { navigations += 1 })
        viewModel.confirmDiscard()
        assertEquals(1, navigations)
        assertFalse(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.webDav.enabled)
    }

    @Test
    fun stateToStringNeverContainsWebDavPassword() {
        val viewModel = activeViewModel(FakeSettingsPort())
        viewModel.updateWebDavSecretInput("vm-print-sentinel".toCharArray())

        assertTrue(viewModel.state.value.dirty)
        assertFalse(viewModel.state.value.toString().contains("vm-print-sentinel"))
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

    private fun concat(chars: CharArray): String = buildString(chars.size) {
        chars.forEach(::append)
    }

    @Suppress("LongParameterList")
    private class FakeSettingsPort(
        private val persistedWebDav: WebDavSettingsDraft = WebDavSettingsDraft(
            enabled = true,
            url = "https://dav.example.com/calendar/",
            username = "alice"
        ),
        private val webDavPasswordConfigured: Boolean = true,
        private val saveDecision: WebDavSaveDecision = WebDavSaveDecision.SUCCESS,
        private val autoCompleteSave: Boolean = true,
        private val autoCompleteTest: Boolean = true,
        private val autoCompleteSync: Boolean = true,
        private val testResults: ArrayDeque<WebDavConnectionDecision> = ArrayDeque(
            listOf(WebDavConnectionDecision.SUCCESS)
        ),
        private val syncResults: ArrayDeque<WebDavSyncNowDecision> = ArrayDeque(
            listOf(WebDavSyncNowDecision.SYNC_STARTED)
        )
    ) : SettingsPort {
        var loads = 0
        var appearanceSaves = 0
        var fetches = 0
        val aiSaves = mutableListOf<AiSaveCall>()
        val webDavSaves = mutableListOf<WebDavSaveCall>()
        val webDavTests = mutableListOf<WebDavTestCall>()
        val webDavSyncs = mutableListOf<WebDavSyncCall>()
        var webDavProbeCancels = 0
        private val syncState = MutableStateFlow<WebDavSyncStatusUi>(WebDavSyncStatusUi.Idle)
        private var pendingSave: CompletableDeferred<WebDavSaveDecision>? = null
        private var pendingTest: CompletableDeferred<WebDavConnectionDecision>? = null
        private var pendingSync: CompletableDeferred<WebDavSyncNowDecision>? = null

        override val webDavSyncState: Flow<WebDavSyncStatusUi> = syncState

        override suspend fun load(): PersistedSettings {
            loads += 1
            return PersistedSettings(
                appearance = AppearanceSettingsDraft(ThemeMode.SYSTEM, "13", "13"),
                ai = AiSettingsDraft(
                    endpoint = "https://provider.example/v1",
                    model = "model-current"
                ),
                apiKeyConfigured = true,
                webDav = persistedWebDav,
                webDavPasswordConfigured = webDavPasswordConfigured
            )
        }

        override suspend fun saveAppearance(draft: AppearanceSettingsDraft): SettingsSaveDecision {
            appearanceSaves += 1
            return SettingsSaveDecision.SUCCESS
        }

        override suspend fun saveAi(
            draft: AiSettingsDraft,
            mutation: ApiKeyMutation,
            key: CharArray?
        ): SettingsSaveDecision {
            aiSaves += AiSaveCall(draft, mutation)
            return SettingsSaveDecision.SUCCESS
        }

        override suspend fun fetchModels(
            draft: AiSettingsDraft,
            mutation: ApiKeyMutation,
            key: CharArray?
        ): ModelFetchResult {
            fetches += 1
            return ModelFetchResult(ModelFetchDecision.SUCCESS, listOf("model-current"))
        }

        override fun cancelModelFetch() = Unit

        override suspend fun saveWebDav(
            draft: WebDavSettingsDraft,
            mutation: ApiKeyMutation,
            password: CharArray?
        ): WebDavSaveDecision {
            webDavSaves += WebDavSaveCall(draft, mutation, password)
            if (autoCompleteSave) return saveDecision
            return CompletableDeferred<WebDavSaveDecision>().also { pendingSave = it }.await()
        }

        override suspend fun testWebDavConnection(
            draft: WebDavSettingsDraft,
            password: CharArray?,
            removePasswordPending: Boolean
        ): WebDavConnectionDecision {
            webDavTests += WebDavTestCall(draft, password, removePasswordPending)
            if (autoCompleteTest) return testResults.removeFirst()
            return CompletableDeferred<WebDavConnectionDecision>()
                .also { pendingTest = it }
                .await()
        }

        override suspend fun syncWebDavNow(
            draft: WebDavSettingsDraft,
            password: CharArray?,
            removePasswordPending: Boolean
        ): WebDavSyncNowDecision {
            webDavSyncs += WebDavSyncCall(draft, password, removePasswordPending)
            if (autoCompleteSync) return syncResults.removeFirst()
            return CompletableDeferred<WebDavSyncNowDecision>()
                .also { pendingSync = it }
                .await()
        }

        override fun cancelWebDavProbe() {
            webDavProbeCancels += 1
            pendingTest?.cancel()
        }

        fun emitSync(status: WebDavSyncStatusUi) {
            syncState.value = status
        }

        fun finishSave(value: WebDavSaveDecision) {
            pendingSave?.complete(value)
        }

        fun finishTest(value: WebDavConnectionDecision) {
            pendingTest?.complete(value)
        }

        fun finishSync(value: WebDavSyncNowDecision) {
            pendingSync?.complete(value)
        }
    }

    private class AiSaveCall(val draft: AiSettingsDraft, val mutation: ApiKeyMutation)

    private class WebDavSaveCall(
        val draft: WebDavSettingsDraft,
        val mutation: ApiKeyMutation,
        password: CharArray?
    ) {
        val passwordReference: CharArray? = password
        val passwordCopy: CharArray? = password?.copyOf()
    }

    private class WebDavTestCall(
        val draft: WebDavSettingsDraft,
        password: CharArray?,
        val removePasswordPending: Boolean
    ) {
        val passwordReference: CharArray? = password
    }

    private class WebDavSyncCall(
        val draft: WebDavSettingsDraft,
        password: CharArray?,
        val removePasswordPending: Boolean
    ) {
        val passwordReference: CharArray? = password
    }
}
