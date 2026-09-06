package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.Bundle
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetSizeClassResolverTest {
    @Test
    fun portraitUsesMinWidthAndMaxHeightOnly() {
        assertEquals(
            SizeClass.LARGE,
            WidgetSizeClassResolver.resolve(
                options(250, 110, 250, 250),
                Configuration.ORIENTATION_PORTRAIT
            )
        )
        assertEquals(
            SizeClass.MEDIUM,
            WidgetSizeClassResolver.resolve(
                options(250, 110, 110, 249),
                Configuration.ORIENTATION_PORTRAIT
            )
        )
        assertEquals(
            SizeClass.SMALL,
            WidgetSizeClassResolver.resolve(
                options(249, 110, 110, 249),
                Configuration.ORIENTATION_PORTRAIT
            )
        )
    }

    @Test
    fun landscapeUsesMaxWidthAndMinHeightOnly() {
        assertEquals(
            SizeClass.LARGE,
            WidgetSizeClassResolver.resolve(
                options(110, 250, 250, 250),
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
        assertEquals(
            SizeClass.MEDIUM,
            WidgetSizeClassResolver.resolve(
                options(110, 250, 249, 110),
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
        assertEquals(
            SizeClass.SMALL,
            WidgetSizeClassResolver.resolve(
                options(110, 249, 110, 249),
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
    }

    @Test
    fun missingZeroNegativeAndMalformedDimensionsFailClosedToSmall() {
        listOf(
            Bundle(),
            options(0, 0, 0, 0),
            options(-1, 100, 100, 100),
            Bundle().apply {
                putString(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, "250")
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            }
        ).forEach { malformed ->
            assertEquals(
                SizeClass.SMALL,
                WidgetSizeClassResolver.resolve(malformed, Configuration.ORIENTATION_PORTRAIT)
            )
        }
    }

    @Test
    fun undefinedAndUnexpectedOrientationFailClosedToSmall() {
        val largeInEitherKnownOrientation = options(300, 300, 300, 300)

        assertEquals(
            SizeClass.SMALL,
            WidgetSizeClassResolver.resolve(
                largeInEitherKnownOrientation,
                Configuration.ORIENTATION_UNDEFINED
            )
        )
        assertEquals(
            SizeClass.SMALL,
            WidgetSizeClassResolver.resolve(largeInEitherKnownOrientation, Int.MAX_VALUE)
        )
    }

    @Test
    fun thresholdRequiresAtLeast250DpAndNeverReadsDisplayMetrics() {
        assertEquals(
            SizeClass.MEDIUM,
            WidgetSizeClassResolver.resolve(
                options(1, 250, 1, 1),
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
        assertEquals(
            SizeClass.SMALL,
            WidgetSizeClassResolver.resolve(
                options(1, 249, 1, 249),
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
    }

    private fun options(minWidth: Int, maxWidth: Int, minHeight: Int, maxHeight: Int): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight)
        }
}
