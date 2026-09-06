package com.molotov.clender.ui.calendar

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.calendar.CalendarBlock
import com.molotov.clender.domain.calendar.CalendarLayoutConfig
import com.molotov.clender.domain.calendar.CalendarLayoutEngine
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.After
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
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarC3AdaptiveAccessibilityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = com.molotov.clender.testsupport.RobolectricComposeHost()
    private val selectedDate = LocalDate.of(2026, 8, 15)

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    @Test
    fun toolbarActionsStayInsideTheViewportAtEveryWidthOrientationDirectionAndFontBoundary() {
        val cases = listOf(
            ViewportCase(360, 640, LayoutDirection.Ltr, 8),
            ViewportCase(599, 360, LayoutDirection.Rtl, 20),
            ViewportCase(600, 800, LayoutDirection.Ltr, 20),
            ViewportCase(839, 480, LayoutDirection.Rtl, 8),
            ViewportCase(840, 900, LayoutDirection.Ltr, 20)
        )
        cases.forEach { viewport ->
            setCalendarContent(viewport, fontScale = 2f)
            val viewportBounds = composeRule.onNodeWithTag(VIEWPORT_TAG)
                .fetchSemanticsNode().boundsInRoot
            ACTION_TAGS.forEach { tag ->
                val node = composeRule.onNodeWithTag(tag).assertHasClickAction()
                    .assertIsDisplayed().fetchSemanticsNode()
                val bounds = node.boundsInRoot
                val minimum = 48f * composeHost.activity.resources.displayMetrics.density
                assertTrue("$tag must be at least 48dp", bounds.width >= minimum)
                assertTrue("$tag must be at least 48dp high", bounds.height >= minimum)
                assertTrue(
                    "$tag is clipped on the left at ${viewport.widthDp}dp",
                    bounds.left >= viewportBounds.left
                )
                assertTrue(
                    "$tag is clipped on the right at ${viewport.widthDp}dp",
                    bounds.right <= viewportBounds.right
                )
                assertTrue("$tag is clipped vertically", bounds.top >= viewportBounds.top)
                assertTrue("$tag is clipped vertically", bounds.bottom <= viewportBounds.bottom)
            }
        }
    }

    @Config(qualifiers = "w360dp-h640dp-420dpi")
    @Test
    fun monthDateSemanticsUseLocalizedReadableDatesAndRemainClickableInBothDirections() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.US).forEach { locale ->
            listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
                setMonthContent(
                    MonthRenderCase(
                        locale = locale,
                        direction = direction,
                        widthDp = 360,
                        heightDp = 360,
                        fontSizeSp = 20,
                        fontScale = 2f
                    )
                )
                val node = composeRule.onNodeWithTag("calendar_month_day_$selectedDate")
                    .performScrollTo()
                    .assertHasClickAction()
                    .fetchSemanticsNode()
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.joinToString(" ").orEmpty()
                val localizedDate = selectedDate.format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
                )
                assertTrue(
                    "Missing locale-aware date in $description",
                    description.contains(localizedDate)
                )
                assertFalse(
                    "ISO date must not be the only spoken label",
                    description == selectedDate.toString()
                )
                assertMinimumTouchTarget(node.boundsInRoot.width, node.boundsInRoot.height)
            }
        }
    }

    @Config(qualifiers = "w600dp-h800dp-420dpi")
    @Test
    fun timelineLabelsContainSafeTitleAndTimeAndOverlayMirrorsCanvasInRtl() {
        val dates = (0L..6L).map(selectedDate::plusDays)
        val event = eventFixture(
            id = 7,
            eventType = EventType.TIMESPAN,
            title = "Design review",
            startTime = selectedDate.plusDays(3).atTime(1, 15),
            endTime = selectedDate.plusDays(3).atTime(2, 45),
            syncUid = "00000000000000000000000000000007"
        )
        val layout = CalendarLayoutEngine.layout(
            listOf(event),
            CalendarLayoutConfig(
                rangeStart = selectedDate,
                rangeEndExclusive = selectedDate.plusDays(7),
                perHourLogicalHeight = 60f,
                columnWidth = 48f,
                minimumVisualHeight = 12f,
                minimumAccessibleLaneWidth = 48f
            )
        )
        val block = layout.blocks.single()
        val labels = mapOf(7L to "Design review\n1:15 AM – 2:45 AM")
        setTimelineContent(
            TimelineRenderCase(
                dates,
                layout,
                labels,
                LayoutDirection.Rtl,
                widthDp = 600,
                heightDp = 480
            )
        )

        assertTimelineOverlay(block, dates)
    }

    private fun assertTimelineOverlay(block: CalendarBlock, dates: List<LocalDate>) {
        val visual = composeRule.onNodeWithTag("calendar_timeline_visual_layer")
            .fetchSemanticsNode().boundsInRoot
        val overlay = composeRule.onNodeWithTag(
            "calendar_event_${block.column}_${block.id}",
            useUnmergedTree = true
        ).assertHasClickAction().fetchSemanticsNode()
        val description = overlay.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()
        assertTrue(
            "Timeline semantics must include the safe title",
            description.contains("Design review")
        )
        assertTrue("Timeline semantics must include the safe time", description.contains("1:15"))
        val minimum = 48f * composeHost.activity.resources.displayMetrics.density
        assertTrue(
            "Timeline overlay must remain at least 48dp wide",
            overlay.boundsInRoot.width >= minimum
        )
        assertTrue(
            "Timeline overlay must remain at least 48dp high",
            overlay.boundsInRoot.height >= minimum
        )
        val columnWidth = visual.width / dates.size
        val expectedRtlLeft = visual.right - columnWidth * (block.column + 1)
        assertTrue(
            "RTL semantic overlay must mirror the Canvas column",
            kotlin.math.abs(overlay.boundsInRoot.left - expectedRtlLeft) <= 1.5f
        )
        assertTrue(
            "Overlay must be within Canvas horizontally",
            overlay.boundsInRoot.left >= visual.left
        )
        assertTrue(
            "Overlay must be within Canvas horizontally",
            overlay.boundsInRoot.right <= visual.right
        )
    }

    @Test
    fun timelineScrollsToLateHoursAndPreservesCanvasOverlayGeometry() {
        val dates = listOf(selectedDate)
        val event = eventFixture(
            id = 8,
            title = "Late appointment",
            startTime = selectedDate.atTime(23, 30),
            estimatedDurationMinutes = 20,
            syncUid = "00000000000000000000000000000008"
        )
        val layout = CalendarLayoutEngine.layout(
            listOf(event),
            CalendarLayoutConfig(
                rangeStart = selectedDate,
                rangeEndExclusive = selectedDate.plusDays(1),
                perHourLogicalHeight = 60f,
                columnWidth = 304f,
                minimumVisualHeight = 12f,
                minimumAccessibleLaneWidth = 48f
            )
        )
        setTimelineContent(
            TimelineRenderCase(
                dates,
                layout,
                mapOf(8L to "Late appointment 23:30"),
                LayoutDirection.Ltr,
                widthDp = 360,
                heightDp = 320
            )
        )
        val hour = composeRule.onNodeWithTag("calendar_timeline_hour_23")
        hour.performScrollTo().assertIsDisplayed()
        val overlay = composeRule.onNodeWithTag("calendar_event_${layout.blocks.single().column}_8")
            .performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        assertTrue(
            "Late event must be reachable after vertical scrolling",
            overlay.boundsInRoot.bottom > 0f
        )
        val visual = composeRule.onNodeWithTag("calendar_timeline_visual_layer")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Scrolled overlay must still share the Canvas horizontal extent",
            overlay.boundsInRoot.left >= visual.left
        )
        assertTrue(
            "Scrolled overlay must still share the Canvas horizontal extent",
            overlay.boundsInRoot.right <= visual.right
        )
    }

    private fun setCalendarContent(viewport: ViewportCase, fontScale: Float) {
        setTestContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides viewport.direction,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                ClenderTheme(
                    AppearanceUiState(
                        themeMode = ThemeMode.SYSTEM,
                        appFontSizeSp = viewport.fontSizeSp,
                        widgetFontSizeSp = 13
                    )
                ) {
                    Box(
                        Modifier.size(
                            viewport.widthDp.dp,
                            viewport.heightDp.dp
                        ).testTag(VIEWPORT_TAG)
                    ) {
                        CalendarScreen(
                            model = calendarModel(Locale.US),
                            actions = CalendarScreenActions({}, {}, {}, {})
                        )
                    }
                }
            }
        }
    }

    private fun setMonthContent(renderCase: MonthRenderCase) {
        setTestContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides renderCase.direction,
                LocalDensity provides Density(LocalDensity.current.density, renderCase.fontScale)
            ) {
                ClenderTheme(AppearanceUiState(ThemeMode.SYSTEM, renderCase.fontSizeSp, 13)) {
                    Box(Modifier.size(renderCase.widthDp.dp, renderCase.heightDp.dp)) {
                        Box(Modifier.verticalScroll(rememberScrollState())) {
                            MonthCalendar(
                                model = MonthCalendarModel(
                                    selectedDate = selectedDate,
                                    today = selectedDate.plusDays(2),
                                    locale = renderCase.locale,
                                    eventCounts = emptyMap()
                                ),
                                onDateSelected = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setTimelineContent(renderCase: TimelineRenderCase) {
        setTestContent {
            CompositionLocalProvider(LocalLayoutDirection provides renderCase.direction) {
                ClenderTheme(AppearanceUiState(ThemeMode.SYSTEM, 13, 13)) {
                    Box(Modifier.size(renderCase.widthDp.dp, renderCase.heightDp.dp)) {
                        CalendarTimeline(
                            model = CalendarTimelineModel(
                                renderCase.dates,
                                renderCase.layout,
                                renderCase.labels
                            ),
                            onEventClick = {},
                            onOverflowClick = {}
                        )
                    }
                }
            }
        }
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread { composeHost.activity.setContent(content = content) }
        composeRule.waitForIdle()
    }

    private fun calendarModel(locale: Locale): CalendarScreenModel = CalendarScreenModel(
        state = CalendarScreenState(
            mode = CalendarMode.MONTH,
            selectedDate = selectedDate,
            queryRange = CalendarRangePolicy.queryRange(selectedDate, CalendarMode.MONTH, locale),
            loadStatus = CalendarLoadStatus.CONTENT
        ),
        locale = locale,
        today = selectedDate.plusDays(2)
    )

    private fun assertMinimumTouchTarget(width: Float, height: Float) {
        val minimum = 48f * composeHost.activity.resources.displayMetrics.density
        assertTrue("Expected width >= 48dp, got $width", width >= minimum)
        assertTrue("Expected height >= 48dp, got $height", height >= minimum)
    }

    private data class ViewportCase(
        val widthDp: Int,
        val heightDp: Int,
        val direction: LayoutDirection,
        val fontSizeSp: Int
    )

    private data class MonthRenderCase(
        val locale: Locale,
        val direction: LayoutDirection,
        val widthDp: Int,
        val heightDp: Int,
        val fontSizeSp: Int,
        val fontScale: Float
    )

    private data class TimelineRenderCase(
        val dates: List<LocalDate>,
        val layout: com.molotov.clender.domain.calendar.CalendarLayoutResult,
        val labels: Map<Long, String>,
        val direction: LayoutDirection,
        val widthDp: Int,
        val heightDp: Int
    )

    private companion object {
        const val VIEWPORT_TAG = "calendar_c3_viewport"
        val ACTION_TAGS = listOf(
            "calendar_mode_month",
            "calendar_mode_week",
            "calendar_mode_day",
            "calendar_previous_range",
            "calendar_today",
            "calendar_next_range"
        )
    }
}
