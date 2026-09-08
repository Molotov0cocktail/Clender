package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SizeF
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import kotlin.math.ceil
import kotlin.math.max

/** Budget complete rows against the actual host size; hidden items remain in the footer count. */
internal object WidgetHostLayout {
    fun sizes(context: Context, appWidgetId: Int): List<SizeF> {
        val options = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        }.getOrDefault(Bundle.EMPTY)
        return sizes(options, context.resources.configuration.orientation)
    }

    internal fun sizes(options: Bundle, orientation: Int): List<SizeF> {
        val responsive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            runCatching {
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            }
                .getOrNull().orEmpty().filter(::valid).take(MAX_HOST_SIZES)
        } else {
            emptyList()
        }
        if (responsive.isNotEmpty()) return responsive.distinct()
        val portrait = orientation != Configuration.ORIENTATION_LANDSCAPE
        val widthKey = if (portrait) {
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        } else {
            AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
        }
        val heightKey = if (portrait) {
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        } else {
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        }
        val size = runCatching {
            SizeF(options.getInt(widthKey).toFloat(), options.getInt(heightKey).toFloat())
        }.getOrNull()
        return listOfNotNull(size?.takeIf(::valid))
    }

    fun fit(
        context: Context,
        model: WidgetRenderModel,
        size: SizeF,
        sizeClass: SizeClass
    ): WidgetRenderModel {
        if (model.status != WidgetRenderStatus.CONTENT) return model
        val metrics = context.resources.displayMetrics
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = model.fontSizeSp.coerceIn(MIN_FONT, MAX_FONT) * metrics.scaledDensity
        }
        val padding = context.resources.getDimensionPixelSize(R.dimen.widget_padding)
        val width = (size.width * metrics.density).toInt() - padding * TWO
        val header = max(
            (HEADER_DP * metrics.density).toInt(),
            textHeight(model.date.toString(), paint, width.coerceAtLeast(1), 1)
        )
        val available = (size.height * metrics.density).toInt() - padding * TWO - header
        val candidates = model.rows.filter { it.temporalState != EventTemporalState.PAST }
        val capped = candidates.take(sizeClass.capacity)
        val heights = capped.map { rowHeight(context, it, paint, width.coerceAtLeast(1)) }
        var count = 0
        var used = 0
        for (height in heights) {
            val hidden = model.remainingCount.coerceAtLeast(0) + candidates.size - count - 1
            val footer = if (hidden > 0) {
                textHeight(
                    context.getString(R.string.widget_more, hidden),
                    paint,
                    width.coerceAtLeast(1),
                    Int.MAX_VALUE
                )
            } else {
                0
            }
            if (used + height + footer > available) break
            used += height
            count += 1
        }
        return model.copy(
            rows = capped.take(count),
            remainingCount = model.remainingCount.coerceAtLeast(0) + candidates.size - count
        )
    }

    fun sizeClass(size: SizeF): SizeClass = when {
        size.width >= LARGE_DP && size.height >= LARGE_DP -> SizeClass.LARGE
        size.width >= LARGE_DP || size.height >= LARGE_DP -> SizeClass.MEDIUM
        else -> SizeClass.SMALL
    }

    private fun rowHeight(
        context: Context,
        row: WidgetRenderRow,
        paint: TextPaint,
        width: Int
    ): Int {
        val stateId = when (row.temporalState) {
            EventTemporalState.CURRENT -> R.string.widget_current
            EventTemporalState.NEXT -> R.string.widget_next
            else -> R.string.widget_future
        }
        val stateWidth = ceil(paint.measureText(context.getString(stateId))).toInt()
        val gap = context.resources.getDimensionPixelSize(R.dimen.widget_row_horizontal_gap)
        val titleHeight =
            textHeight(row.title, paint, (width - stateWidth - gap).coerceAtLeast(1), TWO)
        val stateHeight = textHeight(context.getString(stateId), paint, width, 1)
        val timeHeight = textHeight(row.timeLabel, paint, width, Int.MAX_VALUE)
        val minimum = context.resources.getDimensionPixelSize(R.dimen.widget_row_min_height)
        val margin = context.resources.getDimensionPixelSize(R.dimen.widget_row_margin_top)
        return max(minimum, max(titleHeight, stateHeight) + timeHeight) + margin
    }

    private fun textHeight(text: String, paint: TextPaint, width: Int, lines: Int): Int =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setMaxLines(lines)
            .build().height

    private fun valid(size: SizeF): Boolean = size.width.isFinite() && size.height.isFinite() &&
        size.width in MIN_DP..MAX_DP && size.height in MIN_DP..MAX_DP
}

private const val TWO = 2
private const val HEADER_DP = 48
private const val MIN_FONT = 8
private const val MAX_FONT = 20
private const val MIN_DP = 1f
private const val MAX_DP = 4096f
private const val LARGE_DP = 250f
private const val MAX_HOST_SIZES = 12
