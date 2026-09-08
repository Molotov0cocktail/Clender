package com.molotov.clender.ui.calendar

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.calendar.CalendarBlock
import com.molotov.clender.domain.calendar.CalendarLayoutConfig
import com.molotov.clender.domain.calendar.CalendarLayoutEngine
import com.molotov.clender.domain.calendar.CalendarLayoutResult
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
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
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarBlockReadabilityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun adjacentEventsHaveDistinctOpaqueColorsInBothThemes() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            render(theme)
            val pixels = visualPixels()
            assertEquals(
                "The captured event fill must be opaque",
                255,
                android.graphics.Color.alpha(pixels.getPixel(80, 30))
            )
            assertNotEquals(
                "Adjacent events need distinct colors",
                pixels.getPixel(80, 30),
                pixels.getPixel(80, 90)
            )
        }
    }

    @Test
    fun adjacentEventsHaveAVisibleGapAtTheActualTimeBoundary() {
        render(ThemeMode.LIGHT)
        val pixels = visualPixels()
        assertNotEquals(
            "The preceding event must end before the boundary",
            pixels.getPixel(80, 30),
            pixels.getPixel(80, 59)
        )
        assertNotEquals(
            "The next event must start after the boundary",
            pixels.getPixel(80, 90),
            pixels.getPixel(80, 60)
        )
    }

    @Test
    fun shortBlockDoesNotPaintPartialGlyphsButKeepsFullAccessibleTitleAndAction() {
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.DARK, 20, 13)) {
                    TimelineEventBlock(
                        block(1, 0).copy(heightFraction = 12 / 1440f, durationMinutes = 1),
                        {},
                        {},
                        eventLabels = mapOf(1L to "One minute task")
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("One minute task").assertDoesNotExist()
        composeRule.onNodeWithTag("calendar_event_0_1")
            .assertHasClickAction()
            .assertContentDescriptionContains("One minute task", substring = true)
    }

    @Test
    fun t66ReminderWithZeroEstimatedDurationStillShowsItsTitleAndFullTouchTarget() {
        val event = eventFixture(title = "Reminder", estimatedDurationMinutes = 0)
        val date = event.startTime.toLocalDate()
        val marker = CalendarLayoutEngine.layout(
            listOf(event),
            CalendarLayoutConfig(date, date.plusDays(1), 60f, 240f, 12f, 48f)
        ).blocks.single()
        assertTrue(marker.marker)
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.DARK, 20, 13)) {
                    TimelineEventBlock(marker, {}, {}, eventLabels = mapOf(event.id to event.title))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(event.title).assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_event_0_${event.id}")
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun markerLabelStopsBeforeAdjacentEventWhileKeepingAccessibleTitle() {
        val marker = block(1, 0).copy(marker = true, heightFraction = 1 / 1440f)
        val following = block(2, 10)
        val space = markerLabelSpaceFraction(marker, listOf(marker, following))
        assertEquals(10 / 1440f, space, 0.00001f)
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.DARK, 20, 13)) {
                    TimelineEventBlock(
                        marker,
                        {},
                        {},
                        eventLabels = mapOf(1L to "Nearby reminder"),
                        markerLabelHeight = HOUR_HEIGHT * 24 * space
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nearby reminder").assertDoesNotExist()
        composeRule.onNodeWithTag("calendar_event_0_1")
            .assertHasClickAction()
            .assertContentDescriptionContains("Nearby reminder", substring = true)
        val otherLane = following.copy(lane = 1, laneCount = 2)
        val narrowMarker = marker.copy(laneCount = 2)
        assertEquals(
            1f,
            markerLabelSpaceFraction(narrowMarker, listOf(narrowMarker, otherLane)),
            0f
        )
        assertEquals(
            0f,
            markerLabelSpaceFraction(marker, listOf(marker, following.copy(topFraction = 0f))),
            0f
        )
    }

    private fun visualPixels(): Bitmap {
        val bounds = composeRule.onNodeWithTag("calendar_timeline_visual_layer")
            .fetchSemanticsNode().boundsInWindow
        return composeRule.runOnUiThread {
            val view = host.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            Bitmap.createBitmap(bitmap, bounds.left.toInt(), bounds.top.toInt(), 120, 120)
        }
    }

    private fun render(theme: ThemeMode) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    ClenderTheme(AppearanceUiState(theme, 13, 13)) {
                        Box(Modifier.size(320.dp, 400.dp)) {
                            CalendarTimeline(
                                dates = listOf(LocalDate.of(2026, 9, 8)),
                                layoutResult = CalendarLayoutResult(
                                    listOf(block(1, 0), block(2, 60)),
                                    0
                                ),
                                onEventClick = {},
                                onOverflowClick = {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun block(id: Long, minute: Int) = CalendarBlock(
        id, 0, minute / 1440f, 60 / 1440f, minute, 60, 0, 1, id.toInt(),
        false, emptyList(), listOf(id), false
    )
}
