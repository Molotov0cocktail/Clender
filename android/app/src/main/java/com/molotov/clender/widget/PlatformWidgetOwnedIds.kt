package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.molotov.clender.app.widget.WidgetOwnedIdsPort

class PlatformWidgetOwnedIds(context: Context) : WidgetOwnedIdsPort {
    private val application = context.applicationContext

    override suspend fun ownedIds(): Set<Int> = AppWidgetManager.getInstance(application)
        .getAppWidgetIds(ComponentName(application, ClenderWidgetProvider::class.java))
        .filter { it > 0 }
        .toSet()
}
