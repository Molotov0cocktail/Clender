package com.molotov.clender.data.network.webdav

import com.molotov.clender.domain.sync.RemoteSnapshot
import com.molotov.clender.domain.sync.SyncResult
import com.molotov.clender.domain.sync.WebDavConflictException
import com.molotov.clender.domain.sync.WebDavDocumentCodec
import com.molotov.clender.domain.sync.WebDavDocumentException
import com.molotov.clender.domain.sync.syncEvent
import com.molotov.clender.sync.SyncCoordinator
import com.molotov.clender.sync.SyncTrigger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    private lateinit var httpClient: OkHttpClient
    private lateinit var settings: WebDavSettings
    private val codec = WebDavDocumentCodec()

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        httpClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(30))
            .build()
        settings = WebDavSettings.create(
            directoryUrl = server.url("/calendar/root").toString(),
            username = "用户é"
        )
        client = WebDavClient(settings, httpClient, codec)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun settingsNormalizeSafeDirectoryPathsAndFixedRemoteFilename() {
        val unicode = WebDavSettings.create(
            "https://dav.example.test:8443/calendars/日 历/%E5%B7%B2%E7%BC%96%E7%A0%81",
            "user"
        )
        val ipv6 = WebDavSettings.create(
            "https://[2001:db8::1]:8443/root/",
            "user"
        )

        assertEquals(
            "https://dav.example.test:8443/calendars/%E6%97%A5%20%E5%8E%86/" +
                "%E5%B7%B2%E7%BC%96%E7%A0%81/",
            unicode.directoryUrl
        )
        assertEquals(
            unicode.directoryUrl + "clender-events.json",
            unicode.remoteUrl
        )
        assertEquals(
            "https://[2001:db8::1]:8443/root/clender-events.json",
            ipv6.remoteUrl
        )
    }

    @Test
    fun settingsRejectUnsafeOrAmbiguousUrlsAndCredentials() {
        val invalid = listOf(
            "http://dav.example.test/root",
            "https://user:pass@dav.example.test/root",
            "https://dav.example.test/root?query=1",
            "https://dav.example.test/root#fragment",
            "https://dav.example.test/root//child",
            "https://dav.example.test/root/../escape",
            "https://dav.example.test/root/%2e%2e/escape",
            "https://dav.example.test/root/%2Fescape"
        )
        invalid.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                WebDavSettings.create(url, "user")
            }
        }
        listOf("", " ", "user:name").forEach { username ->
            assertThrows(IllegalArgumentException::class.java) {
                WebDavSettings.create("https://dav.example.test/root", username)
            }
        }
    }

    @Test
    fun emptyOrNulPasswordIsRejectedBeforeNetworkAndCallerBufferIsCleared() {
        listOf(charArrayOf(), charArrayOf('\u0000', 'x')).forEach { password ->
            assertThrows(IllegalArgumentException::class.java) {
                client.openSession(password)
            }
            assertTrue(password.all { it == '\u0000' })
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun propfindUsesDepthZeroAndRfc7617Utf8BasicCredentials() = runBlocking {
        server.enqueue(MockResponse(code = 207))
        val password = testPassword()

        client.probe(password)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        val expected = "Basic " + Base64.getEncoder().encodeToString(
            "用户é:pässword".toByteArray(StandardCharsets.UTF_8)
        )
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.headers["Depth"])
        assertEquals(expected, request.headers["Authorization"])
        assertFalse(
            request.headers["Authorization"] ==
                "Basic " + Base64.getEncoder().encodeToString(
                    "用户é:pässword".toByteArray(StandardCharsets.ISO_8859_1)
                )
        )
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun probeAcceptsOnlyDesktopSuccessCodesAndBoundsErrors() {
        listOf(200, 207).forEach { status ->
            server.enqueue(MockResponse(code = status))
            runBlocking { client.probe(testPassword()) }
        }
        listOf(401, 403, 404, 500).forEach { status ->
            server.enqueue(MockResponse(code = status, body = "private-response-body"))
            val error = assertThrows(WebDavTransportException::class.java) {
                runBlocking { client.probe(testPassword()) }
            }
            assertTrue(error.message.orEmpty().length <= WebDavClient.MAX_ERROR_CHARS)
            assertFalse(error.message.orEmpty().contains("private-response-body"))
            assertFalse(error.message.orEmpty().contains("pässword"))
        }
    }

    @Test
    fun fetchTreatsFile404AsMissingAndReadsEtagOnSuccess() = runBlocking {
        server.enqueue(MockResponse(code = 404))
        val missingPassword = testPassword()
        assertEquals(RemoteSnapshot(emptyList(), null, false), fetch(missingPassword))
        assertTrue(missingPassword.all { it == '\u0000' })
        assertEquals("/calendar/root/clender-events.json", server.takeRequest().url.encodedPath)

        val events = listOf(syncEvent())
        server.enqueue(
            MockResponse(
                headers = headersOf("ETag", "\"version-1\""),
                body = codec.encode(events).decodeToString()
            )
        )
        val successPassword = testPassword()
        assertEquals(RemoteSnapshot(events, "\"version-1\"", true), fetch(successPassword))
        assertTrue(successPassword.all { it == '\u0000' })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/calendar/root/clender-events.json", request.url.encodedPath)
        assertEquals(expectedAuthorization(), request.headers["Authorization"])
    }

    @Test
    fun getAndPutNonSuccessResponsesNeverExposeBodyOrCredentials() {
        listOf(401, 403, 500).forEach { status ->
            server.enqueue(MockResponse(code = status, body = "private-get-body"))
            val password = testPassword()
            val error = assertThrows(WebDavTransportException::class.java) {
                runBlocking { fetch(password) }
            }
            assertSafeError(error, "private-get-body")
            assertTrue(password.all { it == '\u0000' })
        }
        listOf(401, 403, 404, 500).forEach { status ->
            server.enqueue(MockResponse(code = status, body = "private-put-body"))
            val password = testPassword()
            val error = assertThrows(WebDavTransportException::class.java) {
                runBlocking {
                    put(
                        codec.encode(emptyList()),
                        etag = null,
                        exists = false,
                        password = password
                    )
                }
            }
            assertSafeError(error, "private-put-body")
            assertTrue(password.all { it == '\u0000' })
        }
    }

    @Test
    fun conditionalPutUsesMatchForExistingAndNoneMatchForCreate() = runBlocking {
        server.enqueue(MockResponse(code = 204))
        val updatePayload = codec.encode(emptyList())
        val updatePassword = testPassword()
        put(updatePayload, etag = "\"v1\"", exists = true, password = updatePassword)
        assertTrue(updatePassword.all { it == '\u0000' })
        val update = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", update.method)
        assertEquals("/calendar/root/clender-events.json", update.url.encodedPath)
        assertEquals(expectedAuthorization(), update.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", update.headers["Content-Type"])
        assertEquals(updatePayload.decodeToString(), requireNotNull(update.body).utf8())
        assertEquals("\"v1\"", update.headers["If-Match"])
        assertEquals(null, update.headers["If-None-Match"])

        server.enqueue(MockResponse(code = 201))
        val createPassword = testPassword()
        put(
            codec.encode(emptyList()),
            etag = null,
            exists = false,
            password = createPassword
        )
        assertTrue(createPassword.all { it == '\u0000' })
        val create = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("*", create.headers["If-None-Match"])
        assertEquals(null, create.headers["If-Match"])
    }

    @Test
    fun existingPutWithoutEtagFailsBeforeNetworkAnd412IsConflict() {
        val earlyFailurePassword = testPassword()
        assertThrows(WebDavConflictException::class.java) {
            runBlocking {
                put(
                    codec.encode(emptyList()),
                    etag = null,
                    exists = true,
                    password = earlyFailurePassword
                )
            }
        }
        assertTrue(earlyFailurePassword.all { it == '\u0000' })
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse(code = 412))
        val conflictPassword = testPassword()
        assertThrows(WebDavConflictException::class.java) {
            runBlocking {
                put(
                    codec.encode(emptyList()),
                    etag = "\"old\"",
                    exists = true,
                    password = conflictPassword
                )
            }
        }
        assertTrue(conflictPassword.all { it == '\u0000' })
    }

    @Test
    fun sameAndCrossHostRedirectsAreRejectedWithoutForwardingCredentials() {
        listOf(
            server.url("/other").toString(),
            "https://other.example.test/stolen"
        ).forEach { location ->
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = headersOf("Location", location)
                )
            )
            val before = server.requestCount
            assertThrows(WebDavTransportException::class.java) {
                runBlocking { fetch() }
            }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    fun contentLengthAndStreamingByteCountBothEnforceFiveMebibytes() {
        val oversized = "x".repeat(WebDavDocumentCodec.MAX_DOCUMENT_BYTES + 1)
        server.enqueue(
            MockResponse(
                headers = headersOf("Connection", "close"),
                body = oversized
            )
        )
        assertThrows(WebDavDocumentException::class.java) {
            runBlocking { fetch() }
        }

        server.enqueue(
            MockResponse.Builder()
                .chunkedBody(oversized, 64 * 1024)
                .build()
        )
        assertThrows(WebDavDocumentException::class.java) {
            runBlocking { fetch() }
        }
    }

    @Test
    fun timeoutAndDisconnectAreBoundedTransportFailures() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val timeoutClient = WebDavClient(
            settings,
            httpClient.newBuilder().readTimeout(Duration.ofMillis(250)).build(),
            codec
        )
        val timeoutPassword = testPassword()
        assertThrows(WebDavTransportException::class.java) {
            runBlocking {
                timeoutClient.openSession(timeoutPassword).use { it.fetch() }
            }
        }
        assertTrue(timeoutPassword.all { it == '\u0000' })

        val disconnecting = OkHttpClient.Builder()
            .addInterceptor { throw IOException("disconnect private detail") }
            .build()
        val disconnectedClient = WebDavClient(settings, disconnecting, codec)
        val disconnectPassword = testPassword()
        assertThrows(WebDavTransportException::class.java) {
            runBlocking {
                disconnectedClient.openSession(disconnectPassword).use { it.fetch() }
            }
        }
        assertTrue(disconnectPassword.all { it == '\u0000' })
    }

    @Test
    fun coroutineCancellationCancelsARealInFlightGetCall() {
        assertRealCallCancellation { password -> fetch(password) }
    }

    @Test
    fun coroutineCancellationCancelsARealInFlightPutCall() {
        assertRealCallCancellation { password ->
            put(
                codec.encode(emptyList()),
                etag = null,
                exists = false,
                password = password
            )
        }
    }

    @Test
    fun coordinatorShutdownCancelsARealInFlightFetchAndClearsCredentials() = runBlocking {
        val password = testPassword()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = SyncCoordinator(
            scope = scope,
            isEnabled = { true },
            runSync = {
                client.openSession(password).use { it.fetch() }
                SyncResult(localChanged = false, uploaded = false, eventCount = 0)
            },
            onRemoteVisibleChanged = {}
        )
        try {
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

            assertTrue(coordinator.request(SyncTrigger.MANUAL))
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) { coordinator.shutdown() }

            assertTrue(password.all { it == '\u0000' })
            assertFalse(coordinator.request(SyncTrigger.FOREGROUND))
        } finally {
            coordinator.shutdown()
            scope.cancel()
        }
    }

    private fun assertSafeError(error: WebDavTransportException, privateBody: String) {
        assertTrue(error.message.orEmpty().length <= WebDavClient.MAX_ERROR_CHARS)
        assertFalse(error.message.orEmpty().contains(privateBody))
        assertFalse(error.message.orEmpty().contains("用户é"))
        assertFalse(error.message.orEmpty().contains("pässword"))
    }

    private fun assertRealCallCancellation(request: suspend (CharArray) -> Unit) = runBlocking {
        val password = testPassword()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val call = async(Dispatchers.Default) { request(password) }
        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        call.cancel()
        withTimeout(2_000) { call.join() }

        assertTrue(call.isCancelled)
        assertTrue(password.all { it == '\u0000' })
    }

    private suspend fun fetch(password: CharArray = testPassword()): RemoteSnapshot =
        client.openSession(password).use { it.fetch() }

    private suspend fun put(
        payload: ByteArray,
        etag: String?,
        exists: Boolean,
        password: CharArray = testPassword()
    ) {
        client.openSession(password).use { it.put(payload, etag, exists) }
    }

    private fun testPassword(): CharArray = "pässword".toCharArray()

    private fun expectedAuthorization(): String = "Basic " + Base64.getEncoder().encodeToString(
        "用户é:pässword".toByteArray(StandardCharsets.UTF_8)
    )
}

private fun headersOf(name: String, value: String): Headers =
    Headers.Builder().add(name, value).build()
