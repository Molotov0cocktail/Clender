package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.molotov.clender.domain.widget.WidgetActionSpec

enum class WidgetQuickAiDestination {
    CONVERSATION,
    SETTINGS
}

data class WidgetQuickAiNavigation(val widgetId: Int, val destination: WidgetQuickAiDestination)

/** Exact, ownership-aware validation for internal Widget action intents. */
object WidgetActionIntentContract {
    const val ACTION_EDIT_EVENT = "com.molotov.clender.action.WIDGET_EDIT_EVENT"
    const val ACTION_LOCAL_REFRESH = "com.molotov.clender.action.WIDGET_LOCAL_REFRESH"
    const val ACTION_QUICK_AI = "com.molotov.clender.action.WIDGET_QUICK_AI"
    const val ACTION_QUICK_AI_OPEN_CONVERSATION =
        "com.molotov.clender.action.WIDGET_QUICK_AI_OPEN_CONVERSATION"
    const val ACTION_QUICK_AI_OPEN_SETTINGS =
        "com.molotov.clender.action.WIDGET_QUICK_AI_OPEN_SETTINGS"

    fun validateQuickAi(context: Context, intent: Intent): WidgetActionSpec.QuickAi? = validate(
        context = context,
        intent = intent,
        expectedAction = ACTION_QUICK_AI,
        expectedComponent = ComponentName(context.packageName, QUICK_AI_ACTIVITY_CLASS_NAME),
        // Task delivery may add BROUGHT_TO_FRONT with NEW_TASK, plus excludeFromRecents.
        expectedFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
            externalTaskFlags(intent) or
            (intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    ) as? WidgetActionSpec.QuickAi

    fun validateQuickAiNavigation(context: Context, intent: Intent): WidgetQuickAiNavigation? {
        val destination = when (intent.action) {
            ACTION_QUICK_AI_OPEN_CONVERSATION -> WidgetQuickAiDestination.CONVERSATION
            ACTION_QUICK_AI_OPEN_SETTINGS -> WidgetQuickAiDestination.SETTINGS
            else -> return null
        }
        val action = validate(
            context = context,
            intent = intent,
            expectedAction = navigationAction(destination),
            expectedComponent = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME),
            expectedFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ) as? WidgetActionSpec.QuickAi
        return action?.let { WidgetQuickAiNavigation(it.widgetId, destination) }
    }

    fun quickAiNavigationIntent(
        context: Context,
        widgetId: Int,
        destination: WidgetQuickAiDestination
    ): Intent {
        val identity = WidgetActionSpec.QuickAi(widgetId).canonicalIdentity
        return Intent(navigationAction(destination)).apply {
            component = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
            setPackage(context.packageName)
            data = identity.toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private fun navigationAction(destination: WidgetQuickAiDestination): String =
        when (destination) {
            WidgetQuickAiDestination.CONVERSATION -> ACTION_QUICK_AI_OPEN_CONVERSATION
            WidgetQuickAiDestination.SETTINGS -> ACTION_QUICK_AI_OPEN_SETTINGS
        }

    fun validateEditEvent(context: Context, intent: Intent): WidgetActionSpec.EditEvent? = validate(
        context = context,
        intent = intent,
        expectedAction = ACTION_EDIT_EVENT,
        expectedComponent = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME),
        expectedFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
            externalTaskFlags(intent)
    ) as? WidgetActionSpec.EditEvent

    fun validateLocalRefresh(context: Context, intent: Intent): WidgetActionSpec.LocalRefresh? =
        validate(
            context = context,
            intent = intent,
            expectedAction = ACTION_LOCAL_REFRESH,
            expectedComponent = ComponentName(
                context.packageName,
                LOCAL_REFRESH_RECEIVER_CLASS_NAME
            ),
            expectedFlags = 0
        ) as? WidgetActionSpec.LocalRefresh

    private fun validate(
        context: Context,
        intent: Intent,
        expectedAction: String,
        expectedComponent: ComponentName,
        expectedFlags: Int
    ): WidgetActionSpec? = intent
        .takeIf {
            matchesEnvelope(
                context,
                it,
                expectedAction,
                expectedComponent,
                expectedFlags
            )
        }
        ?.let(::exactWidgetId)
        ?.let { widgetId -> exactAction(intent, widgetId) }
        ?.takeIf { action -> isOwnedWidget(context, action.widgetId) }
}

private fun externalTaskFlags(intent: Intent): Int =
    if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) {
        Intent.FLAG_ACTIVITY_NEW_TASK or (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
    } else {
        0
    }

private fun matchesEnvelope(
    context: Context,
    intent: Intent,
    expectedAction: String,
    expectedComponent: ComponentName,
    expectedFlags: Int
): Boolean = listOf(
    intent.action == expectedAction,
    intent.component == expectedComponent,
    intent.`package` == context.packageName,
    intent.flags == expectedFlags,
    intent.selector == null,
    intent.clipData == null,
    intent.categories.isNullOrEmpty()
).all { it }

private fun exactWidgetId(intent: Intent): Int? = intent.extras
    ?.takeIf { it.keySet() == setOf(AppWidgetManager.EXTRA_APPWIDGET_ID) }
    ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
    ?.takeIf { it > 0 }

private fun exactAction(intent: Intent, widgetId: Int): WidgetActionSpec? = intent.dataString
    ?.let { rawIdentity ->
        WidgetActionSpec.parse(rawIdentity)?.takeIf { action ->
            action.widgetId == widgetId && action.canonicalIdentity == rawIdentity
        }
    }

private fun isOwnedWidget(context: Context, widgetId: Int): Boolean {
    val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider
    return provider == ComponentName(context, ClenderWidgetProvider::class.java)
}

internal const val MAIN_ACTIVITY_CLASS_NAME = "com.molotov.clender.app.MainActivity"
internal const val QUICK_AI_ACTIVITY_CLASS_NAME = "com.molotov.clender.widget.QuickAiActivity"
internal const val LOCAL_REFRESH_RECEIVER_CLASS_NAME =
    "com.molotov.clender.widget.WidgetLocalRefreshReceiver"
