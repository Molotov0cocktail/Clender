package com.molotov.clender.domain.widget

import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshPolicyTest {
    private val configuredWidgetIds = setOf(1, 7, Int.MAX_VALUE)
    private val fixedClock = Clock.fixed(
        Instant.parse("2026-08-07T01:02:03Z"),
        ZoneOffset.UTC
    )

    @Test
    fun refreshTriggerEnumContainsExactlyTheSevenFrozenTriggers() {
        assertEquals(
            setOf(
                WidgetRefreshTrigger.LOCAL_MUTATION,
                WidgetRefreshTrigger.REMOTE_VISIBLE_CHANGE,
                WidgetRefreshTrigger.CONFIGURATION_CHANGE,
                WidgetRefreshTrigger.FOREGROUND,
                WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH,
                WidgetRefreshTrigger.DATE_BOUNDARY,
                WidgetRefreshTrigger.BOOT
            ),
            WidgetRefreshTrigger.entries.toSet()
        )
    }

    @Test
    fun mutationRemoteForegroundDateBoundaryAndBootRefreshAllConfiguredWidgets() {
        listOf(
            WidgetRefreshTrigger.LOCAL_MUTATION,
            WidgetRefreshTrigger.REMOTE_VISIBLE_CHANGE,
            WidgetRefreshTrigger.FOREGROUND,
            WidgetRefreshTrigger.DATE_BOUNDARY,
            WidgetRefreshTrigger.BOOT
        ).forEach { trigger ->
            val plan = WidgetRefreshPolicy.plan(
                trigger = trigger,
                configuredWidgetIds = configuredWidgetIds,
                clock = fixedClock,
                zoneId = ZoneOffset.UTC
            )

            assertEquals(configuredWidgetIds, plan.targetWidgetIds.toSet())
            assertNull(plan.failure)
        }
    }

    @Test
    fun configurationAndManualRefreshOnlyTargetTheRequestedConfiguredWidget() {
        listOf(
            WidgetRefreshTrigger.CONFIGURATION_CHANGE,
            WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH
        ).forEach { trigger ->
            val plan = WidgetRefreshPolicy.plan(
                trigger = trigger,
                configuredWidgetIds = configuredWidgetIds,
                targetWidgetId = 7,
                clock = fixedClock,
                zoneId = ZoneOffset.UTC
            )

            assertEquals(setOf(7), plan.targetWidgetIds.toSet())
            assertNull(plan.failure)
        }
    }

    @Test
    fun bootWithNoConfiguredWidgetsIsAnExplicitNoOp() {
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.BOOT,
            configuredWidgetIds = emptySet(),
            clock = fixedClock,
            zoneId = ZoneOffset.UTC
        )

        assertTrue(plan.targetWidgetIds.isEmpty())
        assertNull(plan.dateBoundarySchedule)
        assertNull(plan.failure)
    }

    @Test
    fun dateBoundaryPlanIsAppWideOneTimeReplaceWithFixedWorkIdentity() {
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = configuredWidgetIds,
            clock = Clock.fixed(
                Instant.parse("2026-08-07T23:59:30Z"),
                ZoneOffset.UTC
            ),
            zoneId = ZoneOffset.UTC
        )
        val schedule = requireNotNull(plan.dateBoundarySchedule)

        assertEquals("clender-widget-date-boundary", schedule.uniqueWorkName)
        assertTrue(schedule.appWide)
        assertTrue(schedule.oneTime)
        assertTrue(schedule.replaceExisting)
        assertTrue(schedule.delay > Duration.ZERO)
        assertNull(plan.failure)
    }

    @Test
    fun utcBoundaryUsesInjectedClockAndTheNextLocalMidnight() {
        val now = Instant.parse("2026-08-07T23:59:30Z")
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC
        )

        assertEquals(
            Duration.ofSeconds(30),
            requireNotNull(plan.dateBoundarySchedule).delay
        )
    }

    @Test
    fun ordinaryTimeZoneBoundaryUsesLocalMidnightRatherThanUtcMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-08-07T15:59:30Z")
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = Clock.fixed(now, zone),
            zoneId = zone
        )

        assertEquals(
            Duration.ofSeconds(30),
            requireNotNull(plan.dateBoundarySchedule).delay
        )
    }

    @Test
    fun exactMidnightSchedulesTheFollowingMidnightWithAFullPositiveDay() {
        val now = Instant.parse("2026-08-07T00:00:00Z")
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC
        )

        assertEquals(
            Duration.ofDays(1),
            requireNotNull(plan.dateBoundarySchedule).delay
        )
        assertTrue(requireNotNull(plan.dateBoundarySchedule).delay > Duration.ZERO)
    }

    @Test
    fun dstGapAndOverlapUseTheResolvedNextLocalMidnight() {
        val zone = ZoneId.of("America/New_York")
        val cases = listOf(
            Instant.parse("2026-03-08T06:59:30Z") to
                Instant.parse("2026-03-09T04:00:00Z"),
            Instant.parse("2026-11-01T05:59:30Z") to
                Instant.parse("2026-11-02T05:00:00Z")
        )

        cases.forEach { (now, expectedBoundary) ->
            val plan = WidgetRefreshPolicy.plan(
                trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
                configuredWidgetIds = setOf(1),
                clock = Clock.fixed(now, zone),
                zoneId = zone
            )

            val delay = requireNotNull(plan.dateBoundarySchedule).delay
            assertEquals(Duration.between(now, expectedBoundary), delay)
            assertTrue(delay > Duration.ZERO)
        }
    }

    @Test
    fun skippedMidnightGapUsesAtStartOfDayResolutionAndRemainsStrictlyPositive() {
        val zone = ZoneId.of("Pacific/Apia")
        val now = Instant.parse("2011-12-30T09:59:30Z")
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = Clock.fixed(now, zone),
            zoneId = zone
        )

        assertEquals(
            Duration.ofSeconds(30),
            requireNotNull(plan.dateBoundarySchedule).delay
        )
    }

    @Test
    fun nonPositiveConfigurationTargetsFailClosedWithoutAWorkSchedule() {
        listOf(0, -1).forEach { targetWidgetId ->
            val plan = WidgetRefreshPolicy.plan(
                trigger = WidgetRefreshTrigger.CONFIGURATION_CHANGE,
                configuredWidgetIds = configuredWidgetIds,
                targetWidgetId = targetWidgetId,
                clock = fixedClock,
                zoneId = ZoneOffset.UTC
            )

            assertTrue(plan.targetWidgetIds.isEmpty())
            assertNull(plan.dateBoundarySchedule)
            assertNotNull(plan.failure)
        }
    }

    @Test
    fun instantAndLocalDateOverflowReturnFiniteFailureInsteadOfThrowingOrLeakingDetails() {
        val instantOverflow = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = Clock.fixed(Instant.MAX, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC
        )
        val dateOverflowInstant = LocalDate.MAX
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .minusSeconds(1)
        val dateOverflow = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = Clock.fixed(dateOverflowInstant, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC
        )

        listOf(instantOverflow, dateOverflow).forEach { plan ->
            assertTrue(plan.targetWidgetIds.isEmpty())
            assertNull(plan.dateBoundarySchedule)
            assertNotNull(plan.failure)
        }
    }

    @Test
    fun clockFailureReturnsFiniteFailureWithoutPropagatingExceptionBody() {
        val plan = WidgetRefreshPolicy.plan(
            trigger = WidgetRefreshTrigger.DATE_BOUNDARY,
            configuredWidgetIds = setOf(1),
            clock = ThrowingClock,
            zoneId = ZoneOffset.UTC
        )

        assertTrue(plan.targetWidgetIds.isEmpty())
        assertNull(plan.dateBoundarySchedule)
        assertNotNull(plan.failure)
        assertFalse(plan.failure.toString().contains("clock failure", ignoreCase = true))
    }

    private object ThrowingClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = throw DateTimeException("clock failure: hidden")
    }
}
