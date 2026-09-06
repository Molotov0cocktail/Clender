package com.molotov.clender.ui.navigation

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRouteParserTest {
    @Test
    fun allFiveTopLevelRoutesRoundTripThroughParserAndSerializer() {
        val routes = listOf(
            AppRoute.Calendar,
            AppRoute.Events,
            AppRoute.Ai,
            AppRoute.Settings,
            AppRoute.About
        )
        val wire = listOf("calendar", "events", "ai", "settings", "about")

        routes.zip(wire).forEach { (route, expectedRoute) ->
            assertEquals(expectedRoute, route.route)
            assertEquals(expectedRoute, AppRouteParser.serialize(route))
            assertEquals(route, AppRouteParser.parse(expectedRoute))
        }
    }

    @Test
    fun eventDetailRoundTripsForPositiveIds() {
        assertEquals("event/1", AppRoute.EventDetail(1).route)
        assertEquals("event/123", AppRoute.EventDetail(123).route)
        assertEquals(
            AppRoute.EventDetail(1),
            AppRouteParser.parse(AppRouteParser.serialize(AppRoute.EventDetail(1)))
        )
        assertEquals(
            AppRoute.EventDetail(123),
            AppRouteParser.parse(AppRouteParser.serialize(AppRoute.EventDetail(123)))
        )
    }

    @Test
    fun newEventAndQuickAiRoundTripWithExactWireFormats() {
        val newEvent = AppRoute.NewEvent(LocalDate.of(2026, 8, 9))
        assertEquals("event/new?date=2026-08-09", newEvent.route)
        assertEquals("event/new?date=2026-08-09", AppRouteParser.serialize(newEvent))
        assertEquals(newEvent, AppRouteParser.parse("event/new?date=2026-08-09"))

        assertEquals("quick-ai", AppRoute.QuickAi.route)
        assertEquals("quick-ai", AppRouteParser.serialize(AppRoute.QuickAi))
        assertEquals(AppRoute.QuickAi, AppRouteParser.parse("quick-ai"))
    }

    @Test
    fun eventIdMustBePositiveAndWithinIntRange() {
        assertEquals(
            AppRoute.EventDetail(Int.MAX_VALUE),
            AppRouteParser.parse("event/2147483647")
        )
        assertNull(AppRouteParser.parse("event/0"))
        assertNull(AppRouteParser.parse("event/-1"))
        assertNull(AppRouteParser.parse("event/2147483648"))
        assertNull(AppRouteParser.parse("event/abc"))
        assertNull(AppRouteParser.parse("event/"))
        assertNull(AppRouteParser.parse("event"))
    }

    @Test
    fun newEventDateMustBeStrictIsoAndValidCalendarDate() {
        assertEquals(
            AppRoute.NewEvent(LocalDate.of(2026, 8, 9)),
            AppRouteParser.parse("event/new?date=2026-08-09")
        )
        assertNull(AppRouteParser.parse("event/new?date=2026-8-9"))
        assertNull(AppRouteParser.parse("event/new?date=2026-08-9"))
        assertNull(AppRouteParser.parse("event/new?date=2026/08/09"))
        assertNull(AppRouteParser.parse("event/new?date=2026-02-30"))
        assertNull(AppRouteParser.parse("event/new?date=2026-13-01"))
    }

    @Test
    fun newEventRejectsMissingDateAndUnknownExtraParameters() {
        assertNull(AppRouteParser.parse("event/new"))
        assertNull(AppRouteParser.parse("event/new?date="))
        assertNull(AppRouteParser.parse("event/new?date=2026-08-09&foo=bar"))
    }

    @Test
    fun blankUnknownAndTopLevelQueryRoutesAreRejected() {
        assertNull(AppRouteParser.parse(null))
        assertNull(AppRouteParser.parse(""))
        assertNull(AppRouteParser.parse("   "))
        assertNull(AppRouteParser.parse("unknown"))
        assertNull(AppRouteParser.parse("calendar?x=1"))
        assertNull(AppRouteParser.parse("events?date=2026-08-09"))
    }

    @Test
    fun httpHttpsAndBrowsableSchemesAreRejectedAsRoutes() {
        assertNull(AppRouteParser.parse("http://example.com/calendar"))
        assertNull(AppRouteParser.parse("https://example.com/event/1"))
        assertNull(AppRouteParser.parse("browsable://calendar"))
        assertNull(AppRouteParser.parse("browsable://event/1"))
    }
}
