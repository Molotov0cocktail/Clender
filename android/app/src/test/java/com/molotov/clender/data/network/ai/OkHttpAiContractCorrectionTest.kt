package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiRequestMessage
import com.molotov.clender.domain.ai.DEFAULT_AI_SYSTEM_CONTRACT
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpAiContractCorrectionTest {
    @Test
    fun correctionAnchorsLatestUpdateWithoutRepeatingHistoricalCreation() = runBlocking {
        val oldRequest = "创建合成喝水、合成起床、合成专注三个事项。"
        val latest = "合成喝水打开闹钟，合成起床只通知不响闹钟，合成专注计时改3分钟。"
        val original = listOf(
            AiRequestMessage(
                "system",
                DEFAULT_AI_SYSTEM_CONTRACT + "\nVisible schedules: id=5; id=6; id=7"
            ),
            AiRequestMessage("user", oldRequest),
            AiRequestMessage("assistant", "三个合成事项已经创建。"),
            AiRequestMessage("user", latest)
        )
        val updated = """{"operations":[{"action":"update","event_id":5,"alarm_enabled":true},
            |{"action":"update","event_id":6,"notification_enabled":true,"alarm_enabled":false},
            |{"action":"update","event_id":7,"timer_minutes":3}]}
        """.trimMargin()
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(body = response(updated, 2)))
        val result = client.complete(settings(), CharArray(8) { 'x' }, original)
        assertEquals(updated, result.content)
        takeJsonRequest()
        val sent = takeJsonRequest().getValue("messages").jsonArray
        val instruction = sent.last().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(instruction.contains(latest))
        assertTrue(!instruction.contains(oldRequest))
        assertTrue(instruction.contains("不要重复历史创建"))
        assertTrue(instruction.contains("当前event_id和update"))
        val firstMessages = sent.take(original.size).map {
            it.jsonObject.getValue("content").jsonPrimitive.content
        }
        assertEquals(original.map { it.content }, firstMessages)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun correctionPreservesLatestLegitimateCreateRequest() = runBlocking {
        val latest = "新增一个明天9点的合成喝水提醒，并打开普通通知。"
        val original = listOf(
            AiRequestMessage("system", DEFAULT_AI_SYSTEM_CONTRACT),
            AiRequestMessage("user", "修改旧事项。"),
            AiRequestMessage("assistant", "旧事项已修改。"),
            AiRequestMessage("user", latest)
        )
        val created = """{"operations":[{"action":"add","event_type":"reminder",
            |"title":"合成喝水","start_time":"2026-09-09 09:00","notification_enabled":true}]}
        """.trimMargin()
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(body = response(created, 2)))
        val result = client.complete(settings(), CharArray(8) { 'x' }, original)
        assertEquals(created, result.content)
        takeJsonRequest()
        val sent = takeJsonRequest().getValue("messages").jsonArray
        val instruction = sent.last().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(instruction.contains(latest))
        assertTrue(instruction.contains("本轮明确要求新增时仍可使用add"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun repeatedLatestUserMustFitBudgetBeforeSendingCorrection() {
        val limited = settings().copy(contextWindow = 4_096, maxOutputTokens = 512)
        val original = listOf(
            AiRequestMessage("system", DEFAULT_AI_SYSTEM_CONTRACT),
            AiRequestMessage("user", "修改现有事项。" + "x".repeat(5_000))
        )
        val inputLimit = limited.contextWindow - limited.maxOutputTokens -
            limited.contextWindow / 10
        assertTrue(AiMessageBudgeter().countTokens(original) < inputLimit)
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(body = response(VALID, 2)))
        val error = assertThrows(AiAccountedException::class.java) {
            runBlocking { client.complete(limited, CharArray(8) { 'x' }, original) }
        }
        assertTrue(error.failure is AiProtocolException)
        assertEquals(AiCompletionUsage(1, 1, 2), error.usage)
        assertEquals(1, server.requestCount)
    }

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
    fun invalidScalarGetsOneCorrectionWithOriginalContextAndCombinedUsage() = runBlocking {
        server.enqueue(MockResponse(body = response("123", 5)))
        server.enqueue(MockResponse(body = response(VALID, 7)))
        val key = CharArray(8) { 'x' }
        val result = client.complete(settings(), key, messages())
        assertEquals(VALID, result.content)
        assertEquals(AiCompletionUsage(12, 12, 24), result.usage)
        assertTrue(key.all { it == '\u0000' })
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val correction = takeJsonRequest()
        val sent = correction.getValue("messages").jsonArray
        val originalContent = sent.take(messages().size).map {
            it.jsonObject.getValue("content").jsonPrimitive.content
        }
        assertEquals(messages().map { it.content }, originalContent)
        val reference = sent[sent.size - 2].jsonObject.getValue("content").jsonPrimitive.content
        val instruction = sent.last().jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("123", reference)
        assertTrue(instruction.contains("operations"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun validFirstOperationsAreReturnedVerbatimWithoutCorrection() = runBlocking {
        server.enqueue(MockResponse(body = response(VALID, 5)))
        val result = client.complete(settings(), CharArray(8) { 'x' }, messages())
        assertEquals(VALID, result.content)
        assertEquals(AiCompletionUsage(5, 5, 10), result.usage)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformedOperationsAndProseAreCorrectedWithoutExposingFirstResponse() = runBlocking {
        listOf(
            "说明：" + VALID,
            """{"operations":[{"action":"delete","event_id":-1}]}""",
            """{"operations":[{"action":"reply","message":"{\"operations\":[]}"}]}"""
        ).forEach { invalid ->
            server.enqueue(MockResponse(body = response(invalid, 1)))
            server.enqueue(MockResponse(body = response(VALID, 2)))
            val result = client.complete(settings(), CharArray(8) { 'x' }, messages())
            assertEquals(VALID, result.content)
            assertEquals(6, result.usage.totalTokens)
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun genericClientRequestDoesNotGainContractCorrection() = runBlocking {
        server.enqueue(MockResponse(body = response("123", 1)))
        val generic = listOf(AiRequestMessage("user", "hi"))
        val result = client.complete(settings(), CharArray(8) { 'x' }, generic)
        assertEquals("123", result.content)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun originalExtensionFallbackPlusCorrectionHasThreeRequestsAtMost() = runBlocking {
        server.enqueue(MockResponse(code = 400))
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(body = response(VALID, 2)))
        val result = client.complete(settings(), CharArray(8) { 'x' }, messages())
        assertEquals(VALID, result.content)
        assertEquals(6, result.usage.totalTokens)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun correctionHttpFailureIsNotRetried() {
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(code = 422))
        val key = CharArray(8) { 'x' }
        val error = assertThrows(AiAccountedException::class.java) {
            runBlocking { client.complete(settings(), key, messages()) }
        }
        assertTrue(error.failure is AiHttpException)
        assertEquals(422, (error.failure as AiHttpException).statusCode)
        assertEquals(AiCompletionUsage(1, 1, 2), error.usage)
        assertTrue(key.all { it == '\u0000' })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun correctionSharesTheOriginalChatDeadline() {
        client = OkHttpAiClient(
            httpClient = http,
            timeoutPolicy = AiTimeoutPolicy(
                chatRead = Duration.ofSeconds(2),
                chatCall = Duration.ofMillis(2_000)
            )
        )
        server.enqueue(
            MockResponse.Builder().body(response("123", 1))
                .bodyDelay(1_200, TimeUnit.MILLISECONDS).build()
        )
        server.enqueue(
            MockResponse.Builder().body(response(VALID, 2))
                .bodyDelay(1_200, TimeUnit.MILLISECONDS).build()
        )
        val key = CharArray(8) { 'x' }
        val error = assertThrows(AiAccountedException::class.java) {
            runBlocking { client.complete(settings(), key, messages()) }
        }
        assertTrue(error.failure is AiTimeoutException)
        assertEquals(AiCompletionUsage(1, 1, 2), error.usage)
        assertTrue(key.all { it == '\u0000' })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun secondInvalidResponseIsAccountedAndNeverRetriedOrReturnedAsPlainReply() {
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(body = response("456", 2)))
        val error = assertThrows(AiAccountedException::class.java) {
            runBlocking { client.complete(settings(), CharArray(8) { 'x' }, messages()) }
        }
        assertTrue(error.failure is AiProtocolException)
        assertEquals(AiCompletionUsage(3, 3, 6), error.usage)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun correctionCannotOverflowInputBudgetOrChangeOriginalMessages() {
        val budgeter = AiMessageBudgeter()
        val small = settings().copy(contextWindow = 1_024, maxOutputTokens = 128)
        val limit = small.contextWindow - small.maxOutputTokens - small.contextWindow / 10
        val request = messages().toMutableList()
        val available = limit - budgeter.countTokens(request) -
            AiMessageBudgeter.MESSAGE_OVERHEAD_TOKENS
        assertTrue(available > 0)
        request.add(AiRequestMessage("user", "x".repeat(available * 3)))
        assertEquals(limit, budgeter.countTokens(request))
        server.enqueue(MockResponse(body = response("123", 1)))
        val error = assertThrows(AiAccountedException::class.java) {
            runBlocking { client.complete(small, CharArray(8) { 'x' }, request) }
        }
        assertTrue(error.failure is AiProtocolException)
        assertEquals(AiCompletionUsage(1, 1, 2), error.usage)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failedResponseIsBoundedAndDoesNotIncludeReasoning() = runBlocking {
        val invalid = "x".repeat(10_000)
        server.enqueue(MockResponse(body = response(invalid, 1)))
        server.enqueue(MockResponse(body = response(VALID, 2)))
        client.complete(settings(), CharArray(8) { 'x' }, messages())
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val sent = takeJsonRequest().getValue("messages").jsonArray
        val content = sent[sent.size - 2].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(content.isNotEmpty())
        assertTrue(content.length <= 4_096)
        assertTrue(invalid.startsWith(content))
        val containsReasoning = sent.any {
            val text = it.jsonObject.getValue("content").jsonPrimitive.content
            text.contains("private-reasoning-fixture")
        }
        assertTrue(!containsReasoning)
    }

    @Test
    fun cancellationDuringCorrectionCannotReturnOrAccountLateCompletion() {
        var calls = 0
        val observing = http.newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                calls += 1
                if (calls == 2) client.cancelInFlight()
            }
        }).build()
        client = OkHttpAiClient(observing)
        server.enqueue(MockResponse(body = response("123", 1)))
        server.enqueue(MockResponse(body = response(VALID, 2)))
        val key = CharArray(8) { 'x' }
        assertThrows(AiRequestCancelledException::class.java) {
            runBlocking { client.complete(settings(), key, messages()) }
        }
        assertTrue(key.all { it == '\u0000' })
        assertEquals(2, calls)
    }

    @Test
    fun truncatedCompletionDoesNotTriggerFormatCorrection() {
        server.enqueue(MockResponse(body = response("123", 1).replace("\"stop\"", "\"length\"")))
        assertThrows(AiProtocolException::class.java) {
            runBlocking { client.complete(settings(), CharArray(8) { 'x' }, messages()) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun combinedUsageSaturatesInsteadOfOverflowing() = runBlocking {
        val large = 1_000_000_000
        repeat(2) { index ->
            server.enqueue(MockResponse(body = response(if (index == 0) "123" else VALID, large)))
        }
        val result = client.complete(settings(), CharArray(8) { 'x' }, messages())
        assertEquals(AiCompletionUsage(2_000_000_000, 2_000_000_000, Int.MAX_VALUE), result.usage)
    }

    private fun takeJsonRequest(): kotlinx.serialization.json.JsonObject {
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        return Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
    }

    private fun response(content: String, tokens: Int): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("finish_reason", "stop")
                        put(
                            "message",
                            buildJsonObject {
                                put("content", content)
                                put("reasoning_content", "private-reasoning-fixture")
                            }
                        )
                    }
                )
            }
        )
        put(
            "usage",
            buildJsonObject {
                put("prompt_tokens", tokens)
                put("completion_tokens", tokens)
                put("total_tokens", tokens * 2)
            }
        )
    }.toString()

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

    private fun messages() = listOf(
        AiRequestMessage("system", DEFAULT_AI_SYSTEM_CONTRACT),
        AiRequestMessage("user", "合成请求")
    )

    private companion object {
        const val VALID = """{ "operations": [{"action":"reply","message":"收到"}] }"""
    }
}
