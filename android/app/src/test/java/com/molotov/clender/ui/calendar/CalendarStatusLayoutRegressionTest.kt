package com.molotov.clender.ui.calendar

import android.app.Application
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
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
@Config(sdk = [26, 36], application = Application::class, qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarStatusLayoutRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()
    private val date = LocalDate.of(2026, 9, 6)
    private var retries = 0

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun monthEmptyKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.MONTH, CalendarLoadStatus.EMPTY)

    @Test
    fun monthLoadingKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.MONTH, CalendarLoadStatus.LOADING)

    @Test
    fun monthErrorKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.MONTH, CalendarLoadStatus.ERROR)

    @Test
    fun monthContentKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.MONTH, CalendarLoadStatus.CONTENT)

    @Test
    fun weekEmptyKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.WEEK, CalendarLoadStatus.EMPTY)

    @Test
    fun weekLoadingKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.WEEK, CalendarLoadStatus.LOADING)

    @Test
    fun weekErrorKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.WEEK, CalendarLoadStatus.ERROR)

    @Test
    fun weekContentKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.WEEK, CalendarLoadStatus.CONTENT)

    @Test
    fun dayEmptyKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.DAY, CalendarLoadStatus.EMPTY)

    @Test
    fun dayLoadingKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.DAY, CalendarLoadStatus.LOADING)

    @Test
    fun dayErrorKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.DAY, CalendarLoadStatus.ERROR)

    @Test
    fun dayContentKeepsStatusAndCalendarGeometrySeparate() =
        verifyMatrix(CalendarMode.DAY, CalendarLoadStatus.CONTENT)

    private fun verifyMatrix(mode: CalendarMode, status: CalendarLoadStatus) {
        val variants = listOf(
            Triple(320, 8, 1f),
            Triple(320, 8, 2f),
            Triple(320, 20, 1f),
            Triple(320, 20, 2f),
            Triple(360, 8, 1f),
            Triple(360, 8, 2f),
            Triple(360, 20, 1f),
            Triple(360, 20, 2f)
        )
        variants.forEach { (width, font, scale) ->
            val locale = if (scale == 1f) Locale.US else Locale.SIMPLIFIED_CHINESE
            val theme = if (width == 320) ThemeMode.LIGHT else ThemeMode.DARK
            render(mode, status, LayoutCase(width, font, scale, locale, theme))
            verifyStatusLanguage(status, locale)
            verifyGeometry(mode, status, "$mode/$status/$width/$font/$scale/$locale")
        }
    }

    private fun render(mode: CalendarMode, status: CalendarLoadStatus, layout: LayoutCase) {
        val range = CalendarRangePolicy.queryRange(date, mode, layout.locale)
        val events = if (status == CalendarLoadStatus.CONTENT) {
            listOf(eventFixture(id = 7, title = "Layout fixture", startTime = date.atTime(0, 30)))
        } else {
            emptyList()
        }
        val state = CalendarScreenState(
            mode,
            date,
            range,
            events,
            status,
            if (status == CalendarLoadStatus.ERROR) CalendarErrorCode.LOAD_FAILED else null
        )
        composeRule.runOnUiThread {
            val configuration = Configuration(host.activity.resources.configuration).apply {
                setLocale(layout.locale)
                fontScale = layout.scale
            }
            val localizedContext = host.activity.createConfigurationContext(configuration)
            host.activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(
                        host.activity.resources.displayMetrics.density,
                        layout.scale
                    )
                ) {
                    ClenderTheme(AppearanceUiState(layout.theme, layout.font, 13)) {
                        Box(Modifier.size(layout.width.dp, 900.dp).testTag("layout_viewport")) {
                            CalendarScreen(
                                CalendarScreenModel(state, layout.locale, date),
                                CalendarScreenActions({}, {}, { retries++ }, {})
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun verifyStatusLanguage(status: CalendarLoadStatus, locale: Locale) {
        val chinese = locale.language == Locale.CHINESE.language
        val expected = when (status) {
            CalendarLoadStatus.EMPTY ->
                if (chinese) "此范围内没有事项" else "No events in this range"

            CalendarLoadStatus.LOADING ->
                if (chinese) "正在加载日历" else "Loading calendar"

            CalendarLoadStatus.ERROR ->
                if (chinese) "无法加载日历" else "Could not load calendar"

            CalendarLoadStatus.CONTENT -> return
        }
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun verifyGeometry(mode: CalendarMode, status: CalendarLoadStatus, label: String) {
        val statusTag = when (status) {
            CalendarLoadStatus.EMPTY -> "calendar_empty"
            CalendarLoadStatus.LOADING -> "calendar_loading"
            CalendarLoadStatus.ERROR -> "calendar_error"
            CalendarLoadStatus.CONTENT -> null
        }
        val calendarNodes = composeRule.onAllNodes(
            SemanticsMatcher("Calendar headers and body") { node ->
                val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                tag.startsWith("calendar_month_weekday_") ||
                    tag.startsWith("calendar_month_day_") ||
                    tag.startsWith("calendar_timeline_column_") ||
                    tag == "calendar_timeline_visual_layer"
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes()
        if (status == CalendarLoadStatus.ERROR) {
            assertTrue("$label must not show stale calendar beneath error", calendarNodes.isEmpty())
            val before = retries
            composeRule.onNodeWithTag("calendar_retry").assertIsDisplayed().performClick()
            assertEquals(before + 1, retries)
        } else {
            val header = if (mode == CalendarMode.MONTH) {
                "calendar_month_weekday_0"
            } else {
                "calendar_timeline_column_0"
            }
            composeRule.onNodeWithTag(header, useUnmergedTree = true).assertIsDisplayed()
            assertTrue("$label requires actual calendar geometry", calendarNodes.isNotEmpty())
        }
        if (statusTag != null) {
            verifyStatusBounds(statusTag, calendarNodes.map { it.boundsInRoot }, label)
        } else {
            listOf("calendar_empty", "calendar_loading", "calendar_error").forEach { tag ->
                composeRule.onNodeWithTag(tag).assertDoesNotExist()
            }
        }
    }

    private fun verifyStatusBounds(statusTag: String, calendarBounds: List<Rect>, label: String) {
        val viewport = composeRule.onNodeWithTag("layout_viewport")
            .fetchSemanticsNode().boundsInRoot
        val bounds = composeRule.onNodeWithTag(statusTag).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label status must be inside viewport: $bounds / $viewport",
            bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
        )
        calendarBounds.forEach { content ->
            assertFalse(
                "$label status overlaps calendar: $bounds / $content",
                intersects(bounds, content)
            )
        }
    }

    private fun intersects(first: Rect, second: Rect): Boolean =
        first.width > 0 && first.height > 0 && second.width > 0 && second.height > 0 &&
            first.left < second.right && second.left < first.right &&
            first.top < second.bottom && second.top < first.bottom

    private data class LayoutCase(
        val width: Int,
        val font: Int,
        val scale: Float,
        val locale: Locale,
        val theme: ThemeMode
    )
}
