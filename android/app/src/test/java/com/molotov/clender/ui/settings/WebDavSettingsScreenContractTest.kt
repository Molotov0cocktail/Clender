package com.molotov.clender.ui.settings

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.molotov.clender.R
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T46-C2b Agent C phase 1: SettingsScreen WebDAV section contract. Pure Compose tests only use the
 * test-only RobolectricComposeHost; none start the production MainActivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebDavSettingsScreenContractTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startHost() = composeHost.start()

    @After
    fun closeHost() = composeHost.close()

    @Test
    fun thirdTabOrderingAndDefaultSectionShowWebDavOnlyWhenSelected() {
        setScreen(readyState())
        target("settings_section_application").assertIsSelected()
        target("settings_section_ai")
        target("settings_section_webdav")
        composeRule.onNodeWithTag("settings_webdav_enabled").assertDoesNotExist()

        var sections = mutableListOf<SettingsSection>()
        setScreen(readyState(), actions = actions(onSectionChange = { sections += it }))
        composeRule.onNodeWithTag("settings_section_webdav").performClick()
        composeRule.runOnIdle { assertEquals(SettingsSection.WEBDAV, sections.last()) }

        setScreen(readyState().copy(section = SettingsSection.WEBDAV))
        target("settings_section_webdav").assertIsSelected()
        listOf(
            "settings_webdav_enabled",
            "settings_webdav_url",
            "settings_webdav_username",
            "settings_webdav_password"
        ).forEach(::field)
        listOf(
            "settings_webdav_save",
            "settings_webdav_test_connection",
            "settings_webdav_sync_now"
        ).forEach(::pageTarget)
    }

    @Test
    fun switchToggleReportsWebDavDraftEnabledChange() {
        val received = mutableListOf<WebDavSettingsDraft>()
        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV),
            actions = actions(onUpdateWebDav = { received += it })
        )

        composeRule.onNodeWithTag("settings_webdav_enabled").performClick()
        composeRule.runOnIdle {
            assertEquals(1, received.size)
            assertTrue(received.last().enabled)
        }
    }

    @Test
    fun passwordFieldIsMaskedAndSemanticsNeverContainTypedPassword() {
        val sentinel = "webdav-semantic-secret-sentinel"
        var received = CharArray(0)
        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV),
            actions = actions(onWebDavSecretInput = { received = it.copyOf() })
        )

        composeRule.onNodeWithTag("settings_webdav_password").performTextInput(sentinel)
        composeRule.runOnIdle { assertEquals(sentinel, concat(received)) }
        val semantics = composeRule.onNodeWithTag("settings_webdav_password")
            .fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in semantics)
        assertFalse(semantics.toString().contains(sentinel))
        composeRule.onNodeWithText(sentinel).assertDoesNotExist()
        received.fill('\u0000')
    }

    @Test
    fun configuredPasswordShowsConfiguredLabelRemoveButtonAndNeverHydratesInput() {
        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV, webDavPasswordConfigured = true)
        )
        composeRule.onNodeWithText(textOf(R.string.settings_webdav_password_configured))
            .performScrollTo().assertIsDisplayed()
        pageTarget("settings_webdav_remove_password")
        assertFalse(
            composeRule.onNodeWithTag("settings_webdav_password")
                .fetchSemanticsNode().config.toString().contains("configured-password-value")
        )

        setScreen(readyState().copy(section = SettingsSection.WEBDAV))
        composeRule.onNodeWithText(textOf(R.string.settings_webdav_password_configured))
            .assertDoesNotExist()
        composeRule.onNodeWithTag("settings_webdav_remove_password").assertDoesNotExist()
    }

    @Test
    fun removePasswordDialogConfirmsAndCancels() {
        var confirms = 0
        var cancels = 0
        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavPasswordConfigured = true,
                webDavPasswordRemoveConfirmation = true
            ),
            actions = actions(
                onConfirmRemoveWebDavPassword = { confirms += 1 },
                onCancelRemoveWebDavPassword = { cancels += 1 }
            )
        )

        composeRule.onNodeWithText(textOf(R.string.settings_webdav_remove_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(textOf(R.string.settings_webdav_remove_message))
            .assertIsDisplayed()
        target("settings_confirm_remove_webdav_password").performClick()
        composeRule.runOnIdle { assertEquals(1, confirms) }
        target("settings_cancel_remove_webdav_password").performClick()
        composeRule.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun connectionResultRendersFiniteLocalizedTextOnlyAndNullIsAbsent() {
        listOf(
            WebDavConnectionDecision.SUCCESS to R.string.settings_webdav_connection_success,
            WebDavConnectionDecision.BUSY to R.string.settings_webdav_connection_busy,
            WebDavConnectionDecision.UNCONFIGURED to
                R.string.settings_webdav_connection_unconfigured,
            WebDavConnectionDecision.VALIDATION_FAILED to
                R.string.settings_webdav_connection_validation_failed,
            WebDavConnectionDecision.AUTH to R.string.settings_webdav_connection_auth,
            WebDavConnectionDecision.DOCUMENT to R.string.settings_webdav_connection_document,
            WebDavConnectionDecision.TRANSPORT to R.string.settings_webdav_connection_transport,
            WebDavConnectionDecision.SECRET to R.string.settings_webdav_connection_secret,
            WebDavConnectionDecision.CANCELLED to R.string.settings_webdav_connection_cancelled,
            WebDavConnectionDecision.INTERNAL to R.string.settings_webdav_connection_internal
        ).forEach { (decision, resource) ->
            setScreen(
                readyState().copy(section = SettingsSection.WEBDAV, connectionResult = decision)
            )
            assertStatusText(
                "settings_webdav_connection_status",
                textOf(resource)
            )
        }

        setScreen(readyState().copy(section = SettingsSection.WEBDAV, connectionResult = null))
        composeRule.onNodeWithTag("settings_webdav_connection_status").assertDoesNotExist()
    }

    @Test
    @Suppress("LongMethod")
    fun syncStatusRendersRunningFailureSuccessAndDisabledFiniteTexts() {
        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Running(pending = false)
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_running)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Running(pending = true)
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_running_pending)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Failed(WebDavSyncFailureCodeUi.AUTH)
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_failed_auth)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Success(
                    uploaded = true,
                    localChanged = false,
                    eventCount = 3
                )
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_success_uploaded, 3)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Success(
                    uploaded = false,
                    localChanged = true,
                    eventCount = 7
                )
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_success_noop, 7)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Disabled
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_disabled)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Unconfigured
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_unconfigured)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                webDavSyncStatus = WebDavSyncStatusUi.Idle
            )
        )
        assertStatusText("settings_webdav_sync_status", textOf(R.string.settings_webdav_sync_idle))
    }

    @Test
    @Suppress("LongMethod")
    fun syncNowResultRendersStartedQueuedAndFailureTextsWithPrecedence() {
        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                syncNowResult = WebDavSyncNowDecision.SYNC_STARTED,
                webDavSyncStatus = WebDavSyncStatusUi.Running(pending = false)
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_started)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                syncNowResult = WebDavSyncNowDecision.SYNC_COALESCED,
                webDavSyncStatus = WebDavSyncStatusUi.Idle
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_queued)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                syncNowResult = WebDavSyncNowDecision.SAVE_FAILED
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_save_failed)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                syncNowResult = WebDavSyncNowDecision.VALIDATION_FAILED
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_validation_failed)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                syncNowResult = WebDavSyncNowDecision.DISABLED
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_disabled)
        )

        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                syncNowResult = WebDavSyncNowDecision.UNCONFIGURED
            )
        )
        assertStatusText(
            "settings_webdav_sync_status",
            textOf(R.string.settings_webdav_sync_unconfigured)
        )
    }

    @Test
    fun savingAndTestingDisableAllWebDavAndExistingSectionOperations() {
        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV, status = SettingsStatus.SAVING)
        )
        listOf(
            "settings_webdav_save",
            "settings_webdav_test_connection",
            "settings_webdav_sync_now"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        }
        composeRule.onNodeWithTag("settings_webdav_password").assertIsNotEnabled()

        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV, status = SettingsStatus.TESTING)
        )
        listOf(
            "settings_webdav_save",
            "settings_webdav_test_connection",
            "settings_webdav_sync_now"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        }

        setScreen(readyState().copy(section = SettingsSection.AI, status = SettingsStatus.TESTING))
        composeRule.onNodeWithTag("settings_save_ai").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun discardDialogRenderableForDirtyWebDav() {
        setScreen(
            readyState().copy(
                section = SettingsSection.WEBDAV,
                dirty = true,
                discardConfirmation = true
            )
        )
        target("settings_discard_confirm")
        target("settings_discard_cancel")
    }

    @Test
    fun syncStateFlowDrivesStatusTextThroughViewModelWiring() {
        val port = ScreenFakeSettingsPort()
        val viewModel = SettingsViewModel(port)
        try {
            viewModel.activate()
            idleMain()
            composeRule.runOnUiThread {
                composeHost.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, 1f)
                    ) {
                        ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                            val state by viewModel.state.collectAsState()
                            SettingsScreen(state = state, actions = actions(viewModel))
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            viewModel.selectSection(SettingsSection.WEBDAV)
            composeRule.waitForIdle()

            port.emitSync(WebDavSyncStatusUi.Running(pending = true))
            composeRule.waitForIdle()
            assertStatusText(
                "settings_webdav_sync_status",
                textOf(R.string.settings_webdav_sync_running_pending)
            )

            port.emitSync(WebDavSyncStatusUi.Failed(WebDavSyncFailureCodeUi.AUTH))
            composeRule.waitForIdle()
            assertStatusText(
                "settings_webdav_sync_status",
                textOf(R.string.settings_webdav_sync_failed_auth)
            )
        } finally {
            clearViewModel(viewModel)
            idleMain()
        }
    }

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-420dpi")
    @Test
    fun compactChineseDarkTwentySpAtDoubleFontScaleIsScrollableAndReachable() {
        assertResponsive(360, ThemeMode.DARK, 20, 2f)
    }

    @Config(qualifiers = "en-rUS-w599dp-h700dp-420dpi")
    @Test
    fun compactBoundary599EnglishLightEightSpIsScrollableAndReachable() {
        assertResponsive(599, ThemeMode.LIGHT, 8, 1f)
    }

    @Config(qualifiers = "zh-rCN-w600dp-h800dp-420dpi")
    @Test
    fun mediumBoundary600ChineseSystemTwentySpIsScrollableAndReachable() {
        assertResponsive(600, ThemeMode.SYSTEM, 20, 1f)
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    @Test
    fun expanded840EnglishDarkEightSpAtDoubleFontScaleIsScrollableAndReachable() {
        assertResponsive(840, ThemeMode.DARK, 8, 2f)
    }

    private fun assertResponsive(width: Int, theme: ThemeMode, fontSp: Int, fontScale: Float) {
        setScreen(
            readyState().copy(section = SettingsSection.WEBDAV),
            RenderOptions(width, theme, fontSp, fontScale)
        )
        composeRule.onNode(hasScrollAction()).assertIsDisplayed()
        pageTarget("settings_webdav_save")
        pageTarget("settings_webdav_test_connection")
        pageTarget("settings_webdav_sync_now")
        target("settings_section_application")
        target("settings_section_ai")
        target("settings_section_webdav")
    }

    private fun target(tag: String) = composeRule.onNodeWithTag(tag)
        .assertIsDisplayed()
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)

    private fun pageTarget(tag: String) = composeRule.onNodeWithTag(tag)
        .performScrollTo()
        .assertIsDisplayed()
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)

    private fun field(tag: String) = composeRule.onNodeWithTag(tag)
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)

    private fun assertStatusText(tag: String, expected: String) {
        val actual = composeRule.onNodeWithTag(tag)
            .assertIsDisplayed()
            .fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString("\n") { it.text }
            .orEmpty()
        assertEquals(expected, actual)
    }

    private fun setScreen(
        state: SettingsUiState,
        options: RenderOptions = RenderOptions(),
        actions: SettingsActions = actions()
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(options.theme, options.fontSp, state.widgetFontSizeSp)
                    ) {
                        SettingsScreen(
                            state = state,
                            actions = actions,
                            modifier = Modifier.width(options.widthDp.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun textOf(@StringRes resource: Int, vararg args: Any): String =
        composeHost.activity.getString(resource, *args)

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

    private data class RenderOptions(
        val widthDp: Int = 360,
        val theme: ThemeMode = ThemeMode.LIGHT,
        val fontSp: Int = 13,
        val fontScale: Float = 1f
    )

    private companion object {
        fun readyState() = SettingsUiState(
            active = true,
            status = SettingsStatus.READY,
            apiKeyConfigured = true
        )

        @Suppress("LongParameterList")
        fun actions(
            onSectionChange: (SettingsSection) -> Unit = {},
            onAppearanceChange: (AppearanceSettingsDraft) -> Unit = {},
            onAiChange: (AiSettingsDraft) -> Unit = {},
            onSecretInput: (CharArray) -> Unit = {},
            onSaveAppearance: () -> Unit = {},
            onSaveAi: () -> Unit = {},
            onFetchModels: () -> Unit = {},
            onRequestRemoveKey: () -> Unit = {},
            onConfirmRemoveKey: () -> Unit = {},
            onCancelRemoveKey: () -> Unit = {},
            onConfirmDiscard: () -> Unit = {},
            onCancelDiscard: () -> Unit = {},
            onUpdateWebDav: (WebDavSettingsDraft) -> Unit = {},
            onWebDavSecretInput: (CharArray) -> Unit = {},
            onSaveWebDav: () -> Unit = {},
            onTestWebDavConnection: () -> Unit = {},
            onSyncWebDavNow: () -> Unit = {},
            onRequestRemoveWebDavPassword: () -> Unit = {},
            onConfirmRemoveWebDavPassword: () -> Unit = {},
            onCancelRemoveWebDavPassword: () -> Unit = {}
        ) = SettingsActions(
            onSectionChange = onSectionChange,
            onAppearanceChange = onAppearanceChange,
            onAiChange = onAiChange,
            onSecretInput = onSecretInput,
            onSaveAppearance = onSaveAppearance,
            onSaveAi = onSaveAi,
            onFetchModels = onFetchModels,
            onRequestRemoveKey = onRequestRemoveKey,
            onConfirmRemoveKey = onConfirmRemoveKey,
            onCancelRemoveKey = onCancelRemoveKey,
            onConfirmDiscard = onConfirmDiscard,
            onCancelDiscard = onCancelDiscard,
            onUpdateWebDav = onUpdateWebDav,
            onWebDavSecretInput = onWebDavSecretInput,
            onSaveWebDav = onSaveWebDav,
            onTestWebDavConnection = onTestWebDavConnection,
            onSyncWebDavNow = onSyncWebDavNow,
            onRequestRemoveWebDavPassword = onRequestRemoveWebDavPassword,
            onConfirmRemoveWebDavPassword = onConfirmRemoveWebDavPassword,
            onCancelRemoveWebDavPassword = onCancelRemoveWebDavPassword
        )

        fun actions(viewModel: SettingsViewModel) = SettingsActions(
            onSectionChange = viewModel.selectSection,
            onAppearanceChange = viewModel.updateAppearance,
            onAiChange = viewModel.updateAi,
            onSecretInput = viewModel.updateSecretInput,
            onSaveAppearance = viewModel.saveAppearance,
            onSaveAi = viewModel.saveAi,
            onFetchModels = viewModel.fetchModels,
            onRequestRemoveKey = viewModel.requestApiKeyRemoval,
            onConfirmRemoveKey = viewModel.confirmApiKeyRemoval,
            onCancelRemoveKey = viewModel.cancelApiKeyRemoval,
            onConfirmDiscard = viewModel.confirmDiscard,
            onCancelDiscard = viewModel.cancelDiscard,
            onUpdateWebDav = viewModel.updateWebDav,
            onWebDavSecretInput = viewModel.updateWebDavSecretInput,
            onSaveWebDav = viewModel.saveWebDav,
            onTestWebDavConnection = viewModel.testWebDavConnection,
            onSyncWebDavNow = viewModel.syncWebDavNow,
            onRequestRemoveWebDavPassword = viewModel.requestWebDavPasswordRemoval,
            onConfirmRemoveWebDavPassword = viewModel.confirmWebDavPasswordRemoval,
            onCancelRemoveWebDavPassword = viewModel.cancelWebDavPasswordRemoval
        )
    }

    private class ScreenFakeSettingsPort : SettingsPort {
        private val syncState = MutableStateFlow<WebDavSyncStatusUi>(WebDavSyncStatusUi.Idle)

        override val webDavSyncState: Flow<WebDavSyncStatusUi> = syncState

        override suspend fun load(): PersistedSettings = PersistedSettings(
            appearance = AppearanceSettingsDraft(ThemeMode.SYSTEM, "13", "13"),
            ai = AiSettingsDraft(
                endpoint = "https://provider.example/v1",
                model = "model-current"
            ),
            apiKeyConfigured = true,
            webDav = WebDavSettingsDraft(
                enabled = true,
                url = "https://dav.example.com/calendar/",
                username = "alice"
            ),
            webDavPasswordConfigured = true
        )

        override suspend fun saveAppearance(draft: AppearanceSettingsDraft): SettingsSaveDecision =
            SettingsSaveDecision.SUCCESS

        override suspend fun saveAi(
            draft: AiSettingsDraft,
            mutation: ApiKeyMutation,
            key: CharArray?
        ): SettingsSaveDecision = SettingsSaveDecision.SUCCESS

        override suspend fun fetchModels(
            draft: AiSettingsDraft,
            mutation: ApiKeyMutation,
            key: CharArray?
        ): ModelFetchResult = ModelFetchResult(ModelFetchDecision.SUCCESS, emptyList())

        override fun cancelModelFetch() = Unit

        override suspend fun saveWebDav(
            draft: WebDavSettingsDraft,
            mutation: ApiKeyMutation,
            password: CharArray?
        ): WebDavSaveDecision = WebDavSaveDecision.SUCCESS

        override suspend fun testWebDavConnection(
            draft: WebDavSettingsDraft,
            password: CharArray?,
            removePasswordPending: Boolean
        ): WebDavConnectionDecision = WebDavConnectionDecision.SUCCESS

        override suspend fun syncWebDavNow(
            draft: WebDavSettingsDraft,
            password: CharArray?,
            removePasswordPending: Boolean
        ): WebDavSyncNowDecision = WebDavSyncNowDecision.SYNC_STARTED

        override fun cancelWebDavProbe() = Unit

        fun emitSync(status: WebDavSyncStatusUi) {
            syncState.value = status
        }
    }
}
