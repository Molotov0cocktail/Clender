package com.molotov.clender.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

@Composable
internal fun CalendarAccessibleLayout(
    minimumBodyHeight: @Composable (Dp) -> Dp,
    content: CalendarLayoutContent,
    modifier: Modifier = Modifier,
    naturalBody: Boolean = false
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableHeight = constraints.maxHeight
        val minimumHeight = minimumBodyHeight(maxWidth)
        val scroll = rememberScrollState()
        val connection = remember(scroll) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f) return Offset.Zero
                    return Offset(0f, -scroll.dispatchRawDelta(-available.y))
                }
            }
        }
        Layout(
            content = {
                Box { content.toolbar() }
                Box(contentAlignment = Alignment.TopCenter) { content.status() }
                Box { content.body() }
            },
            modifier = Modifier.nestedScroll(connection)
                .verticalScroll(scroll, enabled = scroll.maxValue > 0)
        ) { measurables, parent ->
            val loose = Constraints(minWidth = parent.maxWidth, maxWidth = parent.maxWidth)
            val heading = measurables[0].measure(loose)
            val notice = measurables[1].measure(loose)
            val remaining = (availableHeight - heading.height - notice.height).coerceAtLeast(0)
            val bodyConstraints = if (naturalBody) {
                loose
            } else {
                Constraints.fixed(parent.maxWidth, maxOf(remaining, minimumHeight.roundToPx()))
            }
            val content = measurables[2].measure(bodyConstraints)
            val height = maxOf(availableHeight, heading.height + notice.height + content.height)
            layout(parent.maxWidth, height) {
                heading.placeRelative(0, 0)
                notice.placeRelative(0, heading.height)
                val offset = if (naturalBody) {
                    (remaining - content.height).coerceAtLeast(0) / 2
                } else {
                    0
                }
                content.placeRelative(0, heading.height + notice.height + offset)
            }
        }
    }
}

internal data class CalendarLayoutContent(
    val toolbar: @Composable () -> Unit,
    val status: @Composable () -> Unit,
    val body: @Composable () -> Unit
)
