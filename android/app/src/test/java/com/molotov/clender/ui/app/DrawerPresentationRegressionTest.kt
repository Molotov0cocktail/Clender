package com.molotov.clender.ui.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellUiState
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class, qualifiers = "w840dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerPresentationRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()
    private val clicks = mutableListOf<AppDestination>()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun brandAndSectionHeadingsPrecedeTheirDestinations() {
        render()
        val brand = composeRule.onNodeWithTag("drawer_brand")
            .assertIsDisplayed().fetchSemanticsNode()
        val schedule = composeRule.onNodeWithTag("drawer_schedule_heading")
            .assertIsDisplayed().fetchSemanticsNode()
        val application = composeRule.onNodeWithTag("drawer_application_heading")
            .assertIsDisplayed().fetchSemanticsNode()
        val calendar = item(AppDestination.CALENDAR).fetchSemanticsNode()
        val settings = item(AppDestination.SETTINGS).fetchSemanticsNode()
        assertTrue(brand.boundsInRoot.bottom <= schedule.boundsInRoot.top)
        assertTrue(schedule.boundsInRoot.bottom <= calendar.boundsInRoot.top)
        assertTrue(application.boundsInRoot.bottom <= settings.boundsInRoot.top)
    }

    @Test
    fun destinationsHaveDistinctIconsAndSeparatedTouchTargets() {
        render()
        var previousBottom = 0f
        AppDestination.entries.forEach { destination ->
            composeRule.onNodeWithTag("drawer_icon_${destination.route}", useUnmergedTree = true)
                .assertIsDisplayed()
            val node = item(destination).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            assertTrue(node.boundsInRoot.top >= previousBottom + 4f)
            previousBottom = node.boundsInRoot.bottom
        }
    }

    @Test
    fun allDestinationsRemainSelectableAndInvokeTheirOriginalCallback() {
        AppDestination.entries.forEach { current ->
            render(current = current)
            AppDestination.entries.forEach { destination ->
                if (current == destination) {
                    item(destination).assertIsSelected()
                } else {
                    item(destination).assertIsNotSelected()
                }
                item(destination).performClick()
                assertEquals(destination, clicks.last())
            }
        }
    }

    @Config(qualifiers = "en-w840dp-h1000dp-mdpi")
    @Test
    fun englishLargeFontLowHeightCanReachEveryDestinationInBothThemes() = assertScrollable()

    @Config(qualifiers = "zh-rCN-w840dp-h1000dp-mdpi")
    @Test
    fun chineseLargeFontLowHeightCanReachEveryDestinationInBothThemes() = assertScrollable()

    private fun assertScrollable() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            render(height = 260, scale = 2f, theme = theme)
            AppDestination.entries.forEach { destination ->
                item(destination).performScrollTo().assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp).performClick()
                assertEquals(destination, clicks.last())
            }
            composeRule.onNodeWithTag("drawer_brand").performScrollTo().assertIsDisplayed()
        }
    }

    private fun item(destination: AppDestination) =
        composeRule.onNodeWithTag("clender_drawer_${destination.route}")

    private fun render(
        height: Int = 960,
        scale: Float = 1f,
        theme: ThemeMode = ThemeMode.LIGHT,
        current: AppDestination = AppDestination.CALENDAR
    ) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    ClenderTheme(AppearanceUiState(theme, 20, 13)) {
                        Box(Modifier.size(320.dp, height.dp)) {
                            AppDrawerContent(
                                AppShellUiState(
                                    current,
                                    LocalDate.of(2026, 9, 7),
                                    CalendarMode.MONTH,
                                    true,
                                    emptyList(),
                                    null
                                ),
                                clicks::add
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
