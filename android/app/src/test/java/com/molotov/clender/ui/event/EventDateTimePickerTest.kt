package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventDateTimePickerTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val composeHost = RobolectricComposeHost()
    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHostAndRestoreTimeZone() {
        try {
            composeHost.close()
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun utcEpochCodecCoversEpochLeapAndExtremeSupportedYears() {
        listOf(
            LocalDate.of(1970, 1, 1),
            LocalDate.of(2000, 2, 29),
            LocalDate.of(1, 1, 1),
            LocalDate.of(9999, 12, 31)
        ).forEach { date ->
            val millis = EventDateTimePickerCodec.toUtcEpochMillis(date)
            assertEquals(date, EventDateTimePickerCodec.fromUtcEpochMillis(millis))
        }
        assertEquals(0L, EventDateTimePickerCodec.toUtcEpochMillis(LocalDate.of(1970, 1, 1)))
    }

    @Test
    fun utcDateCodecNeverDriftsUnderUtcPlus14OrUtcMinus12Defaults() {
        val date = LocalDate.of(2024, 2, 29)
        listOf("Pacific/Kiritimati", "Etc/GMT+12").forEach { zone ->
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val millis = EventDateTimePickerCodec.toUtcEpochMillis(date)
            assertEquals(date, EventDateTimePickerCodec.fromUtcEpochMillis(millis))
        }
    }

    @Test
    fun nonMidnightMillisFailClosedInsteadOfSilentlyChangingDate() {
        val utcMidnight = LocalDate.of(2026, 8, 31)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

        assertNull(EventDateTimePickerCodec.fromUtcEpochMillis(utcMidnight + 1))
        assertNull(EventDateTimePickerCodec.fromUtcEpochMillis(utcMidnight - 1))
    }

    @Test
    fun replacingDateOrTimeAlwaysClearsSecondsAndNanos() {
        val original = LocalDateTime.of(2026, 8, 31, 9, 17, 58, 999_999_999)

        assertEquals(
            LocalDateTime.of(2027, 1, 2, 9, 17),
            EventDateTimePickerCodec.replaceDate(original, LocalDate.of(2027, 1, 2))
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 31, 23, 59),
            EventDateTimePickerCodec.replaceTime(original, LocalTime.of(23, 59, 42, 1))
        )
    }

    @Test
    fun dateDialogHasTitleConfirmCancelAndReturnsUtcDecodedDate() {
        val selected = LocalDate.of(2026, 8, 31)
        var result: LocalDate? = null
        var dismissals = 0
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                EventDatePickerDialog(
                    selectedDate = selected,
                    onDateSelected = { result = it },
                    onDismiss = { dismissals += 1 }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("event_date_picker_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("event_date_picker_title").assertIsDisplayed()
        composeRule.onNodeWithTag("event_date_picker_cancel").assertHasClickAction()
        composeRule.onNodeWithTag("event_date_picker_confirm")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(selected, result)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun dateDialogRendersForExtremeSupportedYear() {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                EventDatePickerDialog(
                    selectedDate = LocalDate.of(9999, 12, 31),
                    onDateSelected = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("event_date_picker_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("event_date_picker_confirm").assertHasClickAction()
    }

    @Test
    fun timeDialogHasTitleConfirmCancelAndReturnsMinutePrecision() {
        var result: LocalTime? = null
        var dismissals = 0
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                EventTimePickerDialog(
                    selectedTime = LocalTime.of(9, 17, 58, 999_999_999),
                    onTimeSelected = { result = it },
                    onDismiss = { dismissals += 1 }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("event_time_picker_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("event_time_picker_title").assertIsDisplayed()
        composeRule.onNodeWithTag("event_time_picker_cancel").assertHasClickAction()
        composeRule.onNodeWithTag("event_time_picker_confirm")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(LocalTime.of(9, 17), result)
            assertEquals(0, dismissals)
        }
    }
}
