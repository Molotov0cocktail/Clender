package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.domain.ai.AiRequestMessage
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class AiTimeoutPolicy(
    val connect: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS),
    val modelsRead: Duration = Duration.ofSeconds(DEFAULT_MODELS_TIMEOUT_SECONDS),
    val modelsCall: Duration = Duration.ofSeconds(DEFAULT_MODELS_TIMEOUT_SECONDS),
    val chatRead: Duration = Duration.ofSeconds(DEFAULT_CHAT_TIMEOUT_SECONDS),
    val chatCall: Duration = Duration.ofSeconds(DEFAULT_CHAT_TIMEOUT_SECONDS)
) {
    init {
        listOf(connect, modelsRead, modelsCall, chatRead, chatCall).forEach { duration ->
            require(!duration.isZero && !duration.isNegative && duration.toMillis() > 0) {
                "AI timeout values must be positive millisecond durations"
            }
        }
    }
}

@Suppress("TooManyFunctions")
class OkHttpAiClient(
    httpClient: OkHttpClient,
    private val timeoutPolicy: AiTimeoutPolicy = AiTimeoutPolicy(),
    private val maxErrorCharacters: Int = DEFAULT_MAX_ERROR_CHARACTERS,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val json: Json = Json { ignoreUnknownKeys = false }
) : AiClient {
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val inFlight = AtomicReference<Call?>()
    private val cancellationRequested = AtomicReference<Call?>()

    private sealed interface TerminalOutcome {
        data class Success(val body: String) : TerminalOutcome
        data class Failure(val error: AiClientException) : TerminalOutcome
    }

    init {
        require(maxErrorCharacters > 0)
        require(maxResponseBytes > 0)
    }

    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray): List<String> =
        withWipedKey(apiKey) { authorization ->
            val modelsUrl = AiEndpointValidator.modelsUrl(settings.endpoint).toString()
            val request = requestBuilder(modelsUrl)
                .header("Authorization", authorization)
                .get()
                .build()
            val body = execute(request, timeoutPolicy.modelsCall, timeoutPolicy.modelsRead)
            parseModels(body)
        }

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion = withWipedKey(apiKey) { authorization ->
        validateChatSettings(settings)
        val url = AiEndpointValidator.chatUrl(settings.endpoint).toString()
        if (!settings.thinkingEnabled) {
            return@withWipedKey executeChat(url, authorization, settings, messages, false)
        }
        try {
            executeChat(url, authorization, settings, messages, true)
        } catch (error: AiHttpException) {
            if (error.statusCode !in THINKING_FALLBACK_STATUS_CODES) throw error
            currentCoroutineContext().ensureActive()
            executeChat(url, authorization, settings, messages, false)
        }
    }

    override fun cancelInFlight() {
        inFlight.get()?.let { call ->
            cancellationRequested.set(call)
            call.cancel()
        }
    }

    private suspend fun executeChat(
        url: String,
        authorization: String,
        settings: AiSettings,
        messages: List<AiRequestMessage>,
        includeThinking: Boolean
    ): AiCompletion {
        val payload = chatPayload(settings, messages, includeThinking).toString()
        val request = requestBuilder(url)
            .header("Authorization", authorization)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return parseCompletion(execute(request, timeoutPolicy.chatCall, timeoutPolicy.chatRead))
    }

    private suspend fun execute(
        request: Request,
        callTimeout: Duration,
        readTimeout: Duration
    ): String {
        val call = client.newBuilder()
            .connectTimeout(timeoutPolicy.connect.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
        call.timeout().timeout(callTimeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!inFlight.compareAndSet(null, call)) {
            throw AiClientException("Another AI request is already active")
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancellationRequested.set(call)
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        completeCall(
                            call,
                            continuation,
                            TerminalOutcome.Failure(failureOrCancellation(call, e))
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        completeCall(call, continuation, responseOutcome(call, response))
                    }
                }
            )
        }
    }

    private fun responseOutcome(call: Call, response: Response): TerminalOutcome = try {
        response.use { value ->
            val body = readLimited(value)
            if (!value.isSuccessful) {
                TerminalOutcome.Failure(AiHttpException(value.code, sanitizeError(body)))
            } else {
                TerminalOutcome.Success(body)
            }
        }
    } catch (error: AiClientException) {
        TerminalOutcome.Failure(error)
    } catch (_: SocketTimeoutException) {
        TerminalOutcome.Failure(timeoutOrCancellation(call))
    } catch (_: InterruptedIOException) {
        TerminalOutcome.Failure(timeoutOrCancellation(call))
    } catch (error: IOException) {
        TerminalOutcome.Failure(networkOrCancellation(call, error))
    } catch (_: RuntimeException) {
        TerminalOutcome.Failure(AiProtocolException())
    }

    private fun failureOrCancellation(call: Call, error: IOException): AiClientException = when {
        cancellationRequested.get() === call -> AiRequestCancelledException()
        error is SocketTimeoutException || error is InterruptedIOException -> AiTimeoutException()
        else -> AiNetworkException(error)
    }

    private fun timeoutOrCancellation(call: Call): AiClientException = if (
        cancellationRequested.get() === call
    ) {
        AiRequestCancelledException()
    } else {
        AiTimeoutException()
    }

    private fun networkOrCancellation(call: Call, error: IOException): AiClientException = if (
        cancellationRequested.get() === call
    ) {
        AiRequestCancelledException()
    } else {
        AiNetworkException(error)
    }

    private fun completeCall(
        call: Call,
        continuation: CancellableContinuation<String>,
        outcome: TerminalOutcome
    ) {
        val terminalOutcome = if (cancellationRequested.get() === call) {
            TerminalOutcome.Failure(AiRequestCancelledException())
        } else {
            outcome
        }
        releaseCall(call)
        if (!continuation.isActive) return
        when (terminalOutcome) {
            is TerminalOutcome.Success -> continuation.resume(terminalOutcome.body)
            is TerminalOutcome.Failure -> continuation.resumeWithException(terminalOutcome.error)
        }
    }

    private fun releaseCall(call: Call) {
        cancellationRequested.compareAndSet(call, null)
        inFlight.compareAndSet(call, null)
    }

    private fun readLimited(response: Response): String {
        val body = response.body
        val length = body.contentLength()
        if (length > maxResponseBytes) throw AiResponseTooLargeException()
        val source = body.source()
        val output = Buffer()
        var total = 0L
        while (true) {
            val count = source.read(output, READ_CHUNK_BYTES)
            if (count == -1L) break
            total += count
            if (total > maxResponseBytes) throw AiResponseTooLargeException()
        }
        return output.readUtf8()
    }

    @Suppress("ThrowsCount")
    private fun parseModels(body: String): List<String> {
        val root = parseObject(body)
        val data = root["data"] as? JsonArray ?: throw AiProtocolException()
        return data.map { item ->
            val model = item as? JsonObject ?: throw AiProtocolException()
            (model["id"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: throw AiProtocolException()
        }
    }

    private fun parseCompletion(body: String): AiCompletion = try {
        val root = parseObject(body)
        val choices = root["choices"]?.jsonArray ?: throw AiProtocolException()
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiProtocolException()
        val content = message["content"]?.jsonPrimitive?.contentOrNull
            ?: throw AiProtocolException()
        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val usage = root["usage"] as? JsonObject
        val prompt = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val completion = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val total = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull
            ?: prompt + completion
        if (prompt < 0 || completion < 0 || total < 0) throw AiProtocolException()
        AiCompletion(content, reasoning, AiCompletionUsage(prompt, completion, total))
    } catch (error: AiProtocolException) {
        throw error
    } catch (_: RuntimeException) {
        throw AiProtocolException()
    }

    private fun parseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body) as? JsonObject ?: throw AiProtocolException()
    } catch (error: AiProtocolException) {
        throw error
    } catch (_: IllegalArgumentException) {
        throw AiProtocolException()
    }

    private fun chatPayload(
        settings: AiSettings,
        messages: List<AiRequestMessage>,
        includeThinking: Boolean
    ) = buildJsonObject {
        put("model", settings.model)
        put("temperature", settings.temperature)
        put("max_tokens", settings.maxOutputTokens)
        put(
            "messages",
            buildJsonArray {
                messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        }
                    )
                }
            }
        )
        if (includeThinking) {
            put("reasoning_effort", settings.thinkingEffort.name.lowercase())
            put(
                "extra_body",
                buildJsonObject {
                    put("thinking", buildJsonObject { put("type", "enabled") })
                }
            )
        }
    }

    @Suppress("ThrowsCount")
    private fun validateChatSettings(settings: AiSettings) {
        if (settings.model.isBlank()) throw AiConfigurationException("AI model is required")
        if (!settings.temperature.isFinite() || settings.temperature !in 0.0..2.0) {
            throw AiConfigurationException("AI temperature is invalid")
        }
        if (settings.maxOutputTokens <= 0 || settings.contextWindow <= settings.maxOutputTokens) {
            throw AiConfigurationException("AI token budget is invalid")
        }
    }

    private fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "Clender-Android/1.0")

    private fun sanitizeError(body: String): String = body
        .filter { character -> character == '\n' || character == '\t' || character >= ' ' }
        .take(maxErrorCharacters)

    private suspend fun <T> withWipedKey(
        apiKey: CharArray,
        block: suspend (authorization: String) -> T
    ): T {
        val authorization = try {
            if (apiKey.isEmpty() || apiKey.any { it == '\u0000' }) {
                throw AiConfigurationException("AI API key is required")
            }
            "Bearer ${String(apiKey)}"
        } finally {
            apiKey.fill('\u0000')
        }
        return block(authorization)
    }

    private companion object {
        const val READ_CHUNK_BYTES = 8_192L
        val THINKING_FALLBACK_STATUS_CODES = setOf(
            STATUS_BAD_REQUEST,
            STATUS_UNPROCESSABLE_CONTENT
        )
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15L
private const val DEFAULT_MODELS_TIMEOUT_SECONDS = 15L
private const val DEFAULT_CHAT_TIMEOUT_SECONDS = 180L
private const val DEFAULT_MAX_ERROR_CHARACTERS = 512
private const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1_024 * 1_024
private const val STATUS_BAD_REQUEST = 400
private const val STATUS_UNPROCESSABLE_CONTENT = 422
