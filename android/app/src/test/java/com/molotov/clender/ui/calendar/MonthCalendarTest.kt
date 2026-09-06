package com.molotov.clender.ui.calendar

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonthCalendarTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    private val selected = LocalDate.of(2026, 8, 15)
    private val today = LocalDate.of(2026, 8, 20)

    @Test
    fun usMonthAlwaysExposesFortyTwoClickableDateCellsStartingOnSunday() {
        setMonth(locale = Locale.US)

        composeRule.onAllNodes(testTagStartsWith(DAY_TAG_PREFIX)).assertCountEquals(42)
        composeRule.onNodeWithTag("${WEEKDAY_TAG_PREFIX}0").assert(
            hasText(DayOfWeek.SUNDAY.getDisplayName(TextStyle.SHORT, Locale.US))
        )
        composeRule.onNodeWithTag("${DAY_TAG_PREFIX}2026-07-26").assertHasClickAction()
    }

    @Test
    fun simplifiedChineseMonthAlwaysExposesFortyTwoCellsStartingOnMonday() {
        setMonth(locale = Locale.SIMPLIFIED_CHINESE)

        composeRule.onAllNodes(testTagStartsWith(DAY_TAG_PREFIX)).assertCountEquals(42)
        composeRule.onNodeWithTag("${WEEKDAY_TAG_PREFIX}0").assert(
            hasText(
                DayOfWeek.MONDAY.getDisplayName(
                    TextStyle.SHORT,
                    Locale.SIMPLIFIED_CHINESE
                )
            )
        )
        composeRule.onNodeWithTag("${DAY_TAG_PREFIX}2026-07-27").assertExists()
    }

    @Test
    fun selectedTodayOutsideMonthAndCountsHaveNonColorSemantics() {
        setMonth(
            locale = Locale.US,
            eventCounts = mapOf(
                LocalDate.of(2026, 8, 1) to 0,
                LocalDate.of(2026, 8, 2) to 1,
                LocalDate.of(2026, 8, 3) to 3
            )
        )

        val selectedDescription = descriptionFor(selected)
        val todayDescription = descriptionFor(today)
        val outsideDescription = descriptionFor(LocalDate.of(2026, 7, 26))
        val neutralDescription = descriptionFor(LocalDate.of(2026, 8, 4))

        assertTrue(selectedDescription.isNotBlank())
        assertTrue(todayDescription.isNotBlank())
        assertTrue(outsideDescription.isNotBlank())
        assertNotEquals(neutralDescription, selectedDescription)
        assertNotEquals(neutralDescription, todayDescription)
        assertNotEquals(neutralDescription, outsideDescription)
        assertTrue(descriptionFor(LocalDate.of(2026, 8, 1)).contains("0"))
        assertTrue(descriptionFor(LocalDate.of(2026, 8, 2)).contains("1"))
        assertTrue(descriptionFor(LocalDate.of(2026, 8, 3)).contains("3"))
    }

    @Test
    fun clickingVisibleOutsideMonthDateReturnsThatExactDate() {
        var clicked: LocalDate? = null
        setMonth(locale = Locale.US, onDateSelected = { clicked = it })

        composeRule.onNodeWithTag("${DAY_TAG_PREFIX}2026-07-26").performClick()
        composeRule.waitForIdle()

        assertEquals(LocalDate.of(2026, 7, 26), clicked)
    }

    @Config(qualifiers = "w360dp-h640dp-420dpi")
    @Test
    fun phoneWidthLightThemeEightSpKeepsEveryDateReachableAtDoubleFontScale() {
        assertResponsiveMonth(ThemeMode.LIGHT, appFontSizeSp = 8)
    }

    @Config(qualifiers = "w600dp-h800dp-420dpi")
    @Test
    fun mediumWidthDarkThemeTwentySpKeepsEveryDateReachableAtDoubleFontScale() {
        assertResponsiveMonth(ThemeMode.DARK, appFontSizeSp = 20)
    }

    @Config(qualifiers = "w840dp-h900dp-420dpi")
    @Test
    fun expandedWidthLightThemeTwentySpKeepsEveryDateReachableAtDoubleFontScale() {
        assertResponsiveMonth(ThemeMode.LIGHT, appFontSizeSp = 20)
    }

    private fun assertResponsiveMonth(themeMode: ThemeMode, appFontSizeSp: Int) {
        setMonth(
            locale = Locale.SIMPLIFIED_CHINESE,
            appearance = AppearanceUiState(themeMode, appFontSizeSp, widgetFontSizeSp = 13),
            fontScale = 2f
        )
        composeRule.onAllNodes(testTagStartsWith(DAY_TAG_PREFIX)).assertCountEquals(42)
        listOf("2026-07-27", "2026-08-15", "2026-09-06").forEach { isoDate ->
            composeRule.onNodeWithTag("$DAY_TAG_PREFIX$isoDate")
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private fun setMonth(
        locale: Locale,
        eventCounts: Map<LocalDate, Int> = emptyMap(),
        appearance: AppearanceUiState = AppearanceUiState(
            ThemeMode.SYSTEM,
            appFontSizeSp = 13,
            widgetFontSizeSp = 13
        ),
        fontScale: Float = 1f,
        onDateSelected: (LocalDate) -> Unit = {}
    ) {
        setTestContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                ClenderTheme(appearance) {
                    MonthCalendar(
                        model = MonthCalendarModel(
                            selectedDate = selected,
                            today = today,
                            locale = locale,
                            eventCounts = eventCounts
                        ),
                        onDateSelected = onDateSelected
                    )
                }
            }
        }
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent(content = content)
        }
        composeRule.waitForIdle()
    }

    private fun descriptionFor(date: LocalDate): String {
        val node = composeRule.onNodeWithTag("$DAY_TAG_PREFIX$date").fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(separator = " ")
            .orEmpty()
    }

    private fun testTagStartsWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("test tag starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    private companion object {
        const val DAY_TAG_PREFIX = "calendar_month_day_"
        const val WEEKDAY_TAG_PREFIX = "calendar_month_weekday_"
    }
}
