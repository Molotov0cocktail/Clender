package com.molotov.clender.domain.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MergeEngineTest {
    private val engine = MergeEngine()

    @Test
    fun unionUsesNewestRecordPreservesTombstonesAndSortsByUid() {
        val older = Instant.parse("2026-08-03T01:00:00Z")
        val newer = Instant.parse("2026-08-03T02:00:00Z")
        val localOnly = syncEvent(uid = syncUid(3), title = "local")
        val localOld = syncEvent(uid = syncUid(2), title = "old", updatedAt = older)
        val remoteDelete = syncEvent(
            uid = syncUid(2),
            title = "deleted",
            updatedAt = newer,
            deletedAt = newer
        )
        val remoteOnly = syncEvent(uid = syncUid(1), title = "remote")

        val merged = engine.merge(listOf(localOnly, localOld), listOf(remoteDelete, remoteOnly))

        assertEquals(listOf(syncUid(1), syncUid(2), syncUid(3)), merged.map { it.syncUid })
        assertEquals(newer, merged.single { it.syncUid == syncUid(2) }.deletedAt)
    }

    @Test
    fun equalTimestampTieBreakUsesUnicodeCodePointsNotUtf16OrUtf8Ordering() {
        val stamp = Instant.parse("2026-08-03T02:00:00Z")
        val privateUseBmp = syncEvent(uid = syncUid(1), title = "\uE000", updatedAt = stamp)
        val nonBmpEmoji = syncEvent(uid = syncUid(1), title = "😀", updatedAt = stamp)

        assertEquals(
            "😀",
            engine.merge(listOf(privateUseBmp), listOf(nonBmpEmoji)).single().title
        )
        assertEquals(
            engine.merge(listOf(privateUseBmp), listOf(nonBmpEmoji)),
            engine.merge(listOf(nonBmpEmoji), listOf(privateUseBmp))
        )
    }

    @Test
    fun equalTimestampTieBreakMatchesPythonEscapesNullsAndIgnoresLocalId() {
        val stamp = Instant.parse("2026-08-03T02:00:00Z")
        val escapedNewlineWinner = syncEvent(
            uid = syncUid(1),
            title = "A\nZ",
            description = "中文",
            updatedAt = stamp
        ).copy(id = 42L)
        val rawTitleComparatorWouldChooseThis = syncEvent(
            uid = syncUid(1),
            title = "AA",
            description = "中文",
            updatedAt = stamp
        )

        val leftRight = engine.merge(
            listOf(escapedNewlineWinner),
            listOf(rawTitleComparatorWouldChooseThis)
        )
        val rightLeft = engine.merge(
            listOf(rawTitleComparatorWouldChooseThis),
            listOf(escapedNewlineWinner)
        )

        assertEquals(leftRight, rightLeft)
        assertEquals("A\nZ", leftRight.single().title)
        assertEquals(
            leftRight.map { it.copy(id = 0L) },
            WebDavDocumentCodec().decode(WebDavDocumentCodec().encode(leftRight))
        )
    }

    @Test
    fun duplicateUidWithinEitherSideRejectsWholeMerge() {
        val duplicate = syncEvent(uid = syncUid(1))

        assertThrows(WebDavDocumentException::class.java) {
            engine.merge(listOf(duplicate, duplicate.copy(title = "other")), emptyList())
        }
        assertThrows(WebDavDocumentException::class.java) {
            engine.merge(emptyList(), listOf(duplicate, duplicate.copy(title = "other")))
        }
    }
}
