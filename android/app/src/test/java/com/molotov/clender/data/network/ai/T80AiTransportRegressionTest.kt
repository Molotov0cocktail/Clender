package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.diagnostics.DiagnosticLogger
import com.molotov.clender.diagnostics.DiagnosticPhase
import com.molotov.clender.diagnostics.DiagnosticRecord
import com.molotov.clender.diagnostics.DiagnosticResult
import com.molotov.clender.domain.ai.AiRequestMessage
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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

class T80AiTransportRegressionTest {
    private lateinit var server: MockWebServer
    private lateinit var trustedHttpClient: OkHttpClient
    private lateinit var client: OkHttpAiClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        trustedHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        client = OkHttpAiClient(trustedHttpClient)
    }

    @After
    fun tearDown() {
        client.cancelInFlight()
        server.close()
        trustedHttpClient.connectionPool.evictAll()
        trustedHttpClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun diagnosticsIdentifyResponseBodyTimeoutWithoutRecordingPrivateContent() {
        val records = Collections.synchronizedList(mutableListOf<DiagnosticRecord>())
        val observed = OkHttpAiClient(
            trustedHttpClient,
            timeoutPolicy = AiTimeoutPolicy(
                chatRead = Duration.ofMillis(100),
                chatCall = Duration.ofSeconds(2)
            ),
            diagnostics = DiagnosticLogger { records.add(it) }
        )
        server.enqueue(
            MockResponse.Builder().body("PRIVATE_RESPONSE_SENTINEL")
                .bodyDelay(500, TimeUnit.MILLISECONDS).build()
        )
        val key = "PRIVATE_KEY_SENTINEL".toCharArray()
        assertThrows(AiTimeoutException::class.java) {
            runBlocking {
                observed.complete(
                    settings(basePathEndpoint()),
                    key,
                    listOf(AiRequestMessage("user", "PRIVATE_MESSAGE_SENTINEL"))
                )
            }
        }
        val timeout = records.single { it.result == DiagnosticResult.TIMEOUT }
        assertEquals(DiagnosticPhase.READ, timeout.phase)
        assertEquals(200, timeout.httpStatus)
        assertTrue(requireNotNull(timeout.elapsedMs) >= 100)
        assertFalse(records.toString().contains("PRIVATE_"))
        assertFalse(records.toString().contains("localhost"))
        assertTrue(key.all { it == '\u0000' })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun diagnosticStorageFailureCannotFailAnOtherwiseSuccessfulRequest() = runBlocking {
        val observed = OkHttpAiClient(
            trustedHttpClient,
            diagnostics = DiagnosticLogger { throw IllegalStateException("PRIVATE_LOG_FAILURE") }
        )
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"ok"}}]}"""))
        val key = testKey()
        val completion = observed.complete(settings(basePathEndpoint()), key, emptyList())
        assertEquals("ok", completion.content)
        assertTrue(key.all { it == '\u0000' })
    }

    @Test
    fun chatWriteTimeoutDoesNotInheritShortTransportTimeout() = runBlocking {
        val actualWriteTimeout = AtomicReference<Int>()
        val transport = trustedHttpClient.newBuilder()
            .writeTimeout(1, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                actualWriteTimeout.set(chain.writeTimeoutMillis())
                chain.proceed(chain.request())
            }.build()
        val independentClient = OkHttpAiClient(transport)
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"ok"}}]}"""))
        val key = testKey()
        val completion = independentClient.complete(settings(basePathEndpoint()), key, emptyList())
        assertEquals("ok", completion.content)
        assertTrue(key.all { it == '\u0000' })
        assertEquals(60_000, actualWriteTimeout.get())
    }

    @Test
    fun explicitContextLimitCodeIsNotRetriedAsThinkingIncompatibility() {
        server.enqueue(
            MockResponse(code = 400, body = """{"error":{"code":"context_length_exceeded"}}""")
        )
        server.enqueue(
            MockResponse(body = """{"choices":[{"message":{"content":"unexpected retry"}}]}""")
        )
        val key = testKey()
        val error = assertThrows(AiClientException::class.java) {
            runBlocking { client.complete(settings(basePathEndpoint()), key, emptyList()) }
        }
        assertEquals("AiContextBudgetException", error.javaClass.simpleName)
        assertEquals(1, server.requestCount)
        assertTrue(key.all { it == '\u0000' })
    }

    @Test
    fun truncatedOutputRetainsUsageAndCannotExecuteOrRetry() {
        server.enqueue(
            MockResponse(
                body = """{"choices":[{"finish_reason":"length","message":{"content":null}}],
                    |"usage":{"prompt_tokens":20,"completion_tokens":30,"total_tokens":50}}
                """.trimMargin()
            )
        )
        val key = testKey()
        val error = assertThrows(AiAccountedException::class.java) {
            runBlocking { client.complete(settings(basePathEndpoint()), key, emptyList()) }
        }
        assertEquals("AiOutputLimitException", error.failure.javaClass.simpleName)
        assertEquals(AiCompletionUsage(20, 30, 50), error.usage)
        assertEquals(1, server.requestCount)
        assertTrue(key.all { it == '\u0000' })
    }

    private fun basePathEndpoint(): String = server.url("/gateway").toString().removeSuffix("/")

    private fun settings(endpoint: String) = AiSettings(
        endpoint = endpoint,
        model = "model-test",
        temperature = 0.7,
        maxOutputTokens = 512,
        contextWindow = 8_192,
        thinkingEnabled = true,
        thinkingEffort = ThinkingEffort.HIGH,
        systemPrompt = "",
        personality = ""
    )

    private fun testKey(): CharArray = CharArray(8) { index -> ('a'.code + index).toChar() }
}
