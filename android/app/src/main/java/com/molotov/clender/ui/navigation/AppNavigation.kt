package com.molotov.clender.ui.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

enum class AppDestination(val route: String) {
    CALENDAR("calendar"),
    EVENTS("events"),
    AI("ai"),
    SETTINGS("settings"),
    ABOUT("about");

    companion object {
        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull {
            it.route == route
        }

        fun fromAppRoute(route: AppRoute): AppDestination? = when (route) {
            AppRoute.Calendar -> CALENDAR
            AppRoute.Events -> EVENTS
            AppRoute.Ai -> AI
            AppRoute.Settings -> SETTINGS
            AppRoute.About -> ABOUT
            else -> null
        }
    }
}

object DrawerNavigation {
    val destinations: List<AppDestination> = listOf(
        AppDestination.CALENDAR,
        AppDestination.EVENTS,
        AppDestination.AI,
        AppDestination.SETTINGS,
        AppDestination.ABOUT
    )
}

object AppRouteFactory {
    fun events(date: LocalDate): String = "${AppDestination.EVENTS.route}?date=$date"

    fun ai(conversationId: String?): String {
        val safeId = conversationId?.trim()?.takeIf(String::isNotEmpty)
        return if (safeId == null) {
            AppDestination.AI.route
        } else {
            val encodedId = URLEncoder.encode(safeId, StandardCharsets.UTF_8.name())
            "${AppDestination.AI.route}?conversation=$encodedId"
        }
    }
}

enum class BackNavigationAction {
    CLOSE_DRAWER,
    POP_ROUTE,
    DEFER_TO_SYSTEM
}

object BackNavigationPolicy {
    fun decide(drawerOpen: Boolean, canPopRoute: Boolean): BackNavigationAction = when {
        drawerOpen -> BackNavigationAction.CLOSE_DRAWER
        canPopRoute -> BackNavigationAction.POP_ROUTE
        else -> BackNavigationAction.DEFER_TO_SYSTEM
    }
}
