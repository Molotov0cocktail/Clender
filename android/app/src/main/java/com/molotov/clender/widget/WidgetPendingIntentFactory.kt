package com.molotov.clender.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.molotov.clender.domain.widget.WidgetActionSpec

/** Creates immutable platform tokens for the supported Widget actions. */
object WidgetPendingIntentFactory {
    fun create(context: Context, action: WidgetActionSpec): PendingIntent = when (action) {
        is WidgetActionSpec.Configure -> configure(context, action.widgetId)
        is WidgetActionSpec.EditEvent -> editEvent(context, action.widgetId, action.eventId)
        is WidgetActionSpec.LocalRefresh -> localRefresh(context, action.widgetId)
        is WidgetActionSpec.QuickAi -> quickAi(context, action.widgetId)
    }

    fun configure(context: Context, appWidgetId: Int): PendingIntent {
        require(appWidgetId > 0) { "Widget id must be positive" }
        val action = WidgetActionSpec.Configure(appWidgetId)
        val intent = Intent(context, WidgetConfigurationActivity::class.java).apply {
            setPackage(context.packageName)
            this.action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            data = action.canonicalIdentity.toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return activity(context, intent)
    }

    fun editEvent(context: Context, appWidgetId: Int, eventId: Int): PendingIntent {
        val action = WidgetActionSpec.EditEvent(appWidgetId, eventId)
        val intent = Intent(WidgetActionIntentContract.ACTION_EDIT_EVENT).apply {
            component = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
            setPackage(context.packageName)
            data = action.canonicalIdentity.toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return activity(context, intent)
    }

    fun localRefresh(context: Context, appWidgetId: Int): PendingIntent {
        val action = WidgetActionSpec.LocalRefresh(appWidgetId)
        val intent = Intent(WidgetActionIntentContract.ACTION_LOCAL_REFRESH).apply {
            component = ComponentName(context.packageName, LOCAL_REFRESH_RECEIVER_CLASS_NAME)
            setPackage(context.packageName)
            data = action.canonicalIdentity.toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PENDING_INTENT_FLAGS)
    }

    fun quickAi(context: Context, appWidgetId: Int): PendingIntent {
        val action = WidgetActionSpec.QuickAi(appWidgetId)
        val intent = Intent(WidgetActionIntentContract.ACTION_QUICK_AI).apply {
            component = ComponentName(context.packageName, QUICK_AI_ACTIVITY_CLASS_NAME)
            setPackage(context.packageName)
            data = action.canonicalIdentity.toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return activity(context, intent)
    }
}

private fun activity(context: Context, intent: Intent): PendingIntent = PendingIntent.getActivity(
    context,
    REQUEST_CODE,
    intent,
    PENDING_INTENT_FLAGS
)

private const val REQUEST_CODE = 0
private const val PENDING_INTENT_FLAGS =
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
