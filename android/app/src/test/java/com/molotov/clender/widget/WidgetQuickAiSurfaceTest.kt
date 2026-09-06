package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetQuickAiSurfaceTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun largeQuickAiIsVisibleOrderedAndClickableForAllFiniteStatesAndInstances() {
        listOf(41, 42).forEach { widgetId ->
            WidgetRenderStatus.entries.forEach { status ->
                val root = WidgetRemoteViewsRenderer.render(
                    context,
                    model(status),
                    SizeClass.LARGE,
                    widgetId
                ).apply(context, FrameLayout(context))
                val ai = requireNotNull(root.findViewById<TextView>(R.id.widget_quick_ai))
                assertEquals(View.VISIBLE, ai.visibility)
                assertEquals("AI", ai.text.toString())
                assertTrue(ai.hasOnClickListeners())
                val header = ai.parent as ViewGroup
                assertEquals(
                    listOf(
                        R.id.widget_date,
                        R.id.widget_quick_ai,
                        R.id.widget_refresh,
                        R.id.widget_configure
                    ),
                    (0 until header.childCount).map { header.getChildAt(it).id }
                )
                val application = RuntimeEnvironment.getApplication()
                shadowOf(application).clearNextStartedActivities()
                assertTrue(ai.performClick())
                val started = requireNotNull(shadowOf(application).nextStartedActivity)
                assertEquals("com.molotov.clender.action.WIDGET_QUICK_AI", started.action)
                assertEquals(
                    "com.molotov.clender.widget.QuickAiActivity",
                    started.component?.className
                )
                assertEquals("clender-internal://widget/$widgetId/quick-ai", started.dataString)
                assertEquals(widgetId, started.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), started.extras?.keySet())
            }
        }
    }

    @Test
    fun smallerSizesNeverContainQuickAiEvenForEmptyAndUnavailableStates() {
        listOf(SizeClass.SMALL, SizeClass.MEDIUM).forEach { size ->
            WidgetRenderStatus.entries.forEach { status ->
                val root = WidgetRemoteViewsRenderer.render(context, model(status), size, 41)
                    .apply(context, FrameLayout(context))
                assertNull(root.findViewById<View>(R.id.widget_quick_ai))
            }
        }
    }

    @Test
    fun renderWithoutWidgetIdentityNeverBindsQuickAi() {
        val root = WidgetRemoteViewsRenderer.render(context, model(), SizeClass.LARGE)
            .apply(context, FrameLayout(context))
        assertFalse(
            requireNotNull(root.findViewById<View>(R.id.widget_quick_ai)).hasOnClickListeners()
        )
    }

    @Test
    @Config(sdk = [26])
    fun legacyProductionHostSelectsQuickAiOnlyForLarge() {
        SizeClass.entries.forEach { size ->
            val root = WidgetRemoteViewsRenderer.renderForHost(context, model(), size, 41)
                .apply(context, FrameLayout(context))
            assertEquals(
                size == SizeClass.LARGE,
                root.findViewById<View>(R.id.widget_quick_ai) != null
            )
        }
    }

    @Test
    fun quickAiUsesLocalizedAccessibleDescriptionAndFontBoundsAcrossThemes() {
        val descriptions = mutableSetOf<String>()
        listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(locale) }
            )
            WidgetThemeMode.entries.forEach { theme ->
                listOf(8, 20).forEach { font ->
                    val root = WidgetRemoteViewsRenderer.render(
                        localized,
                        model().copy(theme = theme, fontSizeSp = font),
                        SizeClass.LARGE,
                        41
                    ).apply(localized, FrameLayout(localized))
                    val ai = requireNotNull(root.findViewById<TextView>(R.id.widget_quick_ai))
                    assertEquals("AI", ai.text.toString())
                    assertTrue(ai.contentDescription.isNotBlank())
                    descriptions += ai.contentDescription.toString()
                    assertEquals(
                        font.toFloat(),
                        ai.textSize / root.resources.displayMetrics.scaledDensity,
                        0.01f
                    )
                    val minimumTouchSize = 48 * root.resources.displayMetrics.density
                    assertTrue(ai.minimumWidth >= minimumTouchSize)
                    assertTrue(ai.minimumHeight >= minimumTouchSize)
                    assertEquals(
                        root.findViewById<TextView>(R.id.widget_configure).currentTextColor,
                        ai.currentTextColor
                    )
                }
            }
        }
        assertEquals(2, descriptions.size)
    }

    private fun model(status: WidgetRenderStatus = WidgetRenderStatus.CONTENT) = WidgetRenderModel(
        date = LocalDate.of(2026, 9, 6),
        status = status,
        rows = listOf(WidgetRenderRow("09:00", "synthetic title", EventTemporalState.NEXT)),
        remainingCount = 0,
        fontSizeSp = 13,
        opacityPercent = 100,
        theme = WidgetThemeMode.SYSTEM
    )
}
