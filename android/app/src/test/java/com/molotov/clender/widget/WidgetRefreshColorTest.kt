package com.molotov.clender.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetRefreshColorTest {
    @Test
    fun explicitLightOverridesBothSystemModes() {
        listOf(false, true).forEach {
            assertTheme(WidgetThemeMode.LIGHT, it, R.color.widget_text_light)
        }
    }

    @Test
    fun explicitDarkOverridesBothSystemModes() {
        listOf(false, true).forEach {
            assertTheme(WidgetThemeMode.DARK, it, R.color.widget_text_dark)
        }
    }

    @Test
    fun systemDayUsesLightActionText() {
        assertTheme(WidgetThemeMode.SYSTEM, false, R.color.widget_text_light)
    }

    @Test
    fun systemNightUsesDarkActionText() {
        assertTheme(WidgetThemeMode.SYSTEM, true, R.color.widget_text_dark)
    }

    @Test
    fun reapplyUpdatesRefreshWhenThemeChangesInBothDirections() {
        val context = configuredContext(false)
        val parent = FrameLayout(context)
        val root = WidgetRemoteViewsRenderer.render(
            context,
            model(WidgetThemeMode.LIGHT),
            SizeClass.LARGE,
            71
        ).apply(context, parent)
        listOf(
            WidgetThemeMode.DARK to R.color.widget_text_dark,
            WidgetThemeMode.LIGHT to R.color.widget_text_light
        ).forEach { (theme, color) ->
            WidgetRemoteViewsRenderer.render(context, model(theme), SizeClass.LARGE, 71)
                .reapply(context, root)
            assertActionColor(root, context.getColor(color))
        }
    }

    private fun assertTheme(theme: WidgetThemeMode, night: Boolean, color: Int) {
        val context = configuredContext(night)
        WidgetRenderStatus.entries.forEach { status ->
            listOf(8 to 0, 20 to 100).forEach { (font, opacity) ->
                val model = model(theme).copy(
                    status = status,
                    rows = if (status == WidgetRenderStatus.CONTENT) {
                        model(theme).rows
                    } else {
                        emptyList()
                    },
                    fontSizeSp = font,
                    opacityPercent = opacity
                )
                val views = WidgetRemoteViewsRenderer.render(context, model, SizeClass.LARGE, 71)
                assertActionColor(
                    views.apply(context, FrameLayout(context)),
                    context.getColor(color)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val responsive = WidgetRemoteViewsRenderer.renderResponsiveMap(
                        context,
                        model,
                        71
                    ).getValue(SizeF(250f, 250f))
                    assertActionColor(
                        responsive.apply(context, FrameLayout(context)),
                        context.getColor(color)
                    )
                }
            }
        }
    }

    private fun assertActionColor(root: View, expected: Int) {
        val refresh = requireNotNull(root.findViewById<TextView>(R.id.widget_refresh))
        assertEquals(View.VISIBLE, refresh.visibility)
        assertEquals(
            "Refresh must use the configured action foreground",
            expected,
            refresh.currentTextColor
        )
        assertEquals(255, Color.alpha(refresh.currentTextColor))
        listOf(R.id.widget_quick_ai, R.id.widget_configure).forEach { id ->
            assertEquals(root.findViewById<TextView>(id).currentTextColor, refresh.currentTextColor)
        }
    }

    private fun configuredContext(night: Boolean): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return context.createConfigurationContext(configuration)
    }

    private fun model(theme: WidgetThemeMode) = WidgetRenderModel(
        date = LocalDate.of(2026, 9, 6),
        status = WidgetRenderStatus.CONTENT,
        rows = listOf(WidgetRenderRow("09:00", "Test event", EventTemporalState.NEXT, 1)),
        remainingCount = 0,
        fontSizeSp = 13,
        opacityPercent = 100,
        theme = theme
    )
}
