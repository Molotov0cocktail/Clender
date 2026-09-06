package com.molotov.clender.ui.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.R
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityShellTest {
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

    @Test
    fun launchingActivityShowsRealAppShellAndCalendarTopLevel() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer").assertExists()

        val context = composeRule.activity
        val title = context.getString(R.string.screen_calendar)
        assertTrue(
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun activityRecreationPreservesTopLevelDestinationThroughViewModel() {
        val scenario = composeRule.activityRule.scenario
        scenario.onActivity { activity ->
            val viewModel = ViewModelProvider(activity).get(AppShellViewModel::class.java)
            viewModel.navigateTo(AppDestination.SETTINGS)
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            val recreated = ViewModelProvider(activity).get(AppShellViewModel::class.java)
            assertEquals(AppDestination.SETTINGS, recreated.state.value.destination)
        }
    }

    @Test
    fun externalIntentUriIsNotAutoExecutedAsRoute() {
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://example.com/calendar")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val viewModel = ViewModelProvider(controller.get()).get(AppShellViewModel::class.java)
            assertEquals(AppDestination.CALENDAR, viewModel.state.value.destination)
            assertTrue(viewModel.state.value.childRoutes.isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
