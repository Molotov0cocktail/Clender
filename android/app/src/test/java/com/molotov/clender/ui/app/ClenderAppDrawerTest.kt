package com.molotov.clender.ui.app

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClenderAppDrawerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun closeProductionResources() {
        val application = composeRule.activity.application as ClenderApplication
        ProductionActivityTestResources.close(
            application,
            composeRule.activityRule.scenario
        )
    }

    private fun shellViewModel(): AppShellViewModel =
        ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

    @Test
    fun drawerToggleHasStableTagLocalizedDescriptionAndMinTouchTarget() {
        val toggle = composeRule.onNodeWithTag("clender_open_navigation_drawer")
        toggle.assertExists()
        toggle.assertHasClickAction()
        toggle.assert(
            SemanticsMatcher("has a non-blank content description") { node ->
                val description = node.config.getOrNull(
                    androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
                )
                description?.any { it.isNotBlank() } == true
            }
        )
        toggle.assertWidthIsAtLeast(48.dp)
        toggle.assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun drawerShowsAllFiveLocalizedItemsWithSelectionOnCurrentDestination() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()

        listOf("calendar", "events", "ai", "settings", "about").forEach { route ->
            composeRule.onNodeWithTag("clender_drawer_$route").assertExists()
        }

        composeRule.onNodeWithTag("clender_drawer_calendar").assertIsSelected()
        listOf("events", "ai", "settings", "about").forEach { route ->
            composeRule.onNodeWithTag("clender_drawer_$route").assertIsNotSelected()
        }
    }

    @Test
    fun clickingDrawerItemSwitchesDestinationAndClosesDrawer() {
        val viewModel = shellViewModel()

        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_settings").performClick()

        assertEquals(AppDestination.SETTINGS, viewModel.state.value.destination)
        assertFalse(viewModel.state.value.drawerOpen)
    }

    @Test
    fun clickingAboutOpensTheRealReadOnlyPageWithoutInitializingNetworkStacks() {
        val container = (composeRule.activity.application as ClenderApplication).container
        val lazyNames = listOf(
            "secretCipherHolder",
            "secretStoreHolder",
            "aiClientHolder",
            "aiSubmissionGatewayHolder",
            "webDavProbeHolder",
            "webDavSyncRuntimeHolder"
        )
        lazyNames.forEach {
            assertFalse("$it must remain lazy", isContainerLazyInitialized(container, it))
        }

        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_about").performClick()

        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithTag("about_title").assertIsDisplayed()
        composeRule.onNodeWithTag("about_content").assertIsDisplayed()
        lazyNames.forEach {
            assertFalse("$it must remain lazy", isContainerLazyInitialized(container, it))
        }
    }

    @Test
    fun drawerItemLabelsComeFromLocalizedResources() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()

        val context = composeRule.activity
        listOf(
            "calendar" to com.molotov.clender.R.string.drawer_calendar,
            "events" to com.molotov.clender.R.string.drawer_events,
            "ai" to com.molotov.clender.R.string.drawer_ai,
            "settings" to com.molotov.clender.R.string.drawer_settings,
            "about" to com.molotov.clender.R.string.drawer_about
        ).forEach { (route, labelRes) ->
            composeRule.onNodeWithTag("clender_drawer_$route")
                .assert(hasText(context.getString(labelRes)))
        }
    }

    @Config(qualifiers = "w360dp-h640dp-420dpi")
    @Test
    fun drawerUsesModalChromeAtPhoneWidth360dp() {
        assertModalDrawerBehavior()
    }

    @Config(qualifiers = "w600dp-h800dp-420dpi")
    @Test
    fun drawerUsesModalChromeAtMediumWidth600dp() {
        assertModalDrawerBehavior()
    }

    @Config(qualifiers = "w840dp-h800dp-420dpi")
    @Test
    fun drawerUsesModalChromeAtExpandedWidth840dp() {
        assertModalDrawerBehavior()
    }

    private fun assertModalDrawerBehavior() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer").assertExists()
        composeRule.onNodeWithTag("clender_drawer_calendar").assertIsNotDisplayed()

        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_calendar").assertIsDisplayed()

        composeRule.onNodeWithTag("clender_drawer_settings").performClick()
        composeRule.onNodeWithTag("clender_drawer_settings").assertIsNotDisplayed()
    }

    private fun isContainerLazyInitialized(container: Any, name: String): Boolean {
        val field = container.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return (field.get(container) as Lazy<*>).isInitialized()
    }
}
