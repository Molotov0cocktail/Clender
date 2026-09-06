package com.molotov.clender.widget

import com.molotov.clender.domain.widget.WidgetActionSpec
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel

/** Applies an already validated Widget edit entry without performing event I/O. */
object WidgetEditEntryRouter {
    fun route(shell: AppShellViewModel, entry: WidgetActionSpec.EditEvent) {
        shell.navigateTo(AppDestination.EVENTS)
        shell.pushRoute(AppRoute.EventDetail(entry.eventId))
    }
}
