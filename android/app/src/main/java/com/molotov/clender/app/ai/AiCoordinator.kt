package com.molotov.clender.app.ai

import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.AiConfigurationException
import com.molotov.clender.data.network.ai.AiHttpException
import com.molotov.clender.data.network.ai.AiNetworkException
import com.molotov.clender.data.network.ai.AiProtocolException
import com.molotov.clender.data.network.ai.AiRequestCancelledException
import com.molotov.clender.data.network.ai.AiResponseTooLargeException
import com.molotov.clender.data.network.ai.AiTimeoutException
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.domain.ai.AiBudgetInput
import com.molotov.clender.domain.ai.AiContextProvider
import com.molotov.clender.domain.ai.AiExecutionReport
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperationExecutor
import com.molotov.clender.domain.ai.AiParseResult
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.DEFAULT_AI_SYSTEM_CONTRACT
import com.molotov.clender.domain.conversation.ConversationRepository
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AiCoordinatorError {
    CONFIGURATION,
    TIMEOUT,
    NETWORK,
    AUTHENTICATION,
    RATE_LIMIT,
    REQUEST_PARAMETERS,
    PROVIDER,
    INVALID_RESPONSE,
    INTERNAL
}

sealed interface AiCoordinatorState {
    data object Idle : AiCoordinatorState

    data class Working(val conversationId: String) : AiCoordinatorState

    data class Failed(val error: AiCoordinatorError) : AiCoordinatorState

    data object Cancelled : AiCoordinatorState
}

data class AiCoordinatorDependencies(
    val client: AiClient,
    val conversations: ConversationRepository,
    val budgeter: AiMessageBudgeter,
    val parser: AiResponseParser,
    val executor: AiOperationExecutor,
    val clock: Clock,
    val contextProvider: AiContextProvider
)

class AiCoordinator(private val scope: CoroutineScope, dependencies: AiCoordinatorDependencies) :
    ApplicationVisibilityController {
    private val client = dependencies.client
    private val conversations = dependencies.conversations
    private val budgeter = dependencies.budgeter
    private val parser = dependencies.parser
    private val executor = dependencies.executor
    private val clock = dependencies.clock
    private val contextProvider = dependencies.contextProvider
    private val submissionMutex = Mutex()
    private val visibilityLock = Any()
    private val mutableState = MutableStateFlow<AiCoordinatorState>(AiCoordinatorState.Idle)

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var foreground = false

    private var pendingSubmission: Job? = null

    val state: StateFlow<AiCoordinatorState> = mutableState.asStateFlow()

    suspend fun submit(
        conversationId: String,
        userMessage: String,
        settings: AiSettings,
        apiKey: CharArray
    ): Boolean = submitWithLease(conversationId, userMessage, settings, apiKey, null)

    suspend fun submit(
        conversationId: String,
        userMessage: String,
        settings: AiSettings,
        apiKey: CharArray,
        operationLease: AiOperationLease
    ): Boolean = submitWithLease(conversationId, userMessage, settings, apiKey, operationLease)

    private suspend fun submitWithLease(
        conversationId: String,
        userMessage: String,
        settings: AiSettings,
        apiKey: CharArray,
        operationLease: AiOperationLease?
    ): Boolean = submissionMutex.withLock {
        var ownershipTransferred = false
        var requestKey: CharArray? = null
        val pending = Job()
        try {
            val capturedConversationId = prepareSubmission(
                conversationId,
                userMessage,
                pending
            ) ?: return@withLock false
            val ownedKey = apiKey.copyOf()
            requestKey = ownedKey
            val launched = scope.launch(start = CoroutineStart.LAZY) {
                runRequest(capturedConversationId, settings, ownedKey, operationLease)
            }
            launched.invokeOnCompletion { cause ->
                ownedKey.fill('\u0000')
                operationLease?.close()
                synchronized(visibilityLock) {
                    if (activeJob === launched) activeJob = null
                }
                if (cause is CancellationException &&
                    mutableState.value is AiCoordinatorState.Working
                ) {
                    mutableState.value = AiCoordinatorState.Cancelled
                }
            }
            val accepted = synchronized(visibilityLock) {
                if (!foreground || !pending.isActive) {
                    false
                } else {
                    pendingSubmission = null
                    activeJob = launched
                    mutableState.value = AiCoordinatorState.Working(capturedConversationId)
                    launched.start()
                }
            }
            if (!accepted) {
                launched.cancel()
                return@withLock false
            }
            ownershipTransferred = true
            true
        } finally {
            synchronized(visibilityLock) {
                if (pendingSubmission === pending) pendingSubmission = null
            }
            pending.cancel()
            apiKey.fill('\u0000')
            if (!ownershipTransferred) requestKey?.fill('\u0000')
            if (!ownershipTransferred) operationLease?.close()
        }
    }

    override fun onAppForegrounded() {
        synchronized(visibilityLock) {
            foreground = true
        }
    }

    override fun onAppBackgrounded() {
        val hadRequest = synchronized(visibilityLock) {
            foreground = false
            val pending = pendingSubmission
            val active = activeJob
            val hadActiveRequest = pending?.isActive == true || active?.isActive == true
            pending?.cancel(CancellationException("Application moved to the background"))
            active?.cancel(CancellationException("Application moved to the background"))
            hadActiveRequest
        }
        if (hadRequest) {
            mutableState.value = AiCoordinatorState.Cancelled
            client.cancelInFlight()
        }
    }

    val acknowledgeTerminal: () -> Unit = {
        if (mutableState.value is AiCoordinatorState.Failed ||
            mutableState.value is AiCoordinatorState.Cancelled
        ) {
            mutableState.value = AiCoordinatorState.Idle
        }
    }

    private suspend fun prepareSubmission(
        conversationId: String,
        userMessage: String,
        pending: Job
    ): String? {
        val requestValid = conversationId.isNotBlank() &&
            userMessage.isNotBlank() &&
            activeJob?.isActive != true &&
            scope.coroutineContext[Job]?.isActive != false
        return if (!requestValid) {
            null
        } else {
            val reserved = synchronized(visibilityLock) {
                if (!foreground || pendingSubmission?.isActive == true ||
                    activeJob?.isActive == true
                ) {
                    false
                } else {
                    pendingSubmission = pending
                    true
                }
            }
            if (reserved && conversations.findConversation(conversationId) != null &&
                pending.isActive
            ) {
                conversations.appendMessageAndIncrementTokens(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = userMessage,
                    timestamp = clock.nowMicros(),
                    tokenDelta = 0
                )
                conversationId.takeIf { pending.isActive }
            } else {
                null
            }
        }
    }

    private suspend fun runRequest(
        conversationId: String,
        settings: AiSettings,
        apiKey: CharArray,
        operationLease: AiOperationLease?
    ) {
        val terminal = try {
            val failure = runCatching {
                executeRequest(conversationId, settings, apiKey)
            }.exceptionOrNull()
            when (failure) {
                null -> AiCoordinatorState.Idle

                is CancellationException,
                is AiRequestCancelledException -> AiCoordinatorState.Cancelled

                is RuntimeException -> AiCoordinatorState.Failed(failure.toCoordinatorError())

                else -> throw failure
            }
        } finally {
            apiKey.fill('\u0000')
            operationLease?.close()
        }
        mutableState.value = terminal
    }

    private suspend fun executeRequest(
        conversationId: String,
        settings: AiSettings,
        apiKey: CharArray
    ) {
        val history = conversations.observeMessages(conversationId).first()
        val scheduleContext = contextProvider.build()
        val request = budgeter.build(
            AiBudgetInput(
                mandatorySystemContract = DEFAULT_AI_SYSTEM_CONTRACT,
                systemPrompt = "",
                personality = settings.personality,
                scheduleContext = scheduleContext,
                history = history.mapNotNull { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        stripApplicationReceipts(message.content).takeIf(String::isNotBlank)
                            ?.let { message.copy(content = it) }
                    } else {
                        message
                    }
                },
                contextWindow = settings.contextWindow,
                maxOutputTokens = settings.maxOutputTokens
            )
        )
        val completion = client.complete(settings, apiKey, request.messages)
        currentCoroutineContext().ensureActive()
        persistCompletion(conversationId, completion)
    }

    private suspend fun persistCompletion(conversationId: String, completion: AiCompletion) {
        var remainingTokenDelta = completion.usage.totalTokens.coerceAtLeast(0)
        if (completion.reasoningContent.isNotBlank()) {
            append(
                conversationId,
                MessageRole.THINK,
                completion.reasoningContent,
                remainingTokenDelta
            )
            remainingTokenDelta = 0
        }
        val parsed = parser.parse(completion.content)
        val replies = when (parsed) {
            is AiParseResult.PlainReply -> listOf(noChangeReply(listOf(parsed.message)))
            is AiParseResult.Rejected -> listOf(parsed.userMessage)
            is AiParseResult.Operations -> executor.execute(parsed.operations).operationReplies()
        }
        val safeReplies = replies.ifEmpty { listOf("本轮未修改日程。") }
        safeReplies.forEachIndexed { index, reply ->
            append(
                conversationId,
                MessageRole.ASSISTANT,
                reply,
                if (index == 0) remainingTokenDelta else 0
            )
            remainingTokenDelta = 0
        }
        if (parsed is AiParseResult.Rejected) throw AiProtocolException()
    }

    private suspend fun append(
        conversationId: String,
        role: MessageRole,
        content: String,
        tokenDelta: Int
    ) {
        conversations.appendMessageAndIncrementTokens(
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = clock.nowMicros(),
            tokenDelta = tokenDelta
        )
    }
}

private fun AiExecutionReport.operationReplies(): List<String> {
    val eventOutcomes = outcomes.filter { it.action in setOf("add", "update", "delete") }
    val failed = eventOutcomes.count { !it.success }
    val completed = eventOutcomes.count { it.success && it.changed }
    return when {
        failed > 0 -> listOf(
            "已实际完成 $completed 项日程操作，$failed 项未能执行。请先核对日历再重试。"
        )

        scheduleChanged -> listOf(
            "已实际完成 $completed 项日程操作。" +
                modelReply(replies)
        )

        else -> listOf(noChangeReply(replies))
    }
}

private fun noChangeReply(replies: List<String>): String = "本轮未修改日程。" + modelReply(replies)

private fun modelReply(replies: List<String>): String {
    val body = replies.map(::stripApplicationReceipts).filter(String::isNotBlank).joinToString("\n")
    return if (body.isEmpty()) "" else "\n\n模型回复：\n$body"
}

private val applicationReceipt = Regex(
    "^(?:No schedule changes were made\\. 本轮未修改日程。|本轮未修改日程。|" +
        "(?:[0-9]+ schedule operation\\(s\\) completed\\. )?已实际完成 [0-9]+ 项日程操作。)" +
        "(?:\\s*(?:Assistant reply / 模型回复：|模型回复：)\\s*|\\s*$)"
)

private fun stripApplicationReceipts(content: String): String {
    var body = content.trim()
    while (true) {
        val prefix = applicationReceipt.find(body) ?: return body
        body = body.substring(prefix.range.last + 1).trimStart()
    }
}

private fun Clock.nowMicros() = instant().truncatedTo(ChronoUnit.MICROS)

private fun RuntimeException.toCoordinatorError(): AiCoordinatorError = when (this) {
    is AiConfigurationException -> AiCoordinatorError.CONFIGURATION

    is AiTimeoutException -> AiCoordinatorError.TIMEOUT

    is AiNetworkException -> AiCoordinatorError.NETWORK

    is AiHttpException -> when (statusCode) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> AiCoordinatorError.AUTHENTICATION
        HTTP_RATE_LIMITED -> AiCoordinatorError.RATE_LIMIT
        HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE_ENTITY -> AiCoordinatorError.REQUEST_PARAMETERS
        else -> AiCoordinatorError.PROVIDER
    }

    is AiProtocolException,
    is AiResponseTooLargeException -> AiCoordinatorError.INVALID_RESPONSE

    else -> AiCoordinatorError.INTERNAL
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_UNPROCESSABLE_ENTITY = 422
private const val HTTP_RATE_LIMITED = 429
