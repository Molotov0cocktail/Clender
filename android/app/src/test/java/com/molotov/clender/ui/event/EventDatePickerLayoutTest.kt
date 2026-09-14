package com.molotov.clender.ui.event

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowViewGroup

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class EventDatePickerLayoutTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()
    private var selected: LocalDate? = null
    private var dismissals = 0
    private val showDialog = mutableStateOf(true)
    private lateinit var hostView: View

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun allSevenColumnsAndEverySundayAreVisibleAndActuallySelectable() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            render(theme = theme)
            (7..13).forEach { day -> assertDayFits(day) }
            listOf(6, 13, 20, 27).forEach { day ->
                chooseDay(day)
                confirm()
                assertEquals(LocalDate.of(2026, 9, day), selected)
            }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun narrowWindowCanScrollToSundayAndSelectItWithoutShrinkingItsTouchTarget() {
        render()
        val dialog = composeRule.onNodeWithTag("event_date_picker_dialog")
        val headerY = composeRule.onNodeWithText("一", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center.y -
            dialog.fetchSemanticsNode().boundsInRoot.top
        // Drag the weekday header: dragging the month grid would page between months.
        dialog.performTouchInput {
            swipe(Offset(width * 0.85f, headerY), Offset(width * 0.15f, headerY), 350)
        }
        composeRule.waitForIdle()
        chooseDay(13)
        confirm()
        assertEquals(LocalDate.of(2026, 9, 13), selected)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h320dp-mdpi")
    fun shortLandscapeAtDoubleFontCanReachSundayAndConfirm() {
        render(fontScale = 2f)
        val dialog = composeRule.onNodeWithTag("event_date_picker_dialog")
        repeat(4) {
            val bounds = dayNode(27).fetchSemanticsNode().boundsInRoot
            if (bounds.height < 40f) {
                dialog.performTouchInput {
                    swipeUp(startY = height * 0.55f, endY = height * 0.12f, durationMillis = 350)
                }
                composeRule.waitForIdle()
            }
        }
        chooseDay(27)
        confirm()
        assertEquals(LocalDate.of(2026, 9, 27), selected)
    }

    @Test
    @Config(
        qualifiers = "zh-rCN-w640dp-h320dp-mdpi",
        shadows = [VisibleFrameShadow::class]
    )
    fun shortDialogTracksHostVisibleFrameAndReleasesItsLayoutListener() {
        val frame = VisibleFrameFixture()
        render(fontScale = 2f, frame = frame)
        assertTrue(surfaceHeight() <= 320f)
        listOf(248, 200, 0, 248).forEach { height ->
            composeRule.runOnUiThread {
                frame.bounds.set(0, 24, 640, 24 + height)
                hostView.viewTreeObserver.dispatchOnGlobalLayout()
            }
            composeRule.waitForIdle()
            val expectedHeight = if (height == 0) 200 else height
            assertTrue(
                "Surface exceeds host visible frame: ${surfaceHeight()} / $expectedHeight",
                surfaceHeight() <= expectedHeight
            )
            assertWholeActionsFit()
        }
        assertTrue("Production must read actual host frame", frame.reads > 0)
        confirm()
        assertEquals(LocalDate.of(2026, 9, 14), selected)
        composeRule.onNodeWithTag("event_date_picker_cancel").performTouchInput { click() }
        assertEquals(1, dismissals)
        composeRule.runOnIdle { showDialog.value = false }
        composeRule.waitForIdle()
        val readsAfterDispose = frame.reads
        composeRule.runOnUiThread { hostView.viewTreeObserver.dispatchOnGlobalLayout() }
        composeRule.waitForIdle()
        assertEquals(
            "Disposed picker must release global layout listener",
            readsAfterDispose,
            frame.reads
        )
    }

    private fun surfaceHeight(): Float = composeRule.onNodeWithTag("event_date_picker_dialog")
        .fetchSemanticsNode().boundsInRoot.height

    private fun assertWholeActionsFit() {
        val surface = composeRule.onNodeWithTag("event_date_picker_dialog")
            .fetchSemanticsNode().boundsInRoot
        listOf("event_date_picker_cancel", "event_date_picker_confirm").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("Whole 48dp action must fit: $tag $bounds", bounds.height >= 48f)
            assertTrue(
                "Action exceeds visible Surface: $tag $bounds",
                bounds.bottom <= surface.bottom
            )
        }
    }

    class VisibleFrameFixture {
        val bounds = Rect()
        var reads = 0
    }

    @Implements(ViewGroup::class)
    class VisibleFrameShadow : ShadowViewGroup() {
        @RealObject
        private lateinit var actualView: ViewGroup

        @Implementation
        override fun getWindowVisibleDisplayFrame(outRect: Rect) {
            val frame = actualView.tag as? VisibleFrameFixture
            if (frame == null) {
                super.getWindowVisibleDisplayFrame(outRect)
            } else {
                frame.reads += 1
                outRect.set(frame.bounds)
            }
        }
    }

    @Test
    fun changingSundayThenCancellingNeverWritesSelection() {
        render()
        chooseDay(13)
        composeRule.onNodeWithTag("event_date_picker_cancel")
            .performTouchInput { click() }
        composeRule.runOnIdle {
            assertNull(selected)
            assertEquals(1, dismissals)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    fun nextMonthNavigationSelectsTheActualSundayInThatMonth() {
        render()
        composeRule.onNodeWithContentDescription("Change to next month")
            .performTouchInput { click() }
        composeRule.waitForIdle()
        chooseDay(4, month = 10)
        confirm()
        assertEquals(LocalDate.of(2026, 10, 4), selected)
    }

    private fun render(
        theme: ThemeMode = ThemeMode.LIGHT,
        fontScale: Float = 1f,
        frame: VisibleFrameFixture? = null
    ) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                hostView = LocalView.current
                if (frame != null) hostView.tag = frame
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)
                ) {
                    ClenderTheme(AppearanceUiState(theme, 13, 13)) {
                        if (showDialog.value) {
                            EventDatePickerDialog(
                                selectedDate = LocalDate.of(2026, 9, 14),
                                onDateSelected = { selected = it },
                                onDismiss = { dismissals += 1 }
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun dayNode(day: Int, month: Int = 9): SemanticsNodeInteraction {
        val date = LocalDate.of(2026, month, day)
        val label = requireNotNull(
            DatePickerDefaults.dateFormatter().formatDate(
                EventDateTimePickerCodec.toUtcEpochMillis(date),
                host.activity.resources.configuration.locales[0],
                forContentDescription = true
            )
        )
        return composeRule.onNode(
            SemanticsMatcher("Accessible full date $date: $label") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                    it.text.endsWith(label)
                }
            } and hasClickAction()
        )
    }

    private fun assertDayFits(day: Int, month: Int = 9) {
        val bounds = dayNode(day, month).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val dialog = composeRule.onNodeWithTag("event_date_picker_dialog")
            .fetchSemanticsNode().boundsInWindow
        val density = host.activity.resources.displayMetrics.density
        assertTrue("Day $day has clipped width: $bounds", bounds.width >= 40f * density)
        assertTrue("Day $day has clipped height: $bounds", bounds.height >= 40f * density)
        assertTrue("Day $day lies outside dialog: $bounds / $dialog", bounds.left >= dialog.left)
        assertTrue("Day $day lies outside dialog: $bounds / $dialog", bounds.right <= dialog.right)
    }

    private fun chooseDay(day: Int, month: Int = 9) {
        assertDayFits(day, month)
        dayNode(day, month).performTouchInput { click() }
        composeRule.waitForIdle()
    }

    private fun confirm() {
        composeRule.onNodeWithTag("event_date_picker_confirm")
            .assertIsDisplayed().performTouchInput { click() }
        composeRule.waitForIdle()
    }
}
