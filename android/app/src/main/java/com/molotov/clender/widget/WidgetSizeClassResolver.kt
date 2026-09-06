package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.Bundle
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass

object WidgetSizeClassResolver {
    fun resolve(options: Bundle, orientation: Int): SizeClass {
        val currentDimensions = when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> dimensions(
                options,
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            )

            Configuration.ORIENTATION_LANDSCAPE -> dimensions(
                options,
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
            )

            else -> null
        }

        return currentDimensions?.let(::classify) ?: SizeClass.SMALL
    }
}

private fun classify(dimensions: Pair<Int, Int>): SizeClass {
    val (width, height) = dimensions
    return when {
        width >= LARGE_THRESHOLD_DP && height >= LARGE_THRESHOLD_DP -> SizeClass.LARGE
        width >= LARGE_THRESHOLD_DP || height >= LARGE_THRESHOLD_DP -> SizeClass.MEDIUM
        else -> SizeClass.SMALL
    }
}

private fun dimensions(options: Bundle, widthKey: String, heightKey: String): Pair<Int, Int>? {
    val width = integerOption(options, widthKey)
    val height = integerOption(options, heightKey)
    return if (width > 0 && height > 0) width to height else null
}

private fun integerOption(options: Bundle, key: String): Int =
    runCatching { options.getInt(key, INVALID_DIMENSION) }.getOrDefault(INVALID_DIMENSION)

private const val INVALID_DIMENSION = -1
private const val LARGE_THRESHOLD_DP = 250
