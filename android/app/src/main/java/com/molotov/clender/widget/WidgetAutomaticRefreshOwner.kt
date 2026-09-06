package com.molotov.clender.widget

import com.molotov.clender.app.widget.WidgetAutomaticRefreshPort
import kotlinx.coroutines.CoroutineScope

interface WidgetAutomaticRefreshOwner {
    val widgetAutomaticRefreshScope: CoroutineScope
    val widgetAutomaticRefreshRuntime: WidgetAutomaticRefreshPort
}
