package com.molotov.clender.ui.navigation

import java.time.DateTimeException
import java.time.LocalDate

sealed interface AppRoute {
    val route: String

    data object Calendar : AppRoute {
        override val route: String = "calendar"
    }

    data object Events : AppRoute {
        override val route: String = "events"
    }

    data object Ai : AppRoute {
        override val route: String = "ai"
    }

    data object Settings : AppRoute {
        override val route: String = "settings"
    }

    data object About : AppRoute {
        override val route: String = "about"
    }

    data class EventDetail(val id: Int) : AppRoute {
        override val route: String = "event/$id"
    }

    data class NewEvent(val date: LocalDate) : AppRoute {
        override val route: String = "event/new?date=$date"
    }

    data object QuickAi : AppRoute {
        override val route: String = "quick-ai"
    }
}

object AppRouteParser {
    fun parse(raw: String?): AppRoute? {
        val value = raw?.trim().orEmpty()
        return when {
            value.isEmpty() || value.contains("://") -> null

            else -> when (value) {
                AppRoute.Calendar.route -> AppRoute.Calendar
                AppRoute.Events.route -> AppRoute.Events
                AppRoute.Ai.route -> AppRoute.Ai
                AppRoute.Settings.route -> AppRoute.Settings
                AppRoute.About.route -> AppRoute.About
                AppRoute.QuickAi.route -> AppRoute.QuickAi
                else -> parsePrefixed(value)
            }
        }
    }

    fun serialize(route: AppRoute): String = route.route

    private fun parsePrefixed(value: String): AppRoute? = when {
        value.startsWith("event/new") -> parseNewEvent(value)
        value.startsWith("event/") -> parseEventDetail(value)
        else -> null
    }

    private fun parseEventDetail(value: String): AppRoute? {
        val id = value.removePrefix("event/").toIntOrNull()
        return if (id != null && id > 0) AppRoute.EventDetail(id) else null
    }

    private fun parseNewEvent(value: String): AppRoute? {
        val dateValue = value
            .removePrefix("event/new?")
            .takeIf { it.isNotEmpty() && '&' !in it }
            ?.split("=", limit = 2)
            ?.takeIf { it.size == 2 && it[0] == "date" }
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
        return dateValue?.let(::parseStrictIsoDate)
    }

    private fun parseStrictIsoDate(value: String): AppRoute? = try {
        AppRoute.NewEvent(LocalDate.parse(value))
    } catch (_: DateTimeException) {
        null
    }
}
