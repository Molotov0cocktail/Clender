package com.molotov.clender.ui.calendar

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.calendar.CalendarLayoutConfig
import com.molotov.clender.domain.calendar.CalendarLayoutEngine
import com.molotov.clender.domain.calendar.CalendarLayoutResult
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import java.time.LocalDateTime
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
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarTimelineTest {
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

    private val day = LocalDate.of(2026, 8, 31)

    @Test
    fun dayTimelineExposesTwentyFourHoursAndOneDateColumn() {
        setTimeline(dates = listOf(day), layoutResult = layout(emptyList(), 1))

        composeRule.onAllNodes(tagStartsWith(HOUR_TAG_PREFIX)).assertCountEquals(24)
        composeRule.onAllNodes(tagStartsWith(COLUMN_TAG_PREFIX)).assertCountEquals(1)
        (0..23).forEach { hour ->
            composeRule.onNodeWithTag("$HOUR_TAG_PREFIX$hour").assertExists()
        }
    }

    @Test
    fun weekTimelineExposesTwentyFourHoursAndSevenDateColumns() {
        val dates = (0L..6L).map(day::plusDays)
        setTimeline(dates = dates, layoutResult = layout(emptyList(), 7))

        composeRule.onAllNodes(tagStartsWith(HOUR_TAG_PREFIX)).assertCountEquals(24)
        composeRule.onAllNodes(tagStartsWith(COLUMN_TAG_PREFIX)).assertCountEquals(7)
        dates.indices.forEach { column ->
            composeRule.onNodeWithTag("$COLUMN_TAG_PREFIX$column").assertExists()
        }
    }

    @Test
    fun timelineRendersEngineMarkerEstimatedDurationAndCrossDaySegments() {
        val start = day.atStartOfDay()
        val marker = event(1, start.plusHours(9))
        val estimated = event(2, start.plusHours(10), estimatedMinutes = 30)
        val crossDay = event(
            id = 3,
            startTime = start.plusHours(23).plusMinutes(30),
            type = EventType.TIMESPAN,
            endTime = start.plusDays(1).plusMinutes(30)
        )
        val dates = (0L..6L).map(day::plusDays)
        val result = layout(listOf(marker, estimated, crossDay), dayCount = 7)

        setTimeline(dates, result)

        composeRule.onNodeWithTag(eventTag(column = 0, id = 1)).assertExists()
        composeRule.onNodeWithTag(eventTag(column = 0, id = 2)).assertExists()
        composeRule.onNodeWithTag(eventTag(column = 0, id = 3)).assertExists()
        composeRule.onNodeWithTag(eventTag(column = 1, id = 3)).assertExists()
        assertTrue(result.blocks.single { it.id == 1L }.marker)
        assertEquals(30, result.blocks.single { it.id == 2L }.durationMinutes)
    }

    @Test
    fun visualCanvasAndClickableSemanticsOverlayRemainSeparate() {
        val result = layout(listOf(event(1, day.atTime(9, 0), estimatedMinutes = 30)), 1)
        setTimeline(listOf(day), result)

        val visual = composeRule.onNodeWithTag(VISUAL_LAYER_TAG).fetchSemanticsNode()
        assertFalse(visual.config.contains(SemanticsActions.OnClick))
        composeRule.onNodeWithTag(SEMANTICS_LAYER_TAG).assertExists()
        composeRule.onNodeWithTag(eventTag(0, 1)).assertHasClickAction()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Config(qualifiers = "w360dp-h640dp-420dpi")
    @Test
    fun phoneTimelineLightEightSpIsReachableAtDoubleFontScale() {
        assertResponsiveTimeline(ThemeMode.LIGHT, appFontSizeSp = 8)
    }

    @Config(qualifiers = "w600dp-h800dp-420dpi")
    @Test
    fun mediumTimelineDarkTwentySpIsReachableAtDoubleFontScale() {
        assertResponsiveTimeline(ThemeMode.DARK, appFontSizeSp = 20)
    }

    @Config(qualifiers = "w840dp-h900dp-420dpi")
    @Test
    fun expandedTimelineLightTwentySpIsReachableAtDoubleFontScale() {
        assertResponsiveTimeline(ThemeMode.LIGHT, appFontSizeSp = 20)
    }

    private fun assertResponsiveTimeline(themeMode: ThemeMode, appFontSizeSp: Int) {
        val result = layout(listOf(event(1, day.atTime(12, 0), estimatedMinutes = 30)), 1)
        setTimeline(
            dates = listOf(day),
            layoutResult = result,
            themeMode = themeMode,
            appFontSizeSp = appFontSizeSp,
            fontScale = 2f
        )
        composeRule.onAllNodes(tagStartsWith(HOUR_TAG_PREFIX)).assertCountEquals(24)
        composeRule.onNodeWithTag(eventTag(0, 1)).assertHasClickAction().performClick()
    }

    private fun setTimeline(
        dates: List<LocalDate>,
        layoutResult: CalendarLayoutResult,
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        appFontSizeSp: Int = 13,
        fontScale: Float = 1f
    ) {
        setTestContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                ClenderTheme(
                    AppearanceUiState(themeMode, appFontSizeSp, widgetFontSizeSp = 13)
                ) {
                    CalendarTimeline(
                        dates = dates,
                        layoutResult = layoutResult,
                        onEventClick = {},
                        onOverflowClick = {}
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

    private fun layout(
        events: List<Event>,
        dayCount: Int,
        columnWidth: Float = 480f
    ): CalendarLayoutResult = CalendarLayoutEngine.layout(
        events,
        CalendarLayoutConfig(
            rangeStart = day,
            rangeEndExclusive = day.plusDays(dayCount.toLong()),
            perHourLogicalHeight = 60f,
            columnWidth = columnWidth,
            minimumVisualHeight = 12f,
            minimumAccessibleLaneWidth = 48f
        )
    )

    private fun event(
        id: Long,
        startTime: LocalDateTime,
        type: EventType = EventType.REMINDER,
        endTime: LocalDateTime? = null,
        estimatedMinutes: Int = 0
    ): Event = eventFixture(
        id = id,
        eventType = type,
        startTime = startTime,
        endTime = endTime,
        estimatedDurationMinutes = estimatedMinutes,
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun tagStartsWith(prefix: String): SemanticsMatcher =
        SemanticsMatcher("test tag starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    private fun eventTag(column: Int, id: Long): String = "calendar_event_${column}_$id"

    private companion object {
        const val HOUR_TAG_PREFIX = "calendar_timeline_hour_"
        const val COLUMN_TAG_PREFIX = "calendar_timeline_column_"
        const val VISUAL_LAYER_TAG = "calendar_timeline_visual_layer"
        const val SEMANTICS_LAYER_TAG = "calendar_timeline_semantics_layer"
    }
}
