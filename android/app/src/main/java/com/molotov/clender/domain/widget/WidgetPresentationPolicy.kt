package com.molotov.clender.domain.widget

import com.molotov.clender.domain.calendar.EventTemporalState

private const val SMALL_CAPACITY = 2
private const val MEDIUM_CAPACITY = 4
private const val LARGE_CAPACITY = 8

data class WidgetPresentation(
    val visibleItems: List<WidgetEventItem>,
    val remainingCount: Int,
    val opacityPercent: Int
)

object WidgetPresentationPolicy {
    enum class SizeClass(val capacity: Int) {
        SMALL(SMALL_CAPACITY),
        MEDIUM(MEDIUM_CAPACITY),
        LARGE(LARGE_CAPACITY)
    }

    fun present(state: WidgetState, sizeClass: SizeClass): WidgetPresentation {
        val orderedItems = listOf(
            EventTemporalState.CURRENT,
            EventTemporalState.NEXT,
            EventTemporalState.FUTURE
        ).flatMap { temporalState ->
            state.items.filter { it.temporalState == temporalState }
        }.distinctBy(WidgetEventItem::id)
        val visibleItems = orderedItems.take(sizeClass.capacity)

        return WidgetPresentation(
            visibleItems = visibleItems,
            remainingCount = (orderedItems.size - visibleItems.size).coerceAtLeast(0),
            opacityPercent = state.opacityPercent
        )
    }
}
