package com.molotov.clender.ui.app

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.settings.SettingsStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class, qualifiers = "en-rUS-w411dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerStateSynchronizationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = DrawerStateTestHost()

    @Before
    fun startComponentActivity() {
        host.start()
    }

    @After
    fun closeComponentActivity() {
        host.close()
    }

    @Test
    fun scrimCloseSynchronizesStateAndActualButtonCanReopenRepeatedly() {
        render()
        repeat(2) {
            open()
            closeScrimBySemantics()
            composeRule.onNodeWithTag("clender_drawer_calendar").assertIsNotDisplayed()
            open()
            composeRule.runOnUiThread { host.activity.onBackPressedDispatcher.onBackPressed() }
            assertClosed()
        }
        open()
        assertEquals(AppDestination.CALENDAR, host.shell.state.value.destination)
        assertTrue(host.shell.state.value.childRoutes.isEmpty())
    }

    @Test
    fun swipeCloseSynchronizesStateAndDoesNotDisableNextOpen() {
        render()
        open()
        composeRule.onNodeWithTag("clender_drawer_calendar").performTouchInput { swipeLeft() }
        assertClosed()
        open()
    }

    @Test
    fun edgeSwipeOpenSynchronizesShellAndSettingsBeforeScrimClose() {
        render()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(1f, height / 2f), Offset(width * 0.8f, height / 2f), 350)
        }
        assertOpen()
        closeScrimByTouch()
        assertClosed()
        open()
    }

    @Test
    fun backAndRepeatedCurrentDestinationCloseWithoutFinishingHost() {
        render()
        open()
        composeRule.runOnUiThread { host.activity.onBackPressedDispatcher.onBackPressed() }
        assertClosed()
        repeat(2) {
            open()
            composeRule.onNodeWithTag("clender_drawer_calendar").performClick()
            assertClosed()
        }
        assertFalse(host.activity.isFinishing)
        open()
    }

    @Test
    fun firstCompositionDoesNotOverwriteAnExistingOpenRequestWithInitialClosed() {
        composeRule.runOnUiThread { host.shell.openDrawer() }
        render()
        // Initial material Closed is transient; it must not cancel the requested open.
        composeRule.onNodeWithTag("clender_drawer_calendar").assertIsDisplayed()
        assertTrue(host.shell.state.value.drawerOpen)
        closeScrimByTouch()
        assertClosed()
        open()
    }

    @Test
    fun dirtySettingsScrimClosePreservesDraftAndKeepDiscardStillControlNavigation() {
        render()
        open()
        composeRule.onNodeWithTag("clender_drawer_settings").performClick()
        composeRule.runOnIdle {
            host.settings.updateAppearance(
                host.settings.state.value.appearance.copy(appFontSize = "18")
            )
        }
        assertTrue(host.settings.state.value.dirty)
        open()
        closeScrimByTouch()
        assertClosed()
        assertTrue(host.settings.state.value.dirty)
        assertEquals("18", host.settings.state.value.appearance.appFontSize)
        open()
        composeRule.onNodeWithTag("clender_drawer_events").performClick()
        composeRule.onNodeWithTag("settings_discard_cancel").assertIsDisplayed().performClick()
        assertEquals(AppDestination.SETTINGS, host.shell.state.value.destination)
        assertTrue(host.settings.state.value.dirty)
        assertOpen()
        composeRule.onNodeWithTag("clender_drawer_events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("settings_discard_confirm").assertIsDisplayed().performClick()
        assertEquals(AppDestination.EVENTS, host.shell.state.value.destination)
        assertFalse(host.settings.state.value.dirty)
        assertClosed()
        open()
    }

    @Test
    fun newEventDirtyBackStillRequiresKeepAfterDrawerReopen() {
        render()
        open()
        closeScrimByTouch()
        assertClosed()
        composeRule.onNodeWithTag("clender_create_event").performClick()
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("未保存 drawer draft")
        val route = host.shell.state.value.childRoutes
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_keep").assertIsDisplayed().performClick()
        assertEquals(route, host.shell.state.value.childRoutes)
        assertEquals("未保存 drawer draft", host.editor.state.value.form?.title)
    }

    @Test
    fun activityRecreateAfterScrimCloseKeepsViewModelsAndDrawerUsableWithoutNewSavedKeys() {
        render()
        open()
        closeScrimByTouch()
        assertClosed()
        val shell = host.shell
        val settings = host.settings
        val keys = host.savedState.keys()
        composeRule.runOnUiThread { host.recreate() }
        composeRule.waitForIdle()
        assertSame(shell, host.shell)
        assertSame(settings, host.settings)
        assertClosed()
        assertEquals(keys, host.savedState.keys())
        assertFalse(host.savedState.keys().any { it.contains("drawer", ignoreCase = true) })
        open()
        composeRule.runOnUiThread { host.recreate() }
        composeRule.waitForIdle()
        assertOpen()
        closeScrimByTouch()
        assertClosed()
        open()
    }

    @Config(qualifiers = "zh-rCN-w411dp-h640dp")
    @Test
    fun chineseThemeAndFontChangesKeepScrimAndButtonWorking() {
        render()
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            listOf(8, 20).forEach { size ->
                composeRule.runOnUiThread {
                    host.appearance.value = AppearanceUiState(theme, size, 13)
                }
                open()
                closeScrimByTouch()
                assertClosed()
            }
        }
        open()
    }

    @Test
    fun settingsLoadFailureDoesNotLeaveDrawerOpenFlagStuck() {
        host.settingsPort.failLoad = true
        render()
        open()
        composeRule.onNodeWithTag("clender_drawer_settings").performClick()
        composeRule.runOnIdle {
            assertEquals(SettingsStatus.INTERNAL, host.settings.state.value.status)
        }
        open()
        closeScrimByTouch()
        assertClosed()
        open()
        composeRule.onNodeWithTag("clender_drawer_calendar").performClick()
        assertClosed()
    }

    @Test
    fun interruptedScrimCloseAndOpenAnimationHonorLatestRequest() {
        render()
        open()
        composeRule.mainClock.autoAdvance = false
        try {
            closeScrimBySemantics()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnUiThread {
                assertFalse(host.shell.state.value.drawerOpen)
                assertFalse(host.settings.state.value.drawerOpen)
            }
            composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnUiThread { host.shell.closeDrawer() }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
            composeRule.mainClock.advanceTimeBy(1_000)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertOpen()
        closeScrimByTouch()
        assertClosed()
        open()
    }

    @Config(qualifiers = "en-rUS-w360dp-h640dp")
    @Test
    fun narrowDrawerWithoutExposedScrimStillClosesByBackAndSwipe() {
        render()
        open()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val drawer = composeRule.onNodeWithTag("clender_drawer_calendar")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(root.right, drawer.right, 0.5f)
        composeRule.runOnUiThread { host.activity.onBackPressedDispatcher.onBackPressed() }
        assertClosed()
        open()
        composeRule.onNodeWithTag("clender_drawer_calendar").performTouchInput { swipeLeft() }
        assertClosed()
        open()
        closeScrimBySemantics()
        assertClosed()
    }

    private fun render() {
        composeRule.runOnUiThread { host.render() }
        composeRule.waitForIdle()
    }

    private fun open() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performClick()
        assertOpen()
    }

    private fun assertOpen() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clender_drawer_calendar").assertIsDisplayed()
        assertTrue(host.shell.state.value.drawerOpen)
        assertTrue(host.settings.state.value.drawerOpen)
    }

    private fun closeScrimByTouch() {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val drawer = composeRule.onNodeWithTag("clender_drawer_calendar")
            .fetchSemanticsNode().boundsInRoot
        val x = root.width - 2f
        assertTrue("Touch must land outside Drawer: root=$root drawer=$drawer", x > drawer.right)
        composeRule.onRoot().performTouchInput { click(Offset(x, height / 2f)) }
    }

    private fun closeScrimBySemantics() {
        composeRule.onNodeWithContentDescription("Close navigation menu")
            .performSemanticsAction(SemanticsActions.OnClick) { action ->
                assertTrue("Scrim OnClick must accept the action", action())
            }
    }

    private fun assertClosed() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clender_drawer_calendar").assertIsNotDisplayed()
        assertFalse(host.shell.state.value.drawerOpen)
        assertFalse(host.settings.state.value.drawerOpen)
    }
}
