package com.molotov.clender.ui.calendar

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.calendar.CalendarBlock
import com.molotov.clender.domain.calendar.CalendarLayoutConfig
import com.molotov.clender.domain.calendar.CalendarLayoutEngine
import com.molotov.clender.domain.calendar.CalendarLayoutResult
import com.molotov.clender.testsupport.RobolectricComposeHost
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CalendarEventBlockTest {
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
    private val start = day.atStartOfDay()

    @Test
    fun markerAndRegularBlocksExposeDistinctNonColorStateAndFortyEightDpTarget() {
        val marker = layout(listOf(event(1, start.plusHours(9)))).blocks.single()
        val duration = layout(
            listOf(event(2, start.plusHours(10), estimatedMinutes = 30))
        ).blocks.single()

        var clicked: Long? = null
        setTestContent {
            Column {
                TimelineEventBlock(
                    marker,
                    onEventClick = { eventId: Long -> clicked = eventId },
                    onOverflowClick = {}
                )
                TimelineEventBlock(
                    duration,
                    onEventClick = { eventId: Long -> clicked = eventId },
                    onOverflowClick = {}
                )
            }
        }

        val markerNode = composeRule.onNodeWithTag(eventTag(marker)).assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val durationNode = composeRule.onNodeWithTag(eventTag(duration)).assertHasClickAction()
        assertNotEquals(stateDescription(markerNode), stateDescription(durationNode))

        markerNode.performClick()
        composeRule.waitForIdle()
        assertEquals(1L, clicked)
    }

    @Test
    fun laneClusterAndOverlapAreAnnouncedWithoutDependingOnColor() {
        val result = layout(
            listOf(
                span(1, 9, 0, 12, 0),
                span(2, 9, 15, 11, 0),
                span(3, 9, 30, 10, 30),
                span(4, 12, 0, 13, 0)
            )
        )
        val overlapped = result.blocks.filter { it.overlapRanges.isNotEmpty() }
        val adjacent = result.blocks.single { it.id == 4L }

        setTestContent {
            result.blocks.forEach { block ->
                TimelineEventBlock(block, onEventClick = {}, onOverflowClick = {})
            }
        }

        assertEquals(3, overlapped.size)
        assertEquals(setOf(0, 1, 2), overlapped.map(CalendarBlock::lane).toSet())
        val overlapStates = overlapped.map { block ->
            stateDescription(composeRule.onNodeWithTag(eventTag(block)))
        }
        assertTrue(overlapStates.all(String::isNotBlank))
        assertEquals(overlapStates.size, overlapStates.distinct().size)
        assertNotEquals(
            overlapStates.first(),
            stateDescription(composeRule.onNodeWithTag(eventTag(adjacent)))
        )
    }

    @Test
    fun narrowOverflowUsesEngineIdsAndSeparateTemporalGroups() {
        val result = layout(
            events = listOf(
                span(1, 8, 59, 12, 0),
                span(2, 9, 0, 10, 0),
                span(3, 10, 0, 12, 0),
                span(4, 9, 30, 9, 45)
            ),
            columnWidth = 96f
        )
        val overflows = result.blocks.filter(CalendarBlock::overflow)
        val opened = mutableListOf<List<Long>>()

        setTestContent {
            Column {
                overflows.forEach { block ->
                    TimelineEventBlock(
                        block,
                        onEventClick = {},
                        onOverflowClick = { eventIds: List<Long> -> opened += eventIds }
                    )
                }
            }
        }

        assertEquals(listOf(9 * 60, 10 * 60), overflows.map(CalendarBlock::topMinute))
        overflows.forEach { block ->
            composeRule.onNodeWithTag(overflowTag(block))
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(listOf(2L, 4L), listOf(3L)), opened)
    }

    @Test
    fun sanitizedNoticeIsGenericAndNeverLeaksInvalidEventTitle() {
        val secretTitle = "LEAK_THIS_INVALID_EVENT_TITLE"
        val invalid = event(1, start.plusHours(9)).copy(id = 0, title = secretTitle)
        val result = layout(listOf(invalid))

        setTestContent {
            CalendarTimeline(
                dates = listOf(day),
                layoutResult = result,
                onEventClick = {},
                onOverflowClick = {}
            )
        }

        assertEquals(1, result.sanitizedCount)
        composeRule.onNodeWithTag("calendar_sanitized_notice").assertExists()
        composeRule.onNodeWithText(secretTitle).assertDoesNotExist()
        val description = composeRule.onNodeWithTag("calendar_sanitized_notice")
            .fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            .orEmpty()
        assertTrue(description.isNotBlank())
        assertFalse(description.contains(secretTitle))
    }

    @Test
    fun providedSingleEventLabelIsVisibleButOverflowDoesNotExposeMemberLabels() {
        val regular = layout(listOf(event(1, start.plusHours(8), estimatedMinutes = 30)))
            .blocks.single()
        val overflow = layout(
            events = listOf(
                span(2, 9, 0, 10, 0),
                span(3, 9, 0, 10, 0),
                span(4, 9, 0, 10, 0)
            ),
            columnWidth = 96f
        ).blocks.single(CalendarBlock::overflow)
        val labels = mapOf(
            1L to "Visible event label",
            2L to "Overflow member must stay hidden",
            3L to "Second hidden overflow member",
            4L to "Third hidden overflow member"
        )

        setTestContent {
            Column {
                TimelineEventBlock(regular, {}, {}, eventLabels = labels)
                TimelineEventBlock(overflow, {}, {}, eventLabels = labels)
            }
        }

        composeRule.onNodeWithText("Visible event label").assertExists()
        labels.filterKeys { it != 1L }.values.forEach { hiddenLabel ->
            composeRule.onNodeWithText(hiddenLabel).assertDoesNotExist()
        }
    }

    private fun stateDescription(node: androidx.compose.ui.test.SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
            .orEmpty()

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent(content = content)
        }
        composeRule.waitForIdle()
    }

    private fun layout(events: List<Event>, columnWidth: Float = 480f): CalendarLayoutResult =
        CalendarLayoutEngine.layout(
            events,
            CalendarLayoutConfig(
                rangeStart = day,
                rangeEndExclusive = day.plusDays(1),
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
        syncUid = id.coerceAtLeast(1).toString(16).padStart(32, '0')
    )

    private fun span(
        id: Long,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): Event = event(
        id = id,
        startTime = start.plusHours(startHour.toLong()).plusMinutes(startMinute.toLong()),
        type = EventType.TIMESPAN,
        endTime = start.plusHours(endHour.toLong()).plusMinutes(endMinute.toLong())
    )

    private fun eventTag(block: CalendarBlock): String =
        "calendar_event_${block.column}_${block.id}"

    private fun overflowTag(block: CalendarBlock): String =
        "calendar_overflow_${block.column}_${block.topMinute}"
}
