package com.molotov.clender.ui.theme

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.molotov.clender.R
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.calendar.CalendarLoadStatus
import com.molotov.clender.ui.calendar.CalendarRangePolicy
import com.molotov.clender.ui.calendar.CalendarScreen
import com.molotov.clender.ui.calendar.CalendarScreenActions
import com.molotov.clender.ui.calendar.CalendarScreenModel
import com.molotov.clender.ui.calendar.CalendarScreenState
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.settings.BackgroundSettingsSection
import com.molotov.clender.ui.state.CalendarMode
import java.time.LocalDate
import java.util.Locale
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
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundContentColorTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun settingsPlainTextInheritsRootColorAcrossThemeChanges() = verifyColors(settings = true)

    @Test
    fun calendarEmptyTextInheritsRootColorAcrossThemeChanges() = verifyColors(settings = false)

    private fun verifyColors(settings: Boolean) {
        val mode = mutableStateOf(ThemeMode.LIGHT)
        var expected = Color.Unspecified
        var inherited = Color.Unspecified
        val date = LocalDate.of(2026, 9, 7)
        val calendar = CalendarScreenState(
            CalendarMode.MONTH,
            date,
            CalendarRangePolicy.queryRange(date, CalendarMode.MONTH, Locale.ENGLISH),
            emptyList(),
            CalendarLoadStatus.EMPTY,
            null
        )
        host.activity.setContent {
            ClenderTheme(AppearanceUiState(mode.value, 13, 13)) {
                expected = MaterialTheme.colorScheme.onBackground
                AppBackground(mode.value == ThemeMode.DARK) {
                    Scaffold(containerColor = Color.Transparent) { padding ->
                        inherited = LocalContentColor.current
                        Box(Modifier.padding(padding)) {
                            if (settings) {
                                BackgroundSettingsSection()
                            } else {
                                CalendarScreen(
                                    CalendarScreenModel(calendar, Locale.ENGLISH, date),
                                    CalendarScreenActions({}, {}, {}, {})
                                )
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.LIGHT)) {
            composeRule.runOnIdle { mode.value = theme }
            composeRule.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            val node = if (settings) {
                composeRule.onNodeWithText(host.activity.getString(R.string.background_title))
            } else {
                composeRule.onNodeWithTag("calendar_empty")
            }
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
            assertEquals(
                "Actual text color in $theme",
                expected,
                layouts.single().layoutInput.style.color
            )
            assertEquals("Inherited root content color in $theme", expected, inherited)
        }
    }
}
