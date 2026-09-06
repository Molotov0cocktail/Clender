package com.molotov.clender.ui.app

import androidx.annotation.StringRes
import com.molotov.clender.R
import com.molotov.clender.ui.event.EventCrudRoute
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute

internal fun pageStateKey(model: AppContentModel): String = when (
    val route = model.shell.childRoutes.lastOrNull()
) {
    is AppRoute.EventDetail -> "event-detail-${route.id}"
    is AppRoute.NewEvent -> "event-new-${route.date}"
    AppRoute.QuickAi -> "quick-ai"
    null -> "destination-${model.shell.destination.route}"
    else -> "route-${route.route}"
}

@StringRes
internal fun screenTitle(model: AppContentModel): Int = when (
    model.shell.childRoutes.lastOrNull()
) {
    is AppRoute.EventDetail -> if (model.eventCrud.route is EventCrudRoute.Edit) {
        R.string.screen_event_edit
    } else {
        R.string.screen_event_detail
    }

    is AppRoute.NewEvent -> R.string.screen_event_new

    AppRoute.QuickAi -> R.string.screen_quick_ai

    else -> when (model.shell.destination) {
        AppDestination.CALENDAR -> R.string.screen_calendar
        AppDestination.EVENTS -> R.string.screen_events
        AppDestination.AI -> R.string.screen_ai
        AppDestination.SETTINGS -> R.string.screen_settings
        AppDestination.ABOUT -> R.string.screen_about
    }
}
