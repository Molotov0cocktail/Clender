package com.molotov.clender.widget

import android.content.Context
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTimeReadabilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun completeTimeUsesItsOwnWidthWithoutEllipsisAtNarrowAndLargeFontBounds() {
        listOf(110, 250, 360).forEach { width ->
            listOf(8, 13, 20).forEach { font ->
                val root = render(width, 700, font, 1)
                val time = root.findViewById<TextView>(R.id.widget_event_time)
                assertTrue("Time must be displayed at $width dp / $font sp", time != null)
                val text = requireNotNull(time)
                val layout = requireNotNull(text.layout)
                assertEquals("Complete time must not be ellipsized", null, text.ellipsize)
                assertEquals(text.text.length, layout.getLineEnd(layout.lineCount - 1))
                assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                assertTrue(
                    layout.height <=
                        text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                )
            }
        }
    }

    @Test
    fun compactHostShowsOnlyWholeRowsAndReportsHiddenItems() {
        listOf(8, 13, 20).forEach { font ->
            val root = render(250, 250, font, 8, hostBound = true)
            val rows = root.findViewById<ViewGroup>(R.id.widget_rows)
            val more = root.findViewById<TextView>(R.id.widget_more)
            assertTrue("All eight two-line rows cannot fit this host", rows.childCount < 8)
            assertEquals(View.VISIBLE, more.visibility)
            assertTrue(more.bottom <= root.height - root.paddingBottom)
            for (index in 0 until rows.childCount) {
                val row = rows.getChildAt(index)
                assertTrue(rows.top + row.bottom <= more.top)
            }
        }
    }

    private fun render(
        widthDp: Int,
        heightDp: Int,
        font: Int,
        count: Int,
        hostBound: Boolean = false
    ): View {
        val model = WidgetRenderModel(
            date = LocalDate.of(2026, 9, 8),
            status = WidgetRenderStatus.CONTENT,
            rows = (1..count).map {
                WidgetRenderRow("10:40 AM–12:00 PM", "事项标题 $it", EventTemporalState.NEXT)
            },
            remainingCount = 0,
            fontSizeSp = font,
            opacityPercent = 50,
            theme = WidgetThemeMode.LIGHT
        )
        val views = if (hostBound) {
            WidgetRemoteViewsRenderer.renderResponsiveMap(context, model)
                .getValue(SizeF(widthDp.toFloat(), heightDp.toFloat()))
        } else {
            WidgetRemoteViewsRenderer.render(context, model, SizeClass.LARGE)
        }
        val root = views.apply(context, FrameLayout(context))
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
        return root
    }
}
