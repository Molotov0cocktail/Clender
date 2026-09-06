package com.molotov.clender.domain.sync

import com.molotov.clender.core.model.Event

class MergeEngine(private val codec: WebDavDocumentCodec = WebDavDocumentCodec()) {
    fun merge(local: List<Event>, remote: List<Event>): List<Event> {
        val localRecords = codec.normalizeRecords(local)
        val remoteRecords = codec.normalizeRecords(remote)
        val merged = localRecords.associateByTo(mutableMapOf(), Event::syncUid)
        remoteRecords.forEach { candidate ->
            val current = merged[candidate.syncUid]
            if (current == null || candidateWins(candidate, current)) {
                merged[candidate.syncUid] = candidate
            }
        }
        return merged.toSortedMap().values.toList()
    }

    internal fun candidateWins(candidate: Event, current: Event): Boolean {
        require(candidate.syncUid == current.syncUid) {
            "Sync records must have the same UID before winner comparison"
        }
        val candidateCanonical = codec.canonicalRecord(candidate)
        val currentCanonical = codec.canonicalRecord(current)
        return when {
            candidate.updatedAt.isAfter(current.updatedAt) -> true
            candidate.updatedAt.isBefore(current.updatedAt) -> false
            else -> compareUnicodeCodePoints(candidateCanonical, currentCanonical) > 0
        }
    }
}

private fun compareUnicodeCodePoints(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftCodePoint = left.codePointAt(leftIndex)
        val rightCodePoint = right.codePointAt(rightIndex)
        if (leftCodePoint != rightCodePoint) return leftCodePoint.compareTo(rightCodePoint)
        leftIndex += Character.charCount(leftCodePoint)
        rightIndex += Character.charCount(rightCodePoint)
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}
