package com.molotov.clender.ui.navigation

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationContractTest {
    @Test
    fun drawerContainsExactlyFiveStableTopLevelDestinations() {
        assertEquals(
            listOf(
                AppDestination.CALENDAR,
                AppDestination.EVENTS,
                AppDestination.AI,
                AppDestination.SETTINGS,
                AppDestination.ABOUT
            ),
            DrawerNavigation.destinations
        )
        assertEquals(
            listOf("calendar", "events", "ai", "settings", "about"),
            DrawerNavigation.destinations.map(AppDestination::route)
        )
    }

    @Test
    fun datedAndConversationRoutesUseOnlyExplicitNonSensitiveIdentifiers() {
        assertEquals(
            "events?date=2026-08-09",
            AppRouteFactory.events(LocalDate.of(2026, 8, 9))
        )
        assertEquals("ai", AppRouteFactory.ai(conversationId = null))
        assertEquals("ai?conversation=draft-42", AppRouteFactory.ai("draft-42"))
        assertEquals(
            "ai?conversation=conversation-%E4%B8%80+%26%3F",
            AppRouteFactory.ai("conversation-一 &?")
        )
    }

    @Test
    fun backClosesDrawerBeforePoppingPageThenDefersAtRoot() {
        assertEquals(
            BackNavigationAction.CLOSE_DRAWER,
            BackNavigationPolicy.decide(drawerOpen = true, canPopRoute = true)
        )
        assertEquals(
            BackNavigationAction.POP_ROUTE,
            BackNavigationPolicy.decide(drawerOpen = false, canPopRoute = true)
        )
        assertEquals(
            BackNavigationAction.DEFER_TO_SYSTEM,
            BackNavigationPolicy.decide(drawerOpen = false, canPopRoute = false)
        )
    }
}
