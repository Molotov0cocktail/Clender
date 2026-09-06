package com.molotov.clender.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetActionSpecTest {
    @Test
    fun allFourActionsUseExactCanonicalIdentityAndRoundTrip() {
        val cases = listOf(
            WidgetActionSpec.EditEvent(7, 11) to
                "clender-internal://widget/7/edit/11",
            WidgetActionSpec.QuickAi(7) to
                "clender-internal://widget/7/quick-ai",
            WidgetActionSpec.LocalRefresh(7) to
                "clender-internal://widget/7/refresh",
            WidgetActionSpec.Configure(7) to
                "clender-internal://widget/7/configure"
        )

        cases.forEach { (action, expectedIdentity) ->
            assertEquals(expectedIdentity, action.canonicalIdentity)
            assertEquals(action, WidgetActionSpec.parse(expectedIdentity))
        }
    }

    @Test
    fun canonicalIdentityPreservesPositiveAndMaximumWidgetAndEventIds() {
        val cases = listOf(
            WidgetActionSpec.EditEvent(1, 1) to
                "clender-internal://widget/1/edit/1",
            WidgetActionSpec.EditEvent(Int.MAX_VALUE, Int.MAX_VALUE) to
                "clender-internal://widget/2147483647/edit/2147483647",
            WidgetActionSpec.QuickAi(Int.MAX_VALUE) to
                "clender-internal://widget/2147483647/quick-ai",
            WidgetActionSpec.LocalRefresh(1) to
                "clender-internal://widget/1/refresh",
            WidgetActionSpec.Configure(Int.MAX_VALUE) to
                "clender-internal://widget/2147483647/configure"
        )

        cases.forEach { (action, expectedIdentity) ->
            assertEquals(expectedIdentity, action.canonicalIdentity)
            assertEquals(action, WidgetActionSpec.parse(expectedIdentity))
        }

        val identities = cases.map { it.second }
        assertEquals(identities.size, identities.toSet().size)
        assertEquals(
            WidgetActionSpec.EditEvent(1, 1),
            WidgetActionSpec.parse("clender-internal://widget/1/edit/1")
        )
        assertEquals(
            WidgetActionSpec.EditEvent(Int.MAX_VALUE, Int.MAX_VALUE),
            WidgetActionSpec.parse("clender-internal://widget/2147483647/edit/2147483647")
        )
    }

    @Test
    fun parserRejectsBlankHttpAndNonCanonicalPathQueryOrFragment() {
        listOf(
            "",
            " ",
            "\t\n",
            " http://example.invalid/widget/7/edit/11 ",
            "https://example.invalid/widget/7/edit/11",
            "browsable://widget/7/edit/11",
            "clender-internal://widget/7/unknown/11",
            "clender-internal://widget/7/edit",
            "clender-internal://widget/7/edit/11/extra",
            "clender-internal://widget/7/edit/11/",
            "clender-internal://widget/7/edit/11?source=widget",
            "clender-internal://widget/7/edit/11#fragment",
            "clender-internal://widget/7/quick-ai?source=widget",
            "clender-internal://widget/7/refresh#fragment",
            "clender-internal://widget/7/configure?source=widget"
        ).forEach { raw ->
            assertNull("must reject non-canonical identity: $raw", WidgetActionSpec.parse(raw))
        }
    }

    @Test
    fun parserRejectsZeroNegativeOverflowAndNonNumericWidgetIds() {
        listOf("0", "-1", "2147483648", "not-a-number").forEach { widgetId ->
            listOf(
                "clender-internal://widget/$widgetId/edit/11",
                "clender-internal://widget/$widgetId/quick-ai",
                "clender-internal://widget/$widgetId/refresh",
                "clender-internal://widget/$widgetId/configure"
            ).forEach { raw ->
                assertNull("must reject invalid widget id: $raw", WidgetActionSpec.parse(raw))
            }
        }
    }

    @Test
    fun parserRejectsZeroNegativeOverflowAndNonNumericEditEventIds() {
        listOf("0", "-1", "2147483648", "not-a-number").forEach { eventId ->
            val raw = "clender-internal://widget/7/edit/$eventId"
            assertNull("must reject invalid event id: $raw", WidgetActionSpec.parse(raw))
        }
    }
}
