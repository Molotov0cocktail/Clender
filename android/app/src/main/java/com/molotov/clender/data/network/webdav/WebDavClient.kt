package com.molotov.clender.data.network.webdav

import com.molotov.clender.data.settings.WebDavSettingsPolicy
import com.molotov.clender.domain.sync.BoundedSyncFailure
import com.molotov.clender.domain.sync.RemoteSnapshot
import com.molotov.clender.domain.sync.SyncFailureKind
import com.molotov.clender.domain.sync.WebDavConflictException
import com.molotov.clender.domain.sync.WebDavDocumentCodec
import com.molotov.clender.domain.sync.WebDavDocumentException
import com.molotov.clender.domain.sync.WebDavRemote
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

private const val USER_AGENT = "Clender-Android/1.0"
private const val READ_CHUNK_BYTES = 64 * 1024L
private const val DOWNLOAD_DEADLINE_SECONDS = 20L
private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_NO_CONTENT = 204
private const val HTTP_MULTI_STATUS = 207
private const val HTTP_REDIRECT_MIN = 300
private const val HTTP_REDIRECT_MAX = 399
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_PRECONDITION_FAILED = 412

open class WebDavException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class WebDavTransportException(
    message: String,
    cause: Throwable? = null,
    override val statusCode: Int? = null
) : WebDavException(message, cause),
    BoundedSyncFailure {
    override val syncFailureKind: SyncFailureKind = SyncFailureKind.TRANSPORT
}

class WebDavSettings private constructor(
    val directoryUrl: String,
    val username: String,
    val remoteUrl: String
) {
    companion object {
        fun create(directoryUrl: String, username: String): WebDavSettings {
            val validated = WebDavSettingsPolicy.validate(directoryUrl, username)
            return WebDavSettings(
                directoryUrl = validated.directoryUrl,
                username = validated.username,
                remoteUrl = validated.remoteUrl
            )
        }
    }
}

class WebDavClient(
    private val settings: WebDavSettings,
    httpClient: OkHttpClient,
    private val codec: WebDavDocumentCodec = WebDavDocumentCodec()
) {
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun probe(password: CharArray) {
        openSession(password).use { session -> session.probe() }
    }

    fun openSession(password: CharArray): WebDavSession {
        if (password.isEmpty() || password.any { it == '\u0000' }) {
            password.fill('\u0000')
            throw IllegalArgumentException("WebDAV password is required")
        }
        return WebDavSession(settings, client, codec, password)
    }

    companion object {
        const val MAX_ERROR_CHARS: Int = 512
    }
}

class WebDavSession internal constructor(
    private val settings: WebDavSettings,
    private val client: OkHttpClient,
    private val codec: WebDavDocumentCodec,
    private val password: CharArray
) : WebDavRemote,
    AutoCloseable {
    @Volatile
    private var closed = false

    suspend fun probe() {
        val request = requestBuilder(settings.directoryUrl)
            .method("PROPFIND", null)
            .header("Depth", "0")
            .build()
        execute(request) { response ->
            if (response.code !in setOf(HTTP_OK, HTTP_MULTI_STATUS)) {
                throwHttp(response.code, "probe")
            }
        }
    }

    override suspend fun fetch(): RemoteSnapshot {
        val request = requestBuilder(settings.remoteUrl).get().build()
        return execute(request) { response ->
            when (response.code) {
                HTTP_OK -> RemoteSnapshot(
                    events = codec.decode(readLimited(response)),
                    etag = response.header("ETag"),
                    exists = true
                )

                HTTP_NOT_FOUND -> RemoteSnapshot(emptyList(), null, exists = false)

                else -> throwHttp(response.code, "read")
            }
        }
    }

    override suspend fun put(payload: ByteArray, etag: String?, exists: Boolean) {
        ensureOpen()
        if (exists && etag.isNullOrEmpty()) {
            throw WebDavConflictException("WebDAV document is missing an ETag")
        }
        val builder = requestBuilder(settings.remoteUrl)
            .put(payload.toRequestBody(JSON_MEDIA_TYPE))
        if (exists) {
            builder.header("If-Match", checkNotNull(etag))
        } else {
            builder.header("If-None-Match", "*")
        }
        execute(builder.build()) { response ->
            when (response.code) {
                HTTP_OK, HTTP_CREATED, HTTP_NO_CONTENT -> Unit

                HTTP_PRECONDITION_FAILED -> throw WebDavConflictException(
                    "WebDAV document changed concurrently",
                    statusCode = HTTP_PRECONDITION_FAILED
                )

                else -> throwHttp(response.code, "write")
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            password.fill('\u0000')
        }
    }

    private fun requestBuilder(url: String): Request.Builder {
        ensureOpen()
        val passwordString = String(password)
        val authorization = Credentials.basic(
            settings.username,
            passwordString,
            StandardCharsets.UTF_8
        )
        return Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("User-Agent", USER_AGENT)
    }

    private fun ensureOpen() {
        check(!closed) { "WebDAV session is closed" }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> execute(request: Request, handler: (Response) -> T): T {
        ensureOpen()
        val call = client.newCall(request).apply {
            timeout().timeout(DOWNLOAD_DEADLINE_SECONDS, TimeUnit.SECONDS)
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                WebDavTransportException("WebDAV network request failed", e)
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        try {
                            response.use { value ->
                                val result = handler(value)
                                if (continuation.isActive) continuation.resume(result)
                            }
                        } catch (error: WebDavConflictException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } catch (error: WebDavException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } catch (error: WebDavDocumentException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } catch (error: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    WebDavTransportException(
                                        "WebDAV response could not be read",
                                        error
                                    )
                                )
                            }
                        } catch (error: RuntimeException) {
                            resumeHandlingFailure(continuation, error)
                        }
                    }
                }
            )
        }
    }

    private fun readLimited(response: Response): ByteArray {
        val body = response.body
        val contentLength = body.contentLength()
        requireAllowedContentLength(contentLength)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DOWNLOAD_DEADLINE_SECONDS)
        val output = ByteArrayOutputStream(
            contentLength.coerceAtLeast(0L)
                .coerceAtMost(WebDavDocumentCodec.MAX_DOCUMENT_BYTES.toLong())
                .toInt()
        )
        val buffer = Buffer()
        var total = 0L
        val source = body.source()
        while (true) {
            requireBeforeDeadline(deadline)
            val count = source.read(buffer, READ_CHUNK_BYTES)
            if (count == -1L) break
            total += count
            requireAllowedDownloadedBytes(total)
            output.write(buffer.readByteArray(count))
        }
        return output.toByteArray()
    }

    private fun throwHttp(statusCode: Int, operation: String): Nothing {
        val category = when (statusCode) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "authorization"
            in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX -> "redirect"
            else -> operation
        }
        val message = "WebDAV $category failed (HTTP $statusCode)".take(
            WebDavClient.MAX_ERROR_CHARS
        )
        throw WebDavTransportException(message, statusCode = statusCode)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun <T> resumeHandlingFailure(
    continuation: kotlinx.coroutines.CancellableContinuation<T>,
    error: RuntimeException
) {
    if (continuation.isActive) {
        continuation.resumeWithException(
            WebDavTransportException("WebDAV response handling failed", error)
        )
    }
}

private fun requireAllowedContentLength(contentLength: Long) {
    if (contentLength > WebDavDocumentCodec.MAX_DOCUMENT_BYTES) {
        throw WebDavDocumentException("WebDAV document exceeds the size limit")
    }
}

private fun requireBeforeDeadline(deadline: Long) {
    if (System.nanoTime() > deadline) {
        throw WebDavTransportException("WebDAV response timed out")
    }
}

private fun requireAllowedDownloadedBytes(total: Long) {
    if (total > WebDavDocumentCodec.MAX_DOCUMENT_BYTES) {
        throw WebDavDocumentException("WebDAV document exceeds the size limit")
    }
}
