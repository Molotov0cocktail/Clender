package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiRequestMessage
import java.time.Duration
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpAiThinkingPayloadTest {
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
            useHttps(this@OkHttpAiThinkingPayloadTest.serverSocketFactory)
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
    fun everyModelRequestsJsonObjectResponsesForTheApplicationContract() = runBlocking {
        listOf("model-test", "glm-5.3", "glm-5.3-flash").forEach { model ->
            val payload = recordedPayload(settings(basePathEndpoint()).copy(model = model))
            assertEquals(
                "json_object",
                payload["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            )
        }
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
            assertEquals(
                "enabled",
                payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            )
            assertFalse("extra_body" in payload)
        }
    }

    @Test
    fun glm53ModelsNormalizeSavedMediumAndPreserveSupportedEfforts() = runBlocking {
        listOf("glm-5.3", "glm-5.3-flash").forEach { model ->
            ThinkingEffort.entries.forEach { effort ->
                val payload = recordedPayload(
                    settings(basePathEndpoint()).copy(model = model, thinkingEffort = effort)
                )
                val expected = if (effort == ThinkingEffort.MEDIUM) {
                    "high"
                } else {
                    effort.name.lowercase()
                }
                assertEquals(expected, payload["reasoning_effort"]?.jsonPrimitive?.content)
                assertEquals(
                    "enabled",
                    payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                )
                assertFalse("extra_body" in payload)
            }
        }
    }

    @Test
    fun glm53ModelsUseLowEnabledThinkingWhenSavedToggleIsOff() = runBlocking {
        listOf("glm-5.3", "glm-5.3-flash").forEach { model ->
            val payload = recordedPayload(
                settings(basePathEndpoint()).copy(model = model, thinkingEnabled = false)
            )
            assertEquals("low", payload["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals(
                "enabled",
                payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun otherModelsExplicitlyDisableThinkingWithoutEffort() = runBlocking {
        listOf("model-test", "glm-5.3-custom", "glm-5.2").forEach { model ->
            val payload = recordedPayload(
                settings(basePathEndpoint()).copy(model = model, thinkingEnabled = false)
            )
            assertEquals(
                "disabled",
                payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            )
            assertFalse("reasoning_effort" in payload)
            assertFalse("extra_body" in payload)
        }
    }

    @Test
    fun explicitDisabledThinkingFallsBackOnceWithoutAnyExtensions() = runBlocking {
        server.enqueue(MockResponse(code = 422, body = "unsupported"))
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"hello"}}]}"""))
        client.complete(
            settings(basePathEndpoint()).copy(thinkingEnabled = false),
            testKey(),
            listOf(AiRequestMessage("user", "Synthetic"))
        )
        val first = Json.parseToJsonElement(
            requireNotNull(server.takeRequest().body).utf8()
        ).jsonObject
        val retry = Json.parseToJsonElement(
            requireNotNull(server.takeRequest().body).utf8()
        ).jsonObject
        assertEquals("disabled", first["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "json_object",
            first["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
        listOf("thinking", "reasoning_effort", "extra_body", "response_format").forEach {
            assertFalse(it in retry)
        }
        assertEquals(2, server.requestCount)
    }

    private suspend fun recordedPayload(settings: AiSettings): JsonObject {
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"hello"}}]}"""))
        val key = testKey()
        client.complete(settings, key, listOf(AiRequestMessage("user", "Synthetic")))
        assertTrue(key.all { it == '\u0000' })
        return Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
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
