package com.molotov.clender.ui.calendar

import android.app.Application
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.ui.theme.ClenderTheme
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
@Config(sdk = [26, 36], application = Application::class, qualifiers = "w840dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarViewportAccessibilityRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()
    private val date = LocalDate.of(2026, 9, 7)
    private var navigation: AppRoute.EventDetail? = null
    private var retries = 0

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun shortViewportCanReachRealHoursAndGridAcrossCalendarStates() {
        cases().forEach { viewport ->
            listOf(CalendarMode.WEEK, CalendarMode.DAY).forEach { mode ->
                listOf(
                    CalendarLoadStatus.EMPTY,
                    CalendarLoadStatus.CONTENT,
                    CalendarLoadStatus.LOADING
                ).forEach { status ->
                    render(viewport, mode, status)
                    composeRule.onNodeWithTag("accessibility_viewport")
                        .performTouchInput { swipeUp() }
                    assertHourReachable()
                }
            }
        }
    }

    @Test
    fun shortViewportCanScrollToAnEventAndNavigateByActualTouch() {
        cases().forEach { viewport ->
            listOf(CalendarMode.WEEK, CalendarMode.DAY).forEach { mode ->
                render(viewport, mode, CalendarLoadStatus.CONTENT)
                composeRule.onNodeWithTag("accessibility_viewport")
                    .performTouchInput { swipeUp() }
                assertHourReachable()
                val column = CalendarRangePolicy.queryRange(date, mode, viewport.locale)
                    .dates.indexOf(date)
                composeRule.onNodeWithTag("calendar_event_${column}_7")
                    .performScrollTo().assertIsDisplayed().performTouchInput { click() }
                composeRule.waitForIdle()
                assertEquals(AppRoute.EventDetail(7), navigation)
            }
        }
    }

    @Test
    fun shortLandscapeRevealsTimelineUsingOnlyAnUpwardGesture() {
        render(Viewport(840, 320, Locale.US), CalendarMode.WEEK, CalendarLoadStatus.EMPTY)
        val before = composeRule.onNodeWithTag("calendar_range_title")
            .fetchSemanticsNode().positionInRoot.y
        composeRule.onNodeWithTag("accessibility_viewport").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val hours = composeRule.onAllNodes(
            SemanticsMatcher("Hour labels") {
                it.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                    .startsWith("calendar_timeline_hour_")
            }
        ).fetchSemanticsNodes()
        assertTrue(
            "The actual gesture must reveal hour labels; ${diagnostics()}",
            hours.any { it.boundsInRoot.height > 0f }
        )
        val grid = composeRule.onNodeWithTag("calendar_timeline_visual_layer")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("At least one hour of the grid is visible: $grid", grid.height >= 59f)
        repeat(5) {
            composeRule.onNodeWithTag("accessibility_viewport").performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        val restored = composeRule.onNodeWithTag("calendar_range_title")
            .fetchSemanticsNode().positionInRoot.y
        assertEquals("Downward gestures restore the toolbar", before, restored, 1f)
    }

    @Test
    fun normalHeightKeepsHeaderAndPositiveTimelineBelowToolbar() {
        listOf(Viewport(840, 900, Locale.US), Viewport(840, 900, Locale.US, 13, 1f)).forEach {
            render(it, CalendarMode.WEEK, CalendarLoadStatus.CONTENT)
            assertNormalGeometry()
        }
    }

    @Test
    fun shortErrorViewportKeepsRetryReachableByTouch() {
        render(Viewport(320, 320, Locale.US), CalendarMode.WEEK, CalendarLoadStatus.ERROR)
        val retry = composeRule.onNodeWithTag("calendar_retry")
        val geometry = retry.fetchSemanticsNode()
        if (geometry.boundsInRoot.height < geometry.size.height) retry.performScrollTo()
        retry.assertIsDisplayed().performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals(1, retries)
    }

    private fun assertNormalGeometry() {
        val toolbar = composeRule.onNodeWithTag("calendar_mode_week")
            .fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag("calendar_timeline_column_0")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val hour = composeRule.onNodeWithTag("calendar_timeline_hour_0")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(header.top >= toolbar.bottom)
        assertTrue(hour.top >= header.bottom)
        assertEquals(60f, hour.height, 1f)
    }

    private fun assertHourReachable() {
        val hour = composeRule.onNodeWithTag("calendar_timeline_hour_0")
            .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val grid = composeRule.onNodeWithTag("calendar_timeline_visual_layer")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "A complete first hour must remain reachable: $hour; ${diagnostics()}",
            hour.height >= 59f
        )
        assertTrue("Grid needs a positive visible body: $grid", grid.width > 0 && grid.height > 0)
    }

    private fun diagnostics(): String = composeRule.onAllNodes(
        SemanticsMatcher("Geometry diagnostics") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            tag in listOf(
                "calendar_range_title", "calendar_mode_week", "calendar_empty",
                "calendar_loading", "calendar_timeline_column_0", "calendar_timeline_hour_0",
                "calendar_timeline_visual_layer", "accessibility_viewport", "calendar_error"
            ) || node.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        },
        useUnmergedTree = true
    ).fetchSemanticsNodes().joinToString { node ->
        val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        "$tag size=${node.size} pos=${node.positionInRoot} clip=${node.boundsInRoot} " +
            "scroll=${range?.value?.invoke()}/${range?.maxValue?.invoke()} " +
            "action=${node.config.contains(SemanticsActions.ScrollBy)}"
    }

    private fun cases(): List<Viewport> = listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).flatMap {
        listOf(Viewport(840, 320, it), Viewport(320, 480, it))
    }

    private fun render(viewport: Viewport, mode: CalendarMode, status: CalendarLoadStatus) {
        navigation = null
        val state = CalendarScreenState(
            mode,
            date,
            CalendarRangePolicy.queryRange(date, mode, viewport.locale),
            events = if (status == CalendarLoadStatus.CONTENT) {
                listOf(
                    eventFixture(id = 7, title = "Reachable event", startTime = date.atTime(0, 15))
                )
            } else {
                emptyList()
            },
            loadStatus = status
        )
        composeRule.runOnUiThread {
            val configuration = Configuration(host.activity.resources.configuration).apply {
                setLocale(viewport.locale)
                fontScale = viewport.scale
            }
            val context = host.activity.createConfigurationContext(configuration)
            host.activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(1f, viewport.scale)
                ) {
                    ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, viewport.font, 13)) {
                        Box(
                            Modifier.size(viewport.width.dp, viewport.height.dp)
                                .testTag("accessibility_viewport")
                        ) {
                            CalendarScreen(
                                CalendarScreenModel(state, viewport.locale, date),
                                CalendarScreenActions({}, {}, { retries++ }, { navigation = it })
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private data class Viewport(
        val width: Int,
        val height: Int,
        val locale: Locale,
        val font: Int = 20,
        val scale: Float = 2f
    )
}
