package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.FieldUpdate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReminderPolicyTest {
    private val parser = AiResponseParser()

    @Test
    fun acceptsExplicitNotificationAlarmAndTimerBoundariesForAddAndUpdate() {
        listOf(0, 1, 1440).forEach { minutes ->
            val fields =
                """"notification_enabled":false,"alarm_enabled":true,"timer_minutes":$minutes"""
            val add = """{"action":"add","event_type":"reminder","title":"Important",
                |"start_time":"2026-09-08 12:00",$fields}
            """.trimMargin()
            val update = """{"action":"update","event_id":1,$fields}"""
            val parsedAdd = parser.parse(add)
            val parsedUpdate = parser.parse(update)
            assertTrue(parsedAdd is AiParseResult.Operations)
            assertTrue(parsedUpdate is AiParseResult.Operations)
            val command = (
                (parsedAdd as AiParseResult.Operations).operations.single() as AiOperation.Add
                ).command
            val patch = (
                (parsedUpdate as AiParseResult.Operations).operations.single() as AiOperation.Update
                ).patch
            assertEquals(false, command.notificationEnabled)
            assertEquals(true, command.alarmEnabled)
            assertEquals(minutes, command.timerMinutes)
            assertEquals(FieldUpdate.Set(false), patch.notificationEnabled)
            assertEquals(FieldUpdate.Set(true), patch.alarmEnabled)
            assertEquals(FieldUpdate.Set(minutes), patch.timerMinutes)
        }
    }

    @Test
    fun scheduleContextShowsPersistedPolicyAndLegacyUpdateLeavesPolicyUnchanged() = runBlocking {
        val event = eventFixture().copy(
            notificationEnabled = false,
            alarmEnabled = true,
            timerMinutes = 15
        )
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val context = AiContextProvider(
            clock,
            VisibleScheduleSource {
                listOf(event)
            }
        ) { clock.zone }.build()
        assertTrue(context.contains("notification_enabled=false"))
        assertTrue(context.contains("alarm_enabled=true"))
        assertTrue(context.contains("timer_minutes=15"))
        val parsed = parser.parse("""{"action":"update","event_id":1,"title":"Renamed"}""")
            as AiParseResult.Operations
        val patch = (parsed.operations.single() as AiOperation.Update).patch
        assertEquals(FieldUpdate.Unchanged, patch.notificationEnabled)
        assertEquals(FieldUpdate.Unchanged, patch.alarmEnabled)
        assertEquals(FieldUpdate.Unchanged, patch.timerMinutes)
    }

    @Test
    fun invalidTypesAndOutOfRangeTimersRejectWholeEnvelope() {
        val invalidFields = listOf(
            "\"notification_enabled\":\"true\"",
            "\"notification_enabled\":1",
            "\"alarm_enabled\":null",
            "\"alarm_enabled\":\"false\"",
            "\"timer_minutes\":\"5\"",
            "\"timer_minutes\":true",
            "\"timer_minutes\":-1",
            "\"timer_minutes\":1441"
        )
        invalidFields.forEach { field ->
            val response = """{"operations":[{"action":"update","event_id":1,$field},
                |{"action":"reply","message":"claimed done"}]}
            """.trimMargin()
            assertTrue(field, parser.parse(response) is AiParseResult.Rejected)
        }
    }
}
