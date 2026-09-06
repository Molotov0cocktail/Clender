package com.molotov.clender.ui.theme

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.molotov.clender.data.settings.BackgroundStore
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.settings.BackgroundSettingsSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundSettingsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val temporary = TemporaryFolder()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun lightCompactControlsHaveAccessibleSelectionEntry() = show(ThemeMode.LIGHT, 8)

    @Test
    fun darkLargeTypographyKeepsSelectionEntryVisible() = show(ThemeMode.DARK, 20)

    @Test
    fun loadingShowsStatusAndDisablesSelection() {
        val dispatcher = StandardTestDispatcher()
        val job = SupervisorJob()
        val controller = BackgroundController(
            BackgroundStore(temporary.newFolder()),
            CoroutineScope(job + dispatcher),
            StandardTestDispatcher(dispatcher.scheduler, "background-io")
        )
        try {
            controller.load()
            org.junit.Assert.assertTrue(controller.busy)
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    CompositionLocalProvider(LocalBackgroundController provides controller) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            BackgroundSettingsSection()
                        }
                    }
                }
            }
            composeRule.onNodeWithTag("background_busy").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("background_choose").assertIsNotEnabled()
            composeRule.onNodeWithTag("background_strength").assertIsNotEnabled()
        } finally {
            job.cancel()
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    private fun show(theme: ThemeMode, size: Int) {
        host.activity.setContent {
            ClenderTheme(AppearanceUiState(theme, size, 13)) {
                AppBackground(dark = theme == ThemeMode.DARK) {
                    BackgroundSettingsSection()
                }
            }
        }
        composeRule.onNodeWithTag("background_choose").assertIsDisplayed()
        composeRule.onNodeWithTag("background_strength").assertIsDisplayed()
    }
}
