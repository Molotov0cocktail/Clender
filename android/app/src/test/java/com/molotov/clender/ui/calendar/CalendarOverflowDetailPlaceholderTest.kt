package com.molotov.clender.ui.calendar

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.R
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarOverflowDetailPlaceholderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun closeProductionResources() {
        val application = composeRule.activity.application as ClenderApplication
        ProductionActivityTestResources.close(application, composeRule.activityRule.scenario)
    }

    @Test
    fun detailRouteRemainsReadOnlyPlaceholderDuringB1() {
        val viewModel = ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

        composeRule.runOnUiThread { viewModel.pushRoute(AppRoute.EventDetail(7)) }

        composeRule.onAllNodesWithText(
            composeRule.activity.getString(R.string.screen_event_detail)
        ).assertCountEquals(2)
        composeRule.onNodeWithTag("event_detail_form").assertDoesNotExist()
        composeRule.onNodeWithTag("event_detail_delete").assertDoesNotExist()
    }
}
