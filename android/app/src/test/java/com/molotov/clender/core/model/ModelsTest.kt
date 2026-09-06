package com.molotov.clender.core.model

import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test
    fun eventTypeWireValuesAreStrict() {
        assertEquals("reminder", EventType.REMINDER.wireValue)
        assertEquals("timespan", EventType.TIMESPAN.wireValue)
        assertEquals(EventType.REMINDER, EventType.fromWire("reminder"))
        assertThrows(IllegalArgumentException::class.java) { EventType.fromWire("REMINDER") }
        assertThrows(IllegalArgumentException::class.java) { EventType.fromWire("unknown") }
    }

    @Test
    fun domainModelsExposeNoJavaBeanSetters() {
        listOf(Event::class.java, Conversation::class.java, Message::class.java).forEach { type ->
            val setters = type.declaredMethods.filter { method ->
                method.name.startsWith("set") && method.parameterCount == 1
            }
            assertFalse("${type.simpleName} must be immutable", setters.isNotEmpty())
        }
    }

    @Test
    fun conversationAndMessagePreserveUnicodeAndTokenMetadata() {
        val created = Instant.parse("2026-08-07T01:02:03Z")
        val conversation = Conversation("conv-一", "计划 🌏", created, 42)
        val message = Message(7, conversation.id, MessageRole.THINK, "推理\n内容", created)

        assertEquals("计划 🌏", conversation.title)
        assertEquals(42, conversation.tokenCount)
        assertEquals(MessageRole.THINK, message.role)
        assertEquals("推理\n内容", message.content)
    }

    @Test
    fun eventCarriesAllDesktopSyncFieldsWithoutTimezoneConversion() {
        val start = LocalDateTime.of(2026, 8, 7, 23, 59)
        val event = eventFixture(startTime = start)

        assertEquals(start, event.startTime)
        assertEquals("0123456789abcdef0123456789abcdef", event.syncUid)
        assertEquals(Instant.parse("2026-08-07T00:00:00Z"), event.updatedAt)
    }
}

@Suppress("LongParameterList")
internal fun eventFixture(
    id: Long = 1,
    eventType: EventType = EventType.REMINDER,
    title: String = "事项",
    startTime: LocalDateTime = LocalDateTime.of(2026, 8, 7, 9, 0),
    endTime: LocalDateTime? = null,
    description: String = "",
    estimatedDurationMinutes: Int = 0,
    createdAt: Instant = Instant.parse("2026-08-07T00:00:00Z"),
    syncUid: String = "0123456789abcdef0123456789abcdef",
    updatedAt: Instant = createdAt,
    deletedAt: Instant? = null
): Event = Event(
    id = id,
    eventType = eventType,
    title = title,
    startTime = startTime,
    endTime = endTime,
    description = description,
    estimatedDurationMinutes = estimatedDurationMinutes,
    createdAt = createdAt,
    syncUid = syncUid,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)
