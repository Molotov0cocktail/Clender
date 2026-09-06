package com.molotov.clender.domain.sync

import com.molotov.clender.core.model.Event
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class RemoteSnapshot(val events: List<Event>, val etag: String?, val exists: Boolean)

enum class SyncFailureKind {
    CONFLICT,
    DOCUMENT,
    TRANSPORT,
    UNKNOWN
}

interface BoundedSyncFailure {
    val syncFailureKind: SyncFailureKind
    val statusCode: Int?
}

class WebDavConflictException(message: String, override val statusCode: Int? = null) :
    RuntimeException(message),
    BoundedSyncFailure {
    override val syncFailureKind: SyncFailureKind = SyncFailureKind.CONFLICT
}

class SyncFailureException(
    val remoteVisibleChanged: Boolean,
    val kind: SyncFailureKind,
    val httpStatusCode: Int?
) : RuntimeException(failureMessage(kind, httpStatusCode))

class SyncCancellationException(val remoteVisibleChanged: Boolean) :
    CancellationException("Synchronization cancelled after applying remote changes")

interface SyncRecordStore {
    suspend fun snapshot(): List<Event>

    suspend fun applyRemote(events: List<Event>): Boolean
}

interface WebDavRemote {
    suspend fun fetch(): RemoteSnapshot

    suspend fun put(payload: ByteArray, etag: String?, exists: Boolean)
}

data class SyncResult(val localChanged: Boolean, val uploaded: Boolean, val eventCount: Int)

class SyncService(
    private val store: SyncRecordStore,
    private val remote: WebDavRemote,
    private val codec: WebDavDocumentCodec = WebDavDocumentCodec(),
    private val mergeEngine: MergeEngine = MergeEngine(codec)
) {
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    suspend fun sync(): SyncResult {
        var localChanged = false
        try {
            repeat(MAX_ATTEMPTS) { attempt ->
                coroutineContext.ensureActive()
                val snapshot = remote.fetch()
                val merged = mergeEngine.merge(store.snapshot(), snapshot.events)
                coroutineContext.ensureActive()
                withContext(NonCancellable) {
                    if (store.applyRemote(merged)) localChanged = true
                }
                coroutineContext.ensureActive()

                val uploadRecords = mergeEngine.merge(store.snapshot(), merged)
                val payload = codec.encode(uploadRecords)
                if (snapshot.exists && payload.contentEquals(codec.encode(snapshot.events))) {
                    return SyncResult(localChanged, uploaded = false, uploadRecords.size)
                }
                try {
                    remote.put(payload, snapshot.etag, snapshot.exists)
                    return SyncResult(localChanged, uploaded = true, uploadRecords.size)
                } catch (error: WebDavConflictException) {
                    if (attempt == MAX_ATTEMPTS - 1) throw error
                }
            }
            throw WebDavConflictException("WebDAV document changed repeatedly")
        } catch (error: CancellationException) {
            if (localChanged) throw SyncCancellationException(remoteVisibleChanged = true)
            throw error
        } catch (error: Exception) {
            if (localChanged) {
                val bounded = error as? BoundedSyncFailure
                throw SyncFailureException(
                    remoteVisibleChanged = true,
                    kind = bounded?.syncFailureKind ?: SyncFailureKind.UNKNOWN,
                    httpStatusCode = bounded?.statusCode
                )
            }
            throw error
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}

private fun failureMessage(kind: SyncFailureKind, statusCode: Int?): String = buildString {
    append("Synchronization ")
    append(kind.name)
    append(" failure after applying remote changes")
    if (statusCode != null) append(" (HTTP $statusCode)")
}
