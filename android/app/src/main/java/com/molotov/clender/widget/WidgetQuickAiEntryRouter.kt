package com.molotov.clender.widget

import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellViewModel

/** Routes an already validated Widget entry using existing application destinations. */
object WidgetQuickAiEntryRouter {
    fun route(
        shell: AppShellViewModel,
        entry: WidgetQuickAiNavigation,
        showAiSettings: () -> Unit
    ) {
        when (entry.destination) {
            WidgetQuickAiDestination.CONVERSATION -> shell.navigateTo(AppDestination.AI)

            WidgetQuickAiDestination.SETTINGS -> {
                showAiSettings()
                shell.navigateTo(AppDestination.SETTINGS)
            }
        }
    }
}
