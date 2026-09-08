package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiRequestMessage
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
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

class OkHttpAiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpAiClient
    private lateinit var trustedHttpClient: OkHttpClient
    private lateinit var serverSocketFactory: SSLSocketFactory

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        serverSocketFactory = serverCertificates.sslSocketFactory()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(this@OkHttpAiClientTest.serverSocketFactory)
            start()
        }
        trustedHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
        client = OkHttpAiClient(
            httpClient = trustedHttpClient,
            timeoutPolicy = AiTimeoutPolicy(
                connect = Duration.ofSeconds(1),
                modelsRead = Duration.ofSeconds(1),
                modelsCall = Duration.ofSeconds(1),
                chatRead = Duration.ofSeconds(1),
                chatCall = Duration.ofSeconds(1)
            ),
            maxErrorCharacters = 512,
            maxResponseBytes = 1_024
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun modelCatalogRetainsProviderLimitsAndRejectsMalformedOptionalLimits() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"data":[{"id":"known","context_length":128000,"top_provider":{
                    |"context_length":64000,"max_completion_tokens":8192}},
                    |{"id":"unknown","context_length":"128000","max_output_tokens":-1}]}
                """.trimMargin()
            )
        )
        val key = testKey()
        val catalog = client.fetchModelCatalog(settings(basePathEndpoint()), key)
        assertEquals(listOf("known", "unknown"), catalog.map { it.id })
        assertEquals(AiModelCapabilities(64000, 8192), catalog.first().capabilities)
        assertEquals(AiModelCapabilities(), catalog.last().capabilities)
        assertTrue(key.all { it == '\u0000' })
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun selectedThinkingEffortIsUsedInActualRequestPayload() = runBlocking {
        ThinkingEffort.entries.forEach { effort ->
            server.enqueue(
                MockResponse(body = """{"choices":[{"message":{"content":"hello"}}]}""")
            )
            client.complete(
                settings(basePathEndpoint()).copy(thinkingEnabled = true, thinkingEffort = effort),
                testKey(),
                listOf(AiRequestMessage("user", "Synthetic"))
            )
            val payload = Json.parseToJsonElement(
                requireNotNull(server.takeRequest().body).utf8()
            ).jsonObject
            assertEquals(
                effort.name.lowercase(),
                payload["reasoning_effort"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun defaultTimeoutPolicyLocksModelsAndChatContracts() {
        val policy = AiTimeoutPolicy()

        assertEquals(Duration.ofSeconds(15), policy.connect)
        assertEquals(Duration.ofSeconds(15), policy.modelsRead)
        assertEquals(Duration.ofSeconds(15), policy.modelsCall)
        assertEquals(Duration.ofSeconds(180), policy.chatRead)
        assertEquals(Duration.ofSeconds(180), policy.chatCall)
    }

    @Test
    fun modelsUsesGetRelativeV1PathBearerAndWipesCallerKey() = runBlocking {
        server.enqueue(MockResponse(body = """{"data":[{"id":"模型-🌏"},{"id":"b"}]}"""))
        val key = testKey()

        val models = client.fetchModels(settings(basePathEndpoint()), key)

        assertEquals(listOf("模型-🌏", "b"), models)
        assertTrue(key.all { it == '\u0000' })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/gateway/v1/models", request.url.encodedPath)
        assertTrue(request.headers["Authorization"]?.startsWith("Bearer ") == true)
    }

    @Test
    fun v1EndpointIsNotDuplicatedAndChatParsesContentThinkingAndUsage() = runBlocking {
        server.enqueue(
            MockResponse(
                body =
                    """
                    {
                      "choices":[
                        {"message":{"content":"{\"operations\":[]}","reasoning_content":"思考"}}
                      ],
                      "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}
                    }
                    """.trimIndent()
            )
        )
        val key = testKey()
        val completion = client.complete(
            settings(server.url("/gateway/v1").toString()),
            key,
            listOf(AiRequestMessage("user", "你好"))
        )

        assertTrue(key.all { it == '\u0000' })
        assertEquals("{\"operations\":[]}", completion.content)
        assertEquals("思考", completion.reasoningContent)
        assertEquals(15, completion.usage.totalTokens)
        val request = server.takeRequest()
        assertEquals("/gateway/v1/chat/completions", request.url.encodedPath)
        val requestJson = Json.parseToJsonElement(
            requireNotNull(request.body).utf8()
        ).jsonObject
        assertEquals("model-test", requestJson.getValue("model").jsonPrimitive.content)
        assertEquals(0.7, requestJson.getValue("temperature").jsonPrimitive.double, 0.0)
        assertEquals(512, requestJson.getValue("max_tokens").jsonPrimitive.int)
        val message = requestJson.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals("你好", message.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun thinking400Or422FallsBackExactlyOnceWithoutExtensions() = runBlocking {
        listOf(400, 422).forEach { status ->
            server.enqueue(MockResponse(code = status, body = "unsupported"))
            server.enqueue(
                MockResponse(
                    body = """{"choices":[{"message":{"content":"ok"}}]}"""
                )
            )

            val key = testKey()
            assertEquals(
                "ok",
                client.complete(
                    settings(basePathEndpoint()).copy(thinkingEnabled = true),
                    key,
                    listOf(AiRequestMessage("user", "hello"))
                ).content
            )
            assertTrue(key.all { it == '\u0000' })
            val firstBody = requireNotNull(server.takeRequest().body).utf8()
            val retryBody = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(firstBody.contains("reasoning_effort"))
            assertTrue(firstBody.contains("thinking"))
            assertFalse(retryBody.contains("reasoning_effort"))
            assertFalse(retryBody.contains("extra_body"))
            assertFalse(retryBody.contains("\"thinking\""))
        }
    }

    @Test
    fun thinkingFallbackReleasesFinishedCallBeforeInlineContinuationRetries() {
        listOf(400, 422).forEachIndexed { index, status ->
            server.enqueue(MockResponse(code = status, body = "unsupported"))
            server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"ok"}}]}"""))
            val key = testKey()

            val completion = runBlocking(Dispatchers.Unconfined) {
                client.complete(
                    settings(basePathEndpoint()).copy(thinkingEnabled = true),
                    key,
                    listOf(AiRequestMessage("user", "hello"))
                )
            }

            assertEquals("ok", completion.content)
            assertTrue(key.all { it == '\u0000' })
            val firstRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val fallbackRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val firstBody = requireNotNull(firstRequest.body).utf8()
            val fallbackBody = requireNotNull(fallbackRequest.body).utf8()
            assertTrue(firstBody.contains("reasoning_effort"))
            assertTrue(firstBody.contains("thinking"))
            assertFalse(fallbackBody.contains("reasoning_effort"))
            assertFalse(fallbackBody.contains("extra_body"))
            assertFalse(fallbackBody.contains("\"thinking\""))
            assertEquals(2 * (index + 1), server.requestCount)
        }
    }

    @Test
    fun cancellationAtThinkingFallbackBoundaryDoesNotQueueRetry() {
        server.enqueue(MockResponse(code = 400, body = "unsupported"))
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"late"}}]}"""))
        val requestJob = AtomicReference<Job?>()
        val boundaryClient = OkHttpAiClient(
            httpClient = trustedHttpClient.newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun responseBodyEnd(call: Call, byteCount: Long) {
                            requestJob.get()?.cancel(
                                CancellationException("backgrounded at fallback boundary")
                            )
                        }
                    }
                )
                .build(),
            timeoutPolicy = AiTimeoutPolicy(
                connect = Duration.ofSeconds(1),
                modelsRead = Duration.ofSeconds(1),
                modelsCall = Duration.ofSeconds(1),
                chatRead = Duration.ofSeconds(1),
                chatCall = Duration.ofSeconds(1)
            ),
            maxResponseBytes = 1_024
        )
        val worker = Executors.newSingleThreadExecutor()
        val key = testKey()
        try {
            val result = worker.submit<Throwable?> {
                kotlin.runCatching {
                    runBlocking {
                        requestJob.set(coroutineContext[Job])
                        boundaryClient.complete(
                            settings(basePathEndpoint()).copy(thinkingEnabled = true),
                            key,
                            listOf(AiRequestMessage("user", "hello"))
                        )
                    }
                }.exceptionOrNull()
            }

            assertTrue(result.get(2, TimeUnit.SECONDS) is CancellationException)
            assertTrue(key.all { it == '\u0000' })
            assertEquals(1, server.requestCount)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun nonThinkingHttpErrorsNeverRetryAndExposeOnlyBoundedSanitizedError() {
        listOf(401, 429, 500).forEach { status ->
            server.enqueue(
                MockResponse(code = status, body = "provider\u0000-${"x".repeat(900)}")
            )

            val key = testKey()
            val error = assertThrows(AiHttpException::class.java) {
                runBlocking {
                    client.complete(
                        settings(basePathEndpoint()).copy(thinkingEnabled = false),
                        key,
                        listOf(AiRequestMessage("user", "hello"))
                    )
                }
            }

            assertEquals(status, error.statusCode)
            assertTrue(error.safeBody.length <= 512)
            assertFalse(error.safeBody.contains('\u0000'))
            assertTrue(key.all { it == '\u0000' })
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun thinkingFailureFallsBackOnlyFor400And422AndNeverRetriesFallback() {
        server.enqueue(MockResponse(code = 400, body = "unsupported"))
        server.enqueue(MockResponse(code = 500, body = "failed"))

        val key = testKey()
        assertThrows(AiHttpException::class.java) {
            runBlocking {
                client.complete(
                    settings(basePathEndpoint()),
                    key,
                    listOf(AiRequestMessage("user", "hello"))
                )
            }
        }

        assertTrue(key.all { it == '\u0000' })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun malformedSuccessTimeoutAndMissingConfigurationAreRestrictedFailures() {
        server.enqueue(MockResponse(body = """{"choices":[]}"""))
        val protocolKey = testKey()
        assertThrows(AiProtocolException::class.java) {
            runBlocking {
                client.complete(settings(basePathEndpoint()), protocolKey, emptyList())
            }
        }
        assertTrue(protocolKey.all { it == '\u0000' })

        server.enqueue(
            MockResponse.Builder()
                .body("""{"choices":[{"message":{"content":"late"}}]}""")
                .bodyDelay(2, TimeUnit.SECONDS)
                .build()
        )
        val timeoutKey = testKey()
        assertThrows(AiTimeoutException::class.java) {
            runBlocking {
                client.complete(settings(basePathEndpoint()), timeoutKey, emptyList())
            }
        }
        assertTrue(timeoutKey.all { it == '\u0000' })

        val configurationKey = testKey()
        assertThrows(AiConfigurationException::class.java) {
            runBlocking { client.fetchModels(settings(""), configurationKey) }
        }
        assertTrue(configurationKey.all { it == '\u0000' })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun modelsHttpMalformedTimeoutAndOversizeFailuresWipeKeysWithoutRetry() {
        server.enqueue(MockResponse(code = 401, body = "denied"))
        val httpKey = testKey()
        assertThrows(AiHttpException::class.java) {
            runBlocking { client.fetchModels(settings(basePathEndpoint()), httpKey) }
        }
        assertTrue(httpKey.all { it == '\u0000' })

        server.enqueue(MockResponse(body = """{"data":"not-an-array"}"""))
        val malformedKey = testKey()
        assertThrows(AiProtocolException::class.java) {
            runBlocking { client.fetchModels(settings(basePathEndpoint()), malformedKey) }
        }
        assertTrue(malformedKey.all { it == '\u0000' })

        server.enqueue(
            MockResponse.Builder()
                .body("""{"data":[{"id":"late"}]}""")
                .bodyDelay(2, TimeUnit.SECONDS)
                .build()
        )
        val timeoutKey = testKey()
        assertThrows(AiTimeoutException::class.java) {
            runBlocking { client.fetchModels(settings(basePathEndpoint()), timeoutKey) }
        }
        assertTrue(timeoutKey.all { it == '\u0000' })

        server.enqueue(MockResponse(body = "x".repeat(1_025)))
        val oversizeKey = testKey()
        assertThrows(AiResponseTooLargeException::class.java) {
            runBlocking { client.fetchModels(settings(basePathEndpoint()), oversizeKey) }
        }
        assertTrue(oversizeKey.all { it == '\u0000' })
        assertEquals(4, server.requestCount)
    }

    @Test
    fun crossOriginRedirectIsNotFollowedAndNeverReceivesAuthorization() {
        val redirectTarget = MockWebServer().apply {
            useHttps(this@OkHttpAiClientTest.serverSocketFactory)
            start()
        }
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", redirectTarget.url("/capture"))
                    .build()
            )
            val key = testKey()

            assertThrows(AiHttpException::class.java) {
                runBlocking { client.fetchModels(settings(basePathEndpoint()), key) }
            }

            assertTrue(key.all { it == '\u0000' })
            assertEquals(1, server.requestCount)
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            redirectTarget.close()
        }
    }

    @Test
    fun chatRejectsContentLengthAndChunkedStreamingOversizeBodiesAndWipesKeys() {
        listOf(
            MockResponse(body = "x".repeat(1_025)),
            MockResponse.Builder().chunkedBody("x".repeat(1_025), 73).build()
        ).forEach { response ->
            server.enqueue(response)
            val key = testKey()

            assertThrows(AiResponseTooLargeException::class.java) {
                runBlocking {
                    client.complete(settings(basePathEndpoint()), key, emptyList())
                }
            }

            assertTrue(key.all { it == '\u0000' })
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun disconnectedChatIsRestrictedDoesNotRetryAndWipesKey() {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        )
        val key = testKey()

        assertThrows(AiNetworkException::class.java) {
            runBlocking {
                client.complete(settings(basePathEndpoint()), key, emptyList())
            }
        }

        assertTrue(key.all { it == '\u0000' })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellationCancelsSingleCallAndDoesNotRetry() {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        val worker = Executors.newSingleThreadExecutor()
        val key = testKey()
        try {
            val result = worker.submit<Throwable?> {
                kotlin.runCatching {
                    runBlocking {
                        client.complete(
                            settings(basePathEndpoint()),
                            key,
                            listOf(AiRequestMessage("user", "hello"))
                        )
                    }
                }.exceptionOrNull()
            }
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(key.all { it == '\u0000' })

            client.cancelInFlight()

            assertTrue(result.get(2, TimeUnit.SECONDS) is AiRequestCancelledException)
            assertTrue(key.all { it == '\u0000' })
            assertEquals(1, server.requestCount)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun responseBodyStallRemainsCancellableAndRejectsConcurrentCall() {
        server.enqueue(
            MockResponse.Builder()
                .body("never delivered")
                .onResponseBody(SocketEffect.Stall)
                .build()
        )
        val worker = Executors.newSingleThreadExecutor()
        val firstKey = testKey()
        try {
            val result = worker.submit<Throwable?> {
                kotlin.runCatching {
                    runBlocking { client.fetchModels(settings(basePathEndpoint()), firstKey) }
                }.exceptionOrNull()
            }
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(firstKey.all { it == '\u0000' })
            val concurrentKey = testKey()

            assertThrows(AiClientException::class.java) {
                runBlocking { client.fetchModels(settings(basePathEndpoint()), concurrentKey) }
            }
            assertTrue(concurrentKey.all { it == '\u0000' })
            client.cancelInFlight()

            assertTrue(result.get(2, TimeUnit.SECONDS) is AiRequestCancelledException)
            assertTrue(firstKey.all { it == '\u0000' })
            assertEquals(1, server.requestCount)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun chatReadTimeoutIsOwnedByAiClientInsteadOfInheritedTransportDefault() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"choices":[{"message":{"content":"delayed-ok"}}]}""")
                .bodyDelay(100, TimeUnit.MILLISECONDS)
                .build()
        )
        val independentlyTimed = OkHttpAiClient(
            httpClient = trustedHttpClient.newBuilder()
                .readTimeout(1, TimeUnit.MILLISECONDS)
                .build(),
            timeoutPolicy = AiTimeoutPolicy(
                connect = Duration.ofSeconds(1),
                modelsRead = Duration.ofSeconds(1),
                modelsCall = Duration.ofSeconds(1),
                chatRead = Duration.ofSeconds(1),
                chatCall = Duration.ofSeconds(2)
            ),
            maxResponseBytes = 1_024
        )
        val key = testKey()

        val completion = independentlyTimed.complete(
            settings(basePathEndpoint()),
            key,
            emptyList()
        )

        assertEquals("delayed-ok", completion.content)
        assertTrue(key.all { it == '\u0000' })
    }

    @Test
    fun explicitVersionChatUsesExactWirePathAndWipesKey() = runBlocking {
        listOf("/v2", "/api/paas/v4").forEach { base ->
            listOf("", "/").forEach { trailing ->
                server.enqueue(
                    MockResponse(body = """{"choices":[{"message":{"content":"hello"}}]}""")
                )
                val key = testKey()
                val completion = client.complete(
                    settings(server.url("$base$trailing").toString()),
                    key,
                    listOf(AiRequestMessage("user", "hello"))
                )
                assertTrue(key.all { it == '\u0000' })
                assertEquals("hello", completion.content)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("$base/chat/completions", request.url.encodedPath)
            }
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun explicitVersionModelsUsesExactWirePathAndWipesKey() = runBlocking {
        listOf("/v2", "/api/paas/v4").forEach { base ->
            listOf("", "/").forEach { trailing ->
                server.enqueue(MockResponse(body = """{"data":[{"id":"model-test"}]}"""))
                val key = testKey()
                val models = client.fetchModels(
                    settings(server.url("$base$trailing").toString()),
                    key
                )
                assertTrue(key.all { it == '\u0000' })
                assertEquals(listOf("model-test"), models)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("$base/models", request.url.encodedPath)
            }
        }
        assertEquals(4, server.requestCount)
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
