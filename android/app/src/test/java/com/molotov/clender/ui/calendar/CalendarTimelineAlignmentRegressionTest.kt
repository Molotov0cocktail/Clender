package com.molotov.clender.ui.calendar

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.molotov.clender.domain.calendar.CalendarLayoutResult
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
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
class CalendarTimelineAlignmentRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun dateHeadersAlignWithEveryGridColumnAndWideTimelinesFillTheViewport() {
        listOf(320, 360, 600, 840).forEach { width ->
            listOf(1, 7).forEach { count ->
                listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
                    render(width, count, direction)
                    assertAligned(width, count, direction)
                }
            }
        }
    }

    @Test
    fun narrowWeekHeaderAndGridRemainAlignedAfterHorizontalScrolling() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            render(320, 7, direction)
            composeRule.onNodeWithTag("calendar_timeline_column_6").performScrollTo()
            composeRule.waitForIdle()
            assertAligned(320, 7, direction)
        }
    }

    @Test
    fun maximumFontKeepsHourLabelsOnOneLineAndDateWordsIntact() {
        val cases = listOf(
            Triple(320, Locale.US, 1f),
            Triple(840, Locale.US, 1f),
            Triple(320, Locale.SIMPLIFIED_CHINESE, 1f),
            Triple(840, Locale.SIMPLIFIED_CHINESE, 1f),
            Triple(320, Locale.US, 2.625f)
        )
        cases.forEach { (width, locale, density) ->
            render(
                width,
                7,
                LayoutDirection.Ltr,
                RenderAppearance(locale, maximumFont = true, density = density)
            )
            val hour = textLayout("calendar_timeline_hour_0")
            val header = textLayout("calendar_timeline_column_0")
            val hourGlyphs = hour.layoutInput.text.indices.map(hour::getBoundingBox)
            val hourFits = hour.lineCount == 1 && hourGlyphs.all {
                it.left >= 0f && it.right <= hour.size.width &&
                    it.top >= 0f && it.bottom <= hour.size.height
            }
            val requiredWordWidth = header.multiParagraph.intrinsics.minIntrinsicWidth
            val available = header.layoutInput.constraints.maxWidth
            assertTrue(
                "$width/$locale/$density: hourLines=${hour.lineCount}, hourSize=${hour.size}, " +
                    "hourGlyphs=$hourGlyphs, headerWordWidth=$requiredWordWidth/$available",
                hourFits && requiredWordWidth <= available
            )
        }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(tag)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
        assertEquals(1, layouts.size)
        return layouts.single()
    }

    private fun assertAligned(width: Int, count: Int, direction: LayoutDirection) {
        val grid = composeRule.onNodeWithTag("calendar_timeline_visual_layer")
            .getUnclippedBoundsInRoot()
        val expectedWidth = maxOf(304f, 48f * count, width - 56f)
        val label = "$width/$count/$direction"
        val gridWidth = (grid.right - grid.left).value
        assertEquals("Grid must use available width: $label", expectedWidth, gridWidth, 1f)
        val columnWidth = gridWidth / count
        repeat(count) { index ->
            val header = composeRule.onNodeWithTag("calendar_timeline_column_$index")
                .getUnclippedBoundsInRoot()
            val expectedLeft = if (direction == LayoutDirection.Ltr) {
                grid.left.value + columnWidth * index
            } else {
                grid.right.value - columnWidth * (index + 1)
            }
            assertEquals(
                "Header $index left must match grid: $label",
                expectedLeft,
                header.left.value,
                1f
            )
            assertEquals(
                "Header $index width must match grid: $label",
                columnWidth,
                (header.right - header.left).value,
                1f
            )
        }
    }

    private fun render(
        width: Int,
        count: Int,
        direction: LayoutDirection,
        appearance: RenderAppearance = RenderAppearance()
    ) {
        val first = LocalDate.of(2026, 9, 7)
        val dates = List(count) { first.plusDays(it.toLong()) }
        composeRule.runOnUiThread {
            host.activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        appearance.density,
                        if (appearance.maximumFont) 2f else 1f
                    ),
                    LocalLayoutDirection provides direction
                ) {
                    ClenderTheme(
                        AppearanceUiState(
                            ThemeMode.LIGHT,
                            if (appearance.maximumFont) 20 else 13,
                            13
                        )
                    ) {
                        Box(Modifier.size(width.dp, 640.dp)) {
                            CalendarTimeline(
                                CalendarTimelineModel(
                                    dates,
                                    CalendarLayoutResult(emptyList(), 0),
                                    locale = appearance.locale
                                ),
                                {},
                                {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private data class RenderAppearance(
        val locale: Locale = Locale.US,
        val maximumFont: Boolean = false,
        val density: Float = 1f
    )
}
