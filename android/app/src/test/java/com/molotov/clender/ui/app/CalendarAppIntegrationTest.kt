package com.molotov.clender.ui.app

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.calendar.CalendarLoadStatus
import com.molotov.clender.ui.calendar.CalendarViewModel
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.state.CalendarMode
import java.time.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarAppIntegrationTest {
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
    fun productionActivityConnectsQueryNavigationAndReadOnlyEvents() {
        val calendarViewModel = calendarViewModel()
        val shellViewModel = shellViewModel()

        awaitCalendarLoad(calendarViewModel)

        assertEquals(CalendarLoadStatus.EMPTY, calendarViewModel.state.value.loadStatus)
        val selectedDayTag = "calendar_month_day_${calendarViewModel.state.value.selectedDate}"
        composeRule.onNodeWithTag(selectedDayTag)
            .performScrollTo()
            .assertIsDisplayed()
        val initial = calendarViewModel.state.value
        val selectedMonth = YearMonth.from(initial.selectedDate)
        val outsideDate = initial.queryRange.dates.first { YearMonth.from(it) != selectedMonth }

        composeRule.onNodeWithTag("calendar_month_day_$outsideDate")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            calendarViewModel.state.value.selectedDate == outsideDate &&
                shellViewModel.state.value.selectedDate == outsideDate
        }
        composeRule.onNodeWithTag("calendar_mode_day").performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            calendarViewModel.state.value.mode == CalendarMode.DAY &&
                shellViewModel.state.value.calendarMode == CalendarMode.DAY
        }

        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_events").performClick()

        composeRule.onNodeWithTag("event_list_empty").assertIsDisplayed()
    }

    private fun calendarViewModel(): CalendarViewModel =
        ViewModelProvider(composeRule.activity)[CalendarViewModel::class.java]

    private fun shellViewModel(): AppShellViewModel =
        ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

    private fun awaitCalendarLoad(viewModel: CalendarViewModel) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            viewModel.state.value.loadStatus != CalendarLoadStatus.LOADING
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
