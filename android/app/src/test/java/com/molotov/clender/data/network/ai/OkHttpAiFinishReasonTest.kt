package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiRequestMessage
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpAiFinishReasonTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var client: OkHttpAiClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(
            certificate
        ).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        http =
            OkHttpClient.Builder().sslSocketFactory(
                trusted.sslSocketFactory(),
                trusted.trustManager
            )
                .retryOnConnectionFailure(false).followRedirects(false).build()
        client = OkHttpAiClient(httpClient = http)
    }

    @After
    fun tearDown() {
        client.cancelInFlight()
        server.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    @Test
    fun lengthFinishRejectsEvenSyntacticallyCompleteOperations() = rejects("\"length\"")

    @Test
    fun contentFilterFinishRejectsProviderContent() = rejects("\"content_filter\"")

    @Test
    fun toolCallsFinishCannotExecuteTextualOperations() = rejects("\"tool_calls\"")

    @Test
    fun legacyFunctionCallFinishCannotExecuteTextualOperations() = rejects("\"function_call\"")

    @Test
    fun unknownFinishReasonIsRejected() = rejects("\"unexpected\"")

    @Test
    fun malformedFinishReasonIsRejected() = rejects("123")

    @Test
    fun explicitNullIsNotACompletedResponse() = rejects("null")

    @Test
    fun stopAndMissingReasonRemainCompatible() = runBlocking {
        listOf(",\"finish_reason\":\"stop\"", "").forEach { reason ->
            server.enqueue(MockResponse(body = response(reason)))
            val key = CharArray(8) { 'x' }
            val completion = client.complete(settings(), key, messages())
            assertEquals("{\"operations\":[]}", completion.content)
            assertTrue(key.all { it == '\u0000' })
        }
        assertEquals(2, server.requestCount)
    }

    private fun rejects(reason: String) {
        server.enqueue(MockResponse(body = response(",\"finish_reason\":$reason")))
        val key = CharArray(8) { 'x' }
        assertThrows(AiProtocolException::class.java) {
            runBlocking { client.complete(settings(), key, messages()) }
        }
        assertTrue(key.all { it == '\u0000' })
        assertEquals(1, server.requestCount)
    }

    private fun response(reason: String) =
        """{"choices":[{"message":{"content":"{\"operations\":[]}"}$reason}]}"""

    private fun settings() = AiSettings(
        server.url("/v1").toString(),
        "synthetic",
        0.7,
        512,
        8_192,
        false,
        ThinkingEffort.HIGH,
        "",
        ""
    )

    private fun messages() = listOf(AiRequestMessage("user", "Synthetic request"))
}
