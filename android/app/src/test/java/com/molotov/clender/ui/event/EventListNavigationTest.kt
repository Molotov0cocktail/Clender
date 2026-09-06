package com.molotov.clender.ui.event

import com.molotov.clender.ui.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventListNavigationTest {
    @Test
    fun positiveRepresentableEventIdMapsToDetailRoute() {
        assertEquals(AppRoute.EventDetail(7), EventListNavigation.detailRoute(7L))
        assertEquals(
            AppRoute.EventDetail(Int.MAX_VALUE),
            EventListNavigation.detailRoute(Int.MAX_VALUE.toLong())
        )
    }

    @Test
    fun nonPositiveOrUnrepresentableEventIdFailsClosed() {
        listOf(Long.MIN_VALUE, -1L, 0L, Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE).forEach {
            assertNull(EventListNavigation.detailRoute(it))
        }
    }
}
