package com.molotov.clender.ui.calendar

import android.app.Application
import android.text.format.DateFormat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class, qualifiers = "w840dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarVisualRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()
    private val date = LocalDate.of(2026, 9, 7)

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun monthTitleIncludesLocalizedYearAndMonthAcrossYearAndLeapBoundaries() {
        listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
            listOf(date, LocalDate.of(2027, 1, 1), LocalDate.of(2028, 2, 29)).forEach { selected ->
                renderScreen(selected, CalendarMode.MONTH, locale)
                val expected = selected.format(
                    DateTimeFormatter.ofPattern(
                        DateFormat.getBestDateTimePattern(locale, "yMMMM"),
                        locale
                    )
                )
                composeRule.onNodeWithTag("calendar_range_title").assert(hasText(expected))
            }
        }
    }

    @Test
    fun dayAndWeekTitleIdentifyTheActualVisibleRange() {
        listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
            listOf(CalendarMode.DAY, CalendarMode.WEEK).forEach { mode ->
                val selected = LocalDate.of(2026, 12, 31)
                renderScreen(selected, mode, locale)
                val range = CalendarRangePolicy.queryRange(selected, mode, locale)
                val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                val expected = if (mode == CalendarMode.DAY) {
                    selected.format(formatter)
                } else {
                    "${range.dates.first().format(formatter)} – " +
                        range.dates.last().format(formatter)
                }
                composeRule.onNodeWithTag("calendar_range_title").assert(hasText(expected))
            }
        }
    }

    @Test
    fun zeroAndNegativeCountsAreVisuallyAbsentButKeepAccessibleCount() {
        renderMonth(mapOf(date to 0, date.plusDays(1) to -5))
        listOf(date, date.plusDays(1)).forEach { day ->
            val node = composeRule.onNodeWithTag("calendar_month_day_$day")
            node.assert(hasText("0").not())
            val description = node.fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            assertTrue("Zero count remains accessible", description.contains("0"))
        }
    }

    @Test
    fun positiveCountsRemainVisibleAndDatesRetainTheirExactClickTarget() {
        var clicked: LocalDate? = null
        renderMonth(mapOf(date to 3, date.plusDays(1) to 999), onSelect = { clicked = it })
        composeRule.onNodeWithTag("calendar_month_day_$date").assert(hasText("3")).performClick()
        assertEquals(date, clicked)
        val large = composeRule.onNodeWithTag("calendar_month_day_${date.plusDays(1)}")
        val description = large.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue("Full count must remain accessible", description.contains("999"))
    }

    @Test
    fun rangeAndModeActionsKeepExistingCallbacks() {
        val selectedDates = mutableListOf<LocalDate>()
        val selectedModes = mutableListOf<CalendarMode>()
        renderScreen(
            date,
            CalendarMode.MONTH,
            Locale.US,
            actions = CalendarScreenActions(selectedDates::add, selectedModes::add, {}, {})
        )
        listOf("previous_range", "today", "next_range").forEach {
            composeRule.onNodeWithTag("calendar_$it").performClick()
        }
        CalendarMode.entries.forEach {
            composeRule.onNodeWithTag("calendar_mode_${it.wireValue}").performClick()
        }
        assertEquals(
            listOf(date.minusMonths(1), date.plusDays(2), date.plusMonths(1)),
            selectedDates
        )
        assertEquals(CalendarMode.entries, selectedModes)
    }

    @Test
    fun headingAndActionsRemainSeparateAndReachableInThemesAndLargeFonts() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            listOf(360, 600, 840).forEach { width ->
                renderScreen(date, CalendarMode.MONTH, Locale.US, viewport = Viewport(width, theme))
                val heading = composeRule.onNodeWithTag("calendar_range_title")
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val viewport = composeRule.onNodeWithTag("visual_viewport")
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "Heading must fit horizontally",
                    heading.left >= viewport.left && heading.right <= viewport.right
                )
                listOf("month", "week", "day").forEach { mode ->
                    val node = composeRule.onNodeWithTag("calendar_mode_$mode")
                        .assertIsDisplayed().assertHasClickAction()
                        .fetchSemanticsNode().boundsInRoot
                    assertTrue("Heading and mode row must not overlap", heading.bottom <= node.top)
                    assertTrue("Mode touch target", node.width >= 48f && node.height >= 48f)
                }
            }
        }
    }

    @Test
    fun twoDigitDatesFitTheirActualTextLayoutOnNarrowScreensAtMaximumFont() {
        val cases = listOf(
            Triple(320, Locale.US, 1f),
            Triple(360, Locale.US, 1f),
            Triple(320, Locale.SIMPLIFIED_CHINESE, 1f),
            Triple(360, Locale.SIMPLIFIED_CHINESE, 1f),
            Triple(320, Locale.US, 2.625f)
        )
        cases.forEach { (width, locale, density) ->
            val selected = LocalDate.of(2026, 8, 15)
            render(Viewport(width, ThemeMode.LIGHT, density)) {
                MonthCalendar(
                    MonthCalendarModel(selected, selected, locale, emptyMap()),
                    {},
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
            listOf(28, 30, 31).forEach { number ->
                val tag = "calendar_month_day_${selected.withDayOfMonth(number)}"
                val node = composeRule.onNode(
                    hasText(number.toString()) and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true
                )
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    assertTrue(it(layouts))
                }
                assertEquals(1, layouts.size)
                val layout = layouts.single()
                assertDateGlyphsFit(layout, "$number at $width/$locale/$density")
            }
        }
    }

    @Test
    fun weekdayLabelsRemainCompleteOnOneLineAtMaximumFont() {
        listOf(320, 360, 840).forEach { width ->
            listOf(1f, 2.625f).forEach { density ->
                listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
                    render(Viewport(width, ThemeMode.LIGHT, density)) {
                        MonthCalendar(
                            MonthCalendarModel(date, date, locale, emptyMap()),
                            {},
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    repeat(7) { index ->
                        val layouts = mutableListOf<TextLayoutResult>()
                        composeRule.onNodeWithTag("calendar_month_weekday_$index")
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                                assertTrue(it(layouts))
                            }
                        assertEquals(1, layouts.size)
                        assertDateGlyphsFit(
                            layouts.single(),
                            "Weekday $index at $width/$locale/$density"
                        )
                    }
                }
            }
        }
    }

    private fun assertDateGlyphsFit(layout: TextLayoutResult, label: String) {
        val width = minOf(layout.size.width, layout.layoutInput.constraints.maxWidth).toFloat()
        val glyphs = layout.layoutInput.text.indices.map(layout::getBoundingBox)
        val diagnostic = "$label: size=${layout.size}, paragraph=${layout.multiParagraph.width}, " +
            "intrinsic=${layout.multiParagraph.maxIntrinsicWidth}, " +
            "line=${layout.getLineLeft(0)}..${layout.getLineRight(0)}, glyphs=$glyphs"
        assertEquals("Date must remain on one line: $diagnostic", 1, layout.lineCount)
        assertFalse("Date cannot be ellipsized: $diagnostic", layout.isLineEllipsized(0))
        assertTrue(
            "Date line cannot start outside its layout: $diagnostic",
            layout.getLineLeft(0) >= 0f
        )
        assertTrue(
            "Date line cannot exceed its layout: $diagnostic",
            layout.getLineRight(0) <= width
        )
        glyphs.forEach { glyph ->
            assertTrue(
                "Each digit must fit horizontally: $diagnostic",
                glyph.left >= 0f && glyph.right <= width
            )
            assertTrue(
                "Each digit must fit vertically: $diagnostic",
                glyph.top >= 0f && glyph.bottom <= layout.size.height
            )
        }
    }

    @Test
    fun narrowMaximumFontMonthHeadingRendersTheCompleteMonthWithoutEllipsis() {
        listOf(320, 360).forEach { width ->
            listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
                renderScreen(date, CalendarMode.MONTH, locale, Viewport(width, ThemeMode.DARK))
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule.onNodeWithTag("calendar_range_title")
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                        assertTrue(it(layouts))
                    }
                assertEquals(1, layouts.size)
                val layout = layouts.single()
                assertFalse("Month heading must fit its text layout", layout.didOverflowWidth)
                repeat(layout.lineCount) { line ->
                    assertFalse(
                        "Month heading must remain fully readable",
                        layout.isLineEllipsized(line)
                    )
                }
            }
        }
    }

    private fun renderScreen(
        selected: LocalDate,
        mode: CalendarMode,
        locale: Locale,
        viewport: Viewport = Viewport(840, ThemeMode.LIGHT),
        actions: CalendarScreenActions = CalendarScreenActions({}, {}, {}, {})
    ) = render(viewport) {
        CalendarScreen(
            CalendarScreenModel(
                CalendarScreenState(
                    mode,
                    selected,
                    CalendarRangePolicy.queryRange(selected, mode, locale),
                    loadStatus = CalendarLoadStatus.CONTENT
                ),
                locale,
                date.plusDays(2)
            ),
            actions
        )
    }

    private fun renderMonth(counts: Map<LocalDate, Int>, onSelect: (LocalDate) -> Unit = {}) =
        render(Viewport(840, ThemeMode.LIGHT)) {
            MonthCalendar(MonthCalendarModel(date, date.plusDays(2), Locale.US, counts), onSelect)
        }

    private fun render(viewport: Viewport, content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(viewport.density, 2f)) {
                    ClenderTheme(AppearanceUiState(viewport.theme, 20, 13)) {
                        Box(Modifier.size(viewport.width.dp, 900.dp).testTag("visual_viewport")) {
                            content()
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private data class Viewport(val width: Int, val theme: ThemeMode, val density: Float = 1f)
}
