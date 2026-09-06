package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDateTime
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
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventC3AdaptiveAccessibilityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = com.molotov.clender.testsupport.RobolectricComposeHost()

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    @Test
    fun eventListScrollsToTheLastItemAndKeepsClickableBoundsAcrossAllWidthBreakpoints() {
        val events = (1L..20L).map { id ->
            eventListFixture(
                id = id,
                startTime = LocalDateTime.of(2026, 8, 7, 8, 0).plusMinutes(id),
                title = "事项 $id " + "很长的标题 ".repeat(4)
            )
        }
        val state = EventListPresenter.present(
            events,
            java.util.Locale.SIMPLIFIED_CHINESE,
            EventListLabels("提醒", "时间段")
        )
        listOf(
            RenderCase(360, 640, LayoutDirection.Ltr, 8),
            RenderCase(599, 360, LayoutDirection.Rtl, 20),
            RenderCase(600, 800, LayoutDirection.Ltr, 20),
            RenderCase(839, 480, LayoutDirection.Rtl, 8),
            RenderCase(840, 900, LayoutDirection.Ltr, 20)
        ).forEach { renderCase ->
            setEventListContent(state, renderCase, fontScale = 2f)
            val list = composeRule.onNode(hasScrollAction()).assertIsDisplayed()
            list.performScrollToNode(hasTestTag("event_list_item_20"))
            val item = composeRule.onNodeWithTag("event_list_item_20")
                .assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
            assertMinimumTouchTarget(item.boundsInRoot.width, item.boundsInRoot.height)
            assertTrue(
                "Last item is outside the viewport",
                item.boundsInRoot.bottom <= renderCase.heightDp * density()
            )
            assertTrue("Last item is outside the viewport", item.boundsInRoot.top >= 0f)
        }
    }

    @Test
    fun detailActionsRemainReachableAfterScrollingLongContentAtLargeFontAndBothDirections() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            setDetailContent(
                event = detailEvent(description = "详细说明 ".repeat(180)),
                renderCase = RenderCase(360, 360, direction, 20),
                fontScale = 2f
            )
            val content = composeRule.onNodeWithTag("event_detail_content").assertIsDisplayed()
            content.performScrollToNode(hasTestTag("event_detail_delete"))
            listOf("event_detail_edit", "event_detail_delete").forEach { tag ->
                val node = composeRule.onNodeWithTag(tag).assertIsDisplayed()
                    .assertHasClickAction().fetchSemanticsNode()
                assertMinimumTouchTarget(node.boundsInRoot.width, node.boundsInRoot.height)
                assertTrue("$tag must fit the narrow viewport", node.boundsInRoot.left >= 0f)
                assertTrue(
                    "$tag must fit the narrow viewport",
                    node.boundsInRoot.right <= 360f * density()
                )
            }
        }
    }

    @Test
    fun editorAllFieldsPickersAndSaveStayReachableWhenTheFormScrollsAtFiveWidthBreakpoints() {
        val tags = listOf(
            "event_editor_type_reminder",
            "event_editor_type_timespan",
            "event_editor_title",
            "event_editor_start_date",
            "event_editor_start_time",
            "event_editor_end_date",
            "event_editor_end_time",
            "event_editor_description",
            "event_editor_duration",
            "event_editor_save"
        )
        listOf(360, 599, 600, 839, 840).forEachIndexed { index, width ->
            setEditorContent(
                form = timespanForm(description = "说明 ".repeat(80)),
                renderCase = RenderCase(
                    width,
                    if (index % 2 == 0) 640 else 360,
                    if (index % 2 == 0) LayoutDirection.Ltr else LayoutDirection.Rtl,
                    if (index % 2 == 0) 8 else 20
                ),
                fontScale = 2f
            )
            val scroll = composeRule.onNodeWithTag("event_editor_scroll").assertIsDisplayed()
            tags.forEach { tag ->
                scroll.performScrollToNode(hasTestTag(tag))
                val node = composeRule.onNodeWithTag(tag).assertIsDisplayed()
                    .assertHasClickActionIfPresent().fetchSemanticsNode()
                assertMinimumTouchTarget(node.boundsInRoot.width, node.boundsInRoot.height)
                assertTrue("$tag is clipped at ${width}dp", node.boundsInRoot.left >= 0f)
                assertTrue(
                    "$tag is clipped at ${width}dp",
                    node.boundsInRoot.right <= width * density()
                )
            }
        }
    }

    @Test
    fun pickerAndConfirmationDialogActionsHaveRealLargeFontTouchBoundsAndInvokeCallbacks() {
        var dateDismissals = 0
        setDialogContent {
            EventDatePickerDialog(
                selectedDate = LocalDateTime.of(2026, 8, 31, 9, 0).toLocalDate(),
                onDateSelected = {},
                onDismiss = { dateDismissals += 1 }
            )
        }
        listOf("event_date_picker_cancel", "event_date_picker_confirm").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag).assertIsDisplayed()
                .assertHasClickAction().fetchSemanticsNode()
            assertMinimumTouchTarget(node.boundsInRoot.width, node.boundsInRoot.height)
        }
        composeRule.onNodeWithTag("event_date_picker_cancel").performClick()
        composeRule.runOnIdle { assertEquals(1, dateDismissals) }

        var deleteDismissals = 0
        setDialogContent {
            DeleteConfirmationDialog(
                deleting = false,
                error = null,
                onConfirm = {},
                onDismiss = { deleteDismissals += 1 }
            )
        }
        listOf("event_delete_cancel", "event_delete_confirm").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag).assertIsDisplayed()
                .assertHasClickAction().fetchSemanticsNode()
            assertMinimumTouchTarget(node.boundsInRoot.width, node.boundsInRoot.height)
        }
        composeRule.onNodeWithTag("event_delete_cancel").performClick()
        composeRule.runOnIdle { assertEquals(1, deleteDismissals) }
    }

    private fun setEventListContent(
        state: EventListUiState.Content,
        renderCase: RenderCase,
        fontScale: Float
    ) {
        setTestContent {
            Render(renderCase, fontScale) {
                EventListScreen(state, onRetry = {}, onNavigate = {})
            }
        }
    }

    private fun setDetailContent(event: Event, renderCase: RenderCase, fontScale: Float) {
        setTestContent {
            Render(renderCase, fontScale) {
                EventDetailScreen(
                    state = EventCrudUiState(
                        loadStatus = EventCrudLoadStatus.CONTENT,
                        event = event
                    ),
                    onRetry = {},
                    onEdit = {},
                    onRequestDelete = {}
                )
            }
        }
    }

    private fun setEditorContent(form: EventFormState, renderCase: RenderCase, fontScale: Float) {
        setTestContent {
            Render(renderCase, fontScale) {
                EventEditorScreen(
                    state = EventCrudUiState(loadStatus = EventCrudLoadStatus.CONTENT, form = form),
                    onFormChange = {},
                    onSave = {}
                )
            }
        }
    }

    private fun setDialogContent(content: @Composable () -> Unit) {
        setTestContent {
            Render(RenderCase(360, 640, LayoutDirection.Rtl, 20), fontScale = 2f, content = content)
        }
    }

    @Composable
    private fun Render(renderCase: RenderCase, fontScale: Float, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalLayoutDirection provides renderCase.direction,
            LocalDensity provides Density(LocalDensity.current.density, fontScale)
        ) {
            ClenderTheme(AppearanceUiState(ThemeMode.SYSTEM, renderCase.fontSizeSp, 13)) {
                Box(Modifier.size(renderCase.widthDp.dp, renderCase.heightDp.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalInspectionMode provides false
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread { composeHost.activity.setContent(content = content) }
        composeRule.waitForIdle()
    }

    private fun timespanForm(description: String): EventFormState = EventFormState(
        eventType = EventType.TIMESPAN,
        title = "跨日事项",
        startTime = LocalDateTime.of(2026, 8, 31, 9, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 10, 0),
        description = description,
        estimatedDurationInput = "20"
    )

    private fun detailEvent(description: String): Event = eventFixture(
        id = 17,
        eventType = EventType.TIMESPAN,
        title = "详情事项",
        startTime = LocalDateTime.of(2026, 8, 31, 9, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 10, 0),
        description = description,
        estimatedDurationMinutes = 20,
        syncUid = "00000000000000000000000000000017"
    )

    private fun assertMinimumTouchTarget(width: Float, height: Float) {
        val minimum = 48f * density()
        assertTrue("Expected width >= 48dp, got $width", width >= minimum)
        assertTrue("Expected height >= 48dp, got $height", height >= minimum)
    }

    private fun density(): Float = composeHost.activity.resources.displayMetrics.density

    private data class RenderCase(
        val widthDp: Int,
        val heightDp: Int,
        val direction: LayoutDirection,
        val fontSizeSp: Int
    )
}

private fun SemanticsNodeInteraction.assertHasClickActionIfPresent(): SemanticsNodeInteraction =
    if (fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) {
        assertHasClickAction()
    } else {
        this
    }
