package com.molotov.clender.ui.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventCrudAppIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun closeProductionDatabase() {
        val application = composeRule.activity.application as ClenderApplication
        ProductionActivityTestResources.close(
            application,
            composeRule.activityRule.scenario
        )
    }

    @Test
    fun newRouteUsesBackChromeCreatesBodyFreeDraftAndCleanBackPops() {
        val shell = shellViewModel()
        shell.pushRoute(AppRoute.NewEvent(LocalDate.of(2026, 8, 31)))

        composeRule.waitUntil { hasNode("event_editor_title") }
        composeRule.onNodeWithTag("clender_open_navigation_drawer").assertDoesNotExist()
        composeRule.onNodeWithTag("clender_navigate_back").assertExists()
        assertTrue(requireNotNull(shell.state.value.draftId).matches(LOWER_HEX_UUID))

        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.waitUntil { shell.state.value.childRoutes.isEmpty() }
        assertNull(shell.state.value.draftId)
    }

    @Test
    fun dirtyNewBackCannotUseDrawerAndRequiresExplicitDiscard() {
        val shell = shellViewModel()
        shell.pushRoute(AppRoute.NewEvent(LocalDate.of(2026, 8, 31)))
        composeRule.waitUntil { hasNode("event_editor_title") }
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("未保存")

        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_dialog").assertExists()
        composeRule.onNodeWithTag("event_discard_keep").performClick()
        assertTrue(shell.state.value.childRoutes.last() is AppRoute.NewEvent)

        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_confirm").performClick()
        composeRule.waitUntil { shell.state.value.childRoutes.isEmpty() }
        assertNull(shell.state.value.draftId)
    }

    @Test
    fun newRouteCanBeReplacedByDetailAndPoppedWithoutRetainingDraft() {
        val shell = shellViewModel()
        shell.pushRoute(AppRoute.NewEvent(LocalDate.of(2026, 8, 31)))
        composeRule.waitUntil { hasNode("event_editor_title") }
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("新增事件")
        shell.drafts.clear()
        shell.replaceTopRoute(AppRoute.EventDetail(41))
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            shell.state.value.childRoutes.lastOrNull() == AppRoute.EventDetail(41) &&
                (hasNode("event_detail_not_found") || hasNode("event_detail_content"))
        }
        assertNull(shell.state.value.draftId)
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            shell.state.value.childRoutes.isEmpty()
        }
    }

    private fun shellViewModel(): AppShellViewModel =
        ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

    private fun hasNode(tag: String): Boolean = composeRule
        .onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
        .fetchSemanticsNodes()
        .isNotEmpty()

    private companion object {
        val LOWER_HEX_UUID = Regex("^[0-9a-f]{32}$")
        const val TIMEOUT_MILLIS = 30_000L
    }
}
