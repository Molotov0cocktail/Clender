package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AiContextProviderTimeZoneTest {
    private val originalTimeZone = TimeZone.getDefault()

    @After
    fun restoreSystemTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun twoArgumentProviderUsesShanghaiWallClockInsteadOfUtc() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertContext(
            provider("2026-09-06T11:58:49Z"),
            "2026-09-06 19:58",
            "Sunday"
        )
    }

    @Test
    fun localYearAndWeekdayCrossTogetherWhileClockInstantStaysUtc() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val instant = Instant.parse("2026-12-31T16:01:59Z")
        val clock = Clock.fixed(instant, ZoneOffset.UTC)
        val context = AiContextProvider(clock, VisibleScheduleSource { emptyList() })
        assertContext(context, "2027-01-01 00:01", "Friday")
        assertEquals(instant, clock.instant())
        assertEquals(ZoneOffset.UTC, clock.zone)
    }

    @Test
    fun existingProviderReadsNewSystemTimeZoneOnEveryBuild() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val context = provider("2026-09-06T00:30:00Z")
        assertContext(context, "2026-09-06 00:30", "Sunday")
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertContext(context, "2026-09-06 08:30", "Sunday")
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertContext(context, "2026-09-05 17:30", "Saturday")
    }

    @Test
    fun springDstTransitionSkipsNonexistentLocalHour() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertContext(provider("2026-03-08T06:59:00Z"), "2026-03-08 01:59", "Sunday")
        assertContext(provider("2026-03-08T07:00:00Z"), "2026-03-08 03:00", "Sunday")
    }

    @Test
    fun autumnDstTransitionRepeatsLocalHourWithoutChangingDate() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertContext(provider("2026-11-01T05:59:00Z"), "2026-11-01 01:59", "Sunday")
        assertContext(provider("2026-11-01T06:00:00Z"), "2026-11-01 01:00", "Sunday")
    }

    @Test
    fun minuteOffsetsAreNotRoundedToWholeHours() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val context = provider("2026-09-06T18:20:59Z")
        assertContext(context, "2026-09-06 23:50", "Sunday")
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
        assertContext(context, "2026-09-07 00:05", "Monday")
    }

    @Test
    fun suspendedQueryKeepsSingleSnapshotAndNextBuildReadsChangedTimeAndZone() = runBlocking {
        val clock = ContextCountingClock(Instant.parse("2026-12-31T16:01:00Z"))
        var zone: ZoneId = ZoneId.of("Asia/Shanghai")
        var zoneReads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val context = AiContextProvider(
            clock,
            VisibleScheduleSource {
                entered.complete(Unit)
                release.await()
                emptyList()
            }
        ) {
            zoneReads++
            zone
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { context.build() }
        try {
            entered.await()
            assertEquals(1, clock.reads)
            assertEquals(1, zoneReads)
            clock.current = Instant.parse("2027-01-02T00:30:00Z")
            zone = ZoneId.of("America/Los_Angeles")
            release.complete(Unit)
            assertEquals(
                "Current local date/time: 2027-01-01 00:01; weekday: Friday. " +
                    "zone: Asia/Shanghai; UTC offset: +08:00.\nVisible schedules:\n(none)",
                first.await()
            )
            assertEquals(1, clock.reads)
            assertEquals(1, zoneReads)
            assertContext(context, "2027-01-01 16:30", "Friday")
            assertEquals(2, clock.reads)
            assertEquals(2, zoneReads)
        } finally {
            release.complete(Unit)
            first.cancel()
        }
    }

    @Test
    fun scheduleFailureAndCancellationPropagateWithoutReturningStaleContext() {
        listOf(
            IllegalStateException("Synthetic failure"),
            CancellationException("Synthetic cancellation")
        )
            .forEach { failure ->
                val context = AiContextProvider(
                    Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
                    VisibleScheduleSource { throw failure }
                ) { ZoneId.of("Asia/Shanghai") }
                val thrown = assertThrows(failure.javaClass) {
                    runBlocking { context.build() }
                }
                assertSame(failure, thrown)
            }
    }

    @Test
    fun localScheduleFieldsStayUnconvertedSortedAndTombstonesRemainHidden() = runBlocking {
        val start = LocalDateTime.of(2026, 9, 6, 9, 0)
        val early = eventFixture(id = 1, title = "early", startTime = start)
        val later = eventFixture(
            id = 2,
            title = "later",
            eventType = EventType.TIMESPAN,
            startTime = start.plusHours(1),
            endTime = start.plusHours(2),
            description = "Synthetic hidden description"
        )
        val deleted = early.copy(id = 3, title = "deleted", deletedAt = Instant.EPOCH)
        val context = AiContextProvider(
            Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
            VisibleScheduleSource { listOf(later, deleted, early) }
        ) { ZoneId.of("Asia/Shanghai") }
        assertEquals(
            "Current local date/time: 2026-09-06 08:00; weekday: Sunday. " +
                "zone: Asia/Shanghai; UTC offset: +08:00.\nVisible schedules:\n" +
                "- id=1 type=reminder title=early start=2026-09-06 09:00 duration=0" +
                " notification_enabled=false alarm_enabled=false timer_minutes=0\n" +
                "- id=2 type=timespan title=later start=2026-09-06 10:00 " +
                "end=2026-09-06 11:00 duration=0" +
                " notification_enabled=false alarm_enabled=false timer_minutes=0",
            context.build()
        )
        assertEquals(start, early.startTime)
        assertEquals(start.plusHours(2), later.endTime)
    }

    private fun provider(instant: String) = AiContextProvider(
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
        VisibleScheduleSource { emptyList() }
    )

    private suspend fun assertContext(
        context: AiContextProvider,
        dateTime: String,
        weekday: String
    ) {
        assertEquals(
            "Current local date/time: $dateTime; weekday: $weekday.\nVisible schedules:\n(none)",
            context.build().replace(Regex(" zone: [^\\n]+"), "")
        )
    }
}

private class ContextCountingClock(var current: Instant) : Clock() {
    var reads = 0

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)

    override fun instant(): Instant {
        reads++
        return current
    }
}
