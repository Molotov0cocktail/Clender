package com.molotov.clender.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.molotov.clender.R
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetPresentation
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class WidgetRenderStatus {
    CONTENT,
    EMPTY,
    UNAVAILABLE
}

data class WidgetRenderRow(
    val timeLabel: String,
    val title: String,
    val temporalState: EventTemporalState,
    val eventId: Long = INVALID_EVENT_ID
)

data class WidgetRenderModel(
    val date: LocalDate,
    val status: WidgetRenderStatus,
    val rows: List<WidgetRenderRow>,
    val remainingCount: Int,
    val fontSizeSp: Int,
    val opacityPercent: Int,
    val theme: WidgetThemeMode
)

object WidgetRenderModelFactory {
    fun ready(
        date: LocalDate,
        presentation: WidgetPresentation,
        configuration: WidgetConfiguration,
        locale: Locale
    ): WidgetRenderModel {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val rows = presentation.visibleItems.map { item ->
            val timeLabel = when (item.eventType) {
                EventType.REMINDER -> item.startTime.format(formatter)

                EventType.TIMESPAN -> {
                    val endTime = requireNotNull(item.endTime)
                    "${item.startTime.format(formatter)}–${endTime.format(formatter)}"
                }
            }
            WidgetRenderRow(
                timeLabel = timeLabel,
                title = item.title,
                temporalState = item.temporalState,
                eventId = item.id
            )
        }
        return WidgetRenderModel(
            date = date,
            status = if (rows.isEmpty()) WidgetRenderStatus.EMPTY else WidgetRenderStatus.CONTENT,
            rows = rows,
            remainingCount = presentation.remainingCount,
            fontSizeSp = configuration.fontSizeSp,
            opacityPercent = presentation.opacityPercent,
            theme = configuration.theme
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun unavailable(date: LocalDate, locale: Locale): WidgetRenderModel = WidgetRenderModel(
        date = date,
        status = WidgetRenderStatus.UNAVAILABLE,
        rows = emptyList(),
        remainingCount = 0,
        fontSizeSp = SAFE_FONT_SIZE_SP,
        opacityPercent = MAX_OPACITY,
        theme = WidgetThemeMode.SYSTEM
    )
}

object WidgetRemoteViewsRenderer {
    fun renderForHost(
        context: Context,
        model: WidgetRenderModel,
        legacySizeClass: SizeClass
    ): RemoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        renderResponsive(context, model)
    } else {
        render(context, model, legacySizeClass)
    }

    fun renderForHost(
        context: Context,
        model: WidgetRenderModel,
        legacySizeClass: SizeClass,
        appWidgetId: Int
    ): RemoteViews {
        val sizes = WidgetHostLayout.sizes(context, appWidgetId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (sizes.isEmpty()) {
                return RemoteViews(
                    renderResponsiveMap(context, model, appWidgetId)
                )
            }
            val mappings = mutableMapOf<SizeF, RemoteViews>()
            sizes.forEach { size ->
                val sizeClass = WidgetHostLayout.sizeClass(size)
                mappings[size] = render(
                    context,
                    WidgetHostLayout.fit(context, model, size, sizeClass),
                    sizeClass,
                    appWidgetId
                )
            }
            RemoteViews(mappings)
        } else {
            val size = sizes.firstOrNull() ?: defaultSize(legacySizeClass)
            render(
                context,
                WidgetHostLayout.fit(context, model, size, legacySizeClass),
                legacySizeClass,
                appWidgetId
            )
        }
    }

    internal fun isResponsiveApi(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

    fun render(context: Context, model: WidgetRenderModel, sizeClass: SizeClass): RemoteViews =
        renderInternal(context, model, sizeClass, appWidgetId = null)

    private fun renderInternal(
        context: Context,
        model: WidgetRenderModel,
        sizeClass: SizeClass,
        appWidgetId: Int?
    ): RemoteViews {
        val colors = colors(context, model.theme, model.opacityPercent)
        val views = RemoteViews(context.packageName, layoutFor(sizeClass))
        views.setInt(R.id.widget_root, "setBackgroundColor", colors.background)
        views.setTextColor(R.id.widget_date, colors.foreground)
        views.setTextColor(R.id.widget_configure, colors.foreground)
        views.setTextColor(R.id.widget_status, colors.secondary)
        views.setTextColor(R.id.widget_more, colors.secondary)
        val fontSize = model.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP).toFloat()
        views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.widget_configure, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.widget_status, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.widget_more, TypedValue.COMPLEX_UNIT_SP, fontSize)
        if (sizeClass == SizeClass.LARGE) {
            views.setTextColor(R.id.widget_quick_ai, colors.foreground)
            views.setTextColor(R.id.widget_refresh, colors.foreground)
            views.setTextViewTextSize(R.id.widget_quick_ai, TypedValue.COMPLEX_UNIT_SP, fontSize)
        }
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale(context))
        views.setTextViewText(
            R.id.widget_date,
            model.date.format(dateFormatter)
        )
        views.removeAllViews(R.id.widget_rows)

        when (model.status) {
            WidgetRenderStatus.CONTENT ->
                renderContent(
                    context,
                    views,
                    model,
                    sizeClass,
                    WidgetRenderBinding(colors, appWidgetId)
                )

            WidgetRenderStatus.EMPTY ->
                renderFiniteStatus(context, views, R.string.widget_empty)

            WidgetRenderStatus.UNAVAILABLE -> {
                renderFiniteStatus(context, views, R.string.widget_unavailable)
            }
        }
        if (appWidgetId != null) {
            views.setOnClickPendingIntent(
                R.id.widget_configure,
                WidgetPendingIntentFactory.configure(context, appWidgetId)
            )
            if (sizeClass == SizeClass.LARGE) {
                views.setOnClickPendingIntent(
                    R.id.widget_quick_ai,
                    WidgetPendingIntentFactory.quickAi(context, appWidgetId)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_refresh,
                    WidgetPendingIntentFactory.localRefresh(context, appWidgetId)
                )
            }
        }
        return views
    }

    fun render(
        context: Context,
        model: WidgetRenderModel,
        sizeClass: SizeClass,
        appWidgetId: Int
    ): RemoteViews = renderInternal(context, model, sizeClass, appWidgetId)

    @RequiresApi(Build.VERSION_CODES.S)
    fun renderResponsive(context: Context, model: WidgetRenderModel): RemoteViews =
        RemoteViews(renderResponsiveMap(context, model))

    @RequiresApi(Build.VERSION_CODES.S)
    fun renderResponsive(
        context: Context,
        model: WidgetRenderModel,
        appWidgetId: Int
    ): RemoteViews = RemoteViews(renderResponsiveMap(context, model, appWidgetId))

    @RequiresApi(Build.VERSION_CODES.S)
    fun renderResponsiveMap(context: Context, model: WidgetRenderModel): Map<SizeF, RemoteViews> =
        linkedMapOf(
            SizeF(SMALL_DP, SMALL_DP) to SizeClass.SMALL,
            SizeF(MEDIUM_DP, SMALL_DP) to SizeClass.MEDIUM,
            SizeF(SMALL_DP, MEDIUM_DP) to SizeClass.MEDIUM,
            SizeF(MEDIUM_DP, MEDIUM_DP) to SizeClass.LARGE
        ).mapValues { (size, sizeClass) ->
            render(context, WidgetHostLayout.fit(context, model, size, sizeClass), sizeClass)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    fun renderResponsiveMap(
        context: Context,
        model: WidgetRenderModel,
        appWidgetId: Int
    ): Map<SizeF, RemoteViews> = linkedMapOf(
        SizeF(SMALL_DP, SMALL_DP) to SizeClass.SMALL,
        SizeF(MEDIUM_DP, SMALL_DP) to SizeClass.MEDIUM,
        SizeF(SMALL_DP, MEDIUM_DP) to SizeClass.MEDIUM,
        SizeF(MEDIUM_DP, MEDIUM_DP) to SizeClass.LARGE
    ).mapValues { (size, sizeClass) ->
        render(
            context,
            WidgetHostLayout.fit(context, model, size, sizeClass),
            sizeClass,
            appWidgetId
        )
    }
}

private fun renderContent(
    context: Context,
    views: RemoteViews,
    model: WidgetRenderModel,
    sizeClass: SizeClass,
    binding: WidgetRenderBinding
) {
    val displayableRows = model.rows.filter { it.temporalState != EventTemporalState.PAST }
    val visibleRows = displayableRows.take(sizeClass.capacity)
    if (visibleRows.isEmpty()) {
        if (model.remainingCount > 0) {
            views.setViewVisibility(R.id.widget_status, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_status,
                context.getString(R.string.widget_more, model.remainingCount)
            )
            views.setViewVisibility(R.id.widget_more, View.GONE)
        } else {
            renderFiniteStatus(context, views, R.string.widget_empty)
        }
        return
    }
    views.setViewVisibility(R.id.widget_status, View.GONE)
    visibleRows.forEach { row ->
        views.addView(
            R.id.widget_rows,
            renderRow(context, model, row, binding)
        )
    }
    val hiddenBySize = (displayableRows.size - visibleRows.size).coerceAtLeast(0)
    val remaining = (model.remainingCount.coerceAtLeast(0) + hiddenBySize)
    if (remaining > 0) {
        views.setViewVisibility(R.id.widget_more, View.VISIBLE)
        views.setTextViewText(R.id.widget_more, context.getString(R.string.widget_more, remaining))
    } else {
        views.setViewVisibility(R.id.widget_more, View.GONE)
    }
}

private fun renderFiniteStatus(context: Context, views: RemoteViews, statusResource: Int) {
    views.setViewVisibility(R.id.widget_status, View.VISIBLE)
    views.setTextViewText(R.id.widget_status, context.getString(statusResource))
    views.setViewVisibility(R.id.widget_more, View.GONE)
}

private fun renderRow(
    context: Context,
    model: WidgetRenderModel,
    row: WidgetRenderRow,
    binding: WidgetRenderBinding
): RemoteViews = RemoteViews(context.packageName, R.layout.widget_clender_event_row).apply {
    setTextViewText(R.id.widget_event_time, row.timeLabel)
    setTextViewText(R.id.widget_event_title, row.title)
    setTextViewText(R.id.widget_event_state, context.getString(row.temporalState.labelResource()))
    val size = model.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP).toFloat()
    setTextViewTextSize(R.id.widget_event_time, TypedValue.COMPLEX_UNIT_SP, size)
    setTextViewTextSize(R.id.widget_event_title, TypedValue.COMPLEX_UNIT_SP, size)
    setTextViewTextSize(R.id.widget_event_state, TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(R.id.widget_event_time, binding.colors.secondary)
    setTextColor(R.id.widget_event_title, binding.colors.foreground)
    setTextColor(R.id.widget_event_state, binding.colors.accent)
    if (binding.appWidgetId != null && row.eventId in MIN_EVENT_ID..MAX_EVENT_ID) {
        setOnClickPendingIntent(
            R.id.widget_event_row,
            WidgetPendingIntentFactory.editEvent(
                context,
                binding.appWidgetId,
                row.eventId.toInt()
            )
        )
    }
}

private fun EventTemporalState.labelResource(): Int = when (this) {
    EventTemporalState.CURRENT -> R.string.widget_current
    EventTemporalState.NEXT -> R.string.widget_next
    EventTemporalState.FUTURE -> R.string.widget_future
    EventTemporalState.PAST -> error("Past Widget state is not renderable")
}

private fun layoutFor(sizeClass: SizeClass): Int = when (sizeClass) {
    SizeClass.SMALL -> R.layout.widget_clender_small
    SizeClass.MEDIUM -> R.layout.widget_clender_medium
    SizeClass.LARGE -> R.layout.widget_clender_large
}

private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

private fun defaultSize(sizeClass: SizeClass): SizeF = when (sizeClass) {
    SizeClass.SMALL -> SizeF(SMALL_DP, SMALL_DP)
    SizeClass.MEDIUM -> SizeF(MEDIUM_DP, SMALL_DP)
    SizeClass.LARGE -> SizeF(MEDIUM_DP, MEDIUM_DP)
}

private fun colors(context: Context, theme: WidgetThemeMode, opacityPercent: Int): WidgetColors {
    val dark = when (theme) {
        WidgetThemeMode.LIGHT -> false

        WidgetThemeMode.DARK -> true

        WidgetThemeMode.SYSTEM -> {
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }
    val resources = context.resources
    val background = resources.getColor(
        if (dark) R.color.widget_background_dark else R.color.widget_background_light,
        context.theme
    )
    val alpha = (opacityPercent.coerceIn(MIN_OPACITY, MAX_OPACITY) * MAX_ALPHA) / MAX_OPACITY
    return WidgetColors(
        background = Color.argb(
            alpha,
            Color.red(background),
            Color.green(background),
            Color.blue(background)
        ),
        foreground = resources.getColor(
            if (dark) R.color.widget_text_dark else R.color.widget_text_light,
            context.theme
        ),
        secondary = resources.getColor(
            if (dark) R.color.widget_secondary_dark else R.color.widget_secondary_light,
            context.theme
        ),
        accent = resources.getColor(
            if (dark) R.color.widget_accent_dark else R.color.widget_accent_light,
            context.theme
        )
    )
}

private data class WidgetColors(
    val background: Int,
    val foreground: Int,
    val secondary: Int,
    val accent: Int
)

private data class WidgetRenderBinding(val colors: WidgetColors, val appWidgetId: Int?)

private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 20
private const val SAFE_FONT_SIZE_SP = 13
private const val MIN_OPACITY = 0
private const val MAX_OPACITY = 100
private const val MAX_ALPHA = 255
private const val SMALL_DP = 110f
private const val MEDIUM_DP = 250f
private const val INVALID_EVENT_ID = 0L
private const val MIN_EVENT_ID = 1L
private const val MAX_EVENT_ID = 2_147_483_647L
