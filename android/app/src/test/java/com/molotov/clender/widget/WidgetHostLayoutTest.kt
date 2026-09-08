package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.SizeF
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetHostLayoutTest {
    @Test
    @Config(sdk = [36])
    fun knownLargeHostDefaultViewKeepsAnActualRowInsteadOfAnUnrelatedTinyFallback() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).addBoundWidget(
            921,
            AppWidgetProviderInfo().apply {
                provider =
                    ComponentName(context, ClenderWidgetProvider::class.java)
            }
        )
        manager.updateAppWidgetOptions(
            921,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            }
        )
        val model = WidgetRenderModel(
            LocalDate.of(2026, 9, 8),
            WidgetRenderStatus.CONTENT,
            listOf(WidgetRenderRow("10:40–12:00", "Actual host row", EventTemporalState.NEXT, 1)),
            0,
            13,
            100,
            WidgetThemeMode.LIGHT
        )
        val root = WidgetRemoteViewsRenderer.renderForHost(context, model, SizeClass.LARGE, 921)
            .apply(context, FrameLayout(context))
        assertEquals(
            "Actual host row",
            root.findViewById<TextView>(R.id.widget_event_title).text.toString()
        )
    }

    @Test
    fun legacyHostDimensionsFollowOrientationAndRejectMissingOrInvalidOptions() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 700)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 650)
        }
        assertEquals(
            listOf(SizeF(360f, 650f)),
            WidgetHostLayout.sizes(options, Configuration.ORIENTATION_PORTRAIT)
        )
        assertEquals(
            listOf(SizeF(700f, 250f)),
            WidgetHostLayout.sizes(options, Configuration.ORIENTATION_LANDSCAPE)
        )
        assertTrue(
            WidgetHostLayout.sizes(Bundle.EMPTY, Configuration.ORIENTATION_PORTRAIT).isEmpty()
        )
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, -1)
        assertTrue(WidgetHostLayout.sizes(options, Configuration.ORIENTATION_PORTRAIT).isEmpty())
        options.putString(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, "invalid")
        assertTrue(WidgetHostLayout.sizes(options, Configuration.ORIENTATION_PORTRAIT).isEmpty())
    }

    @Test
    @Config(sdk = [36])
    fun responsiveSizesAreDistinctBoundedAndRejectOutOfRangeValues() {
        val options = Bundle().apply {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(
                    SizeF(360f, 650f),
                    SizeF(360f, 650f),
                    SizeF(-1f, 100f),
                    SizeF(5000f, 5000f)
                )
            )
        }
        assertEquals(
            listOf(SizeF(360f, 650f)),
            WidgetHostLayout.sizes(options, Configuration.ORIENTATION_PORTRAIT)
        )
        options.putParcelableArrayList(
            AppWidgetManager.OPTION_APPWIDGET_SIZES,
            ArrayList((1..30).map { SizeF(300f, 300f + it) })
        )
        assertEquals(12, WidgetHostLayout.sizes(options, Configuration.ORIENTATION_PORTRAIT).size)
    }

    @Test
    fun tallerActualHostRestoresRowsAndHiddenTotalsStayExact() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val model = WidgetRenderModel(
            LocalDate.of(2026, 9, 8),
            WidgetRenderStatus.CONTENT,
            (1..8).map { WidgetRenderRow("10:40–12:00", "事项 $it", EventTemporalState.NEXT) },
            3,
            20,
            50,
            WidgetThemeMode.LIGHT
        )
        val short = WidgetHostLayout.fit(context, model, SizeF(360f, 250f), SizeClass.LARGE)
        val tall = WidgetHostLayout.fit(context, model, SizeF(360f, 900f), SizeClass.LARGE)
        assertTrue(short.rows.size < tall.rows.size)
        assertEquals(8, tall.rows.size)
        listOf(short, tall).forEach { assertEquals(11, it.rows.size + it.remainingCount) }
    }
}
