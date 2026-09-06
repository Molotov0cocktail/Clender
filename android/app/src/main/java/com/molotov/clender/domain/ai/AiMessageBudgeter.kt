package com.molotov.clender.domain.ai

import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import kotlin.math.ceil

data class AiRequestMessage(val role: String, val content: String)

data class AiBudgetInput(
    val mandatorySystemContract: String = DEFAULT_AI_SYSTEM_CONTRACT,
    val systemPrompt: String,
    val personality: String,
    val scheduleContext: String,
    val history: List<Message>,
    val contextWindow: Int,
    val maxOutputTokens: Int
)

data class AiBudgetResult(
    val messages: List<AiRequestMessage>,
    val inputTokenEstimate: Int,
    val safetyMarginTokens: Int
)

class AiMessageBudgeter {
    fun estimateTokens(content: String): Int = if (content.isEmpty()) {
        0
    } else {
        ceil(content.toByteArray(Charsets.UTF_8).size / UTF8_BYTES_PER_TOKEN).toInt()
    }

    fun countTokens(messages: List<AiRequestMessage>): Int = messages.sumOf { message ->
        estimateTokens(message.content) + MESSAGE_OVERHEAD_TOKENS
    }

    fun build(input: AiBudgetInput): AiBudgetResult {
        require(input.contextWindow > 0) { "Context window must be positive" }
        require(input.maxOutputTokens > 0) { "Output budget must be positive" }
        require(input.maxOutputTokens < input.contextWindow) {
            "Output budget must be smaller than the context window"
        }
        require(input.mandatorySystemContract.isNotBlank()) {
            "Mandatory AI operation contract cannot be blank"
        }
        val safetyMargin = maxOf(
            MINIMUM_SAFETY_MARGIN_TOKENS,
            input.contextWindow / SAFETY_MARGIN_DIVISOR
        )
        val inputLimit = input.contextWindow - input.maxOutputTokens - safetyMargin
        require(inputLimit > MESSAGE_OVERHEAD_TOKENS) { "No input budget remains" }

        val systemContentBudget = inputLimit - MESSAGE_OVERHEAD_TOKENS
        val fullSystemContent = buildSystemContent(input)
        val system = AiRequestMessage(
            "system",
            if (estimateTokens(fullSystemContent) <= systemContentBudget) {
                fullSystemContent
            } else {
                fitSystemContent(input, systemContentBudget)
            }
        )
        val retainedNewestFirst = mutableListOf<AiRequestMessage>()
        var used = countTokens(listOf(system))
        input.history.asReversed().asSequence()
            .filter { it.role != MessageRole.THINK }
            .forEach { message ->
                val request = AiRequestMessage(message.role.requestRole(), message.content)
                val cost = countTokens(listOf(request))
                when {
                    used + cost <= inputLimit -> {
                        retainedNewestFirst += request
                        used += cost
                    }

                    retainedNewestFirst.isEmpty() -> {
                        val contentBudget = inputLimit - used - MESSAGE_OVERHEAD_TOKENS
                        val truncated = truncateTail(message.content, contentBudget)
                        if (truncated.isNotEmpty()) {
                            val retained = request.copy(content = truncated)
                            retainedNewestFirst += retained
                            used += countTokens(listOf(retained))
                        }
                    }
                }
            }
        val messages = buildList {
            add(system)
            addAll(retainedNewestFirst.asReversed())
        }
        return AiBudgetResult(messages, countTokens(messages), safetyMargin)
    }

    private fun buildSystemContent(input: AiBudgetInput): String = buildList {
        add(input.mandatorySystemContract)
        add(input.systemPrompt)
        if (input.personality.isNotBlank()) add(input.personality)
        if (input.scheduleContext.isNotBlank()) add(input.scheduleContext)
    }.joinToString("\n\n")

    private fun fitSystemContent(input: AiBudgetInput, tokenBudget: Int): String {
        val mandatoryCost = estimateTokens(input.mandatorySystemContract)
        require(mandatoryCost <= tokenBudget) {
            "Mandatory AI operation contract exceeds the input budget"
        }
        val optionalSections = listOf(
            input.systemPrompt to Retention.HEAD,
            input.personality to Retention.HEAD,
            input.scheduleContext to Retention.TAIL
        ).filter { (content, _) -> content.isNotBlank() }
        if (optionalSections.isEmpty()) return input.mandatorySystemContract
        val separatorCost = estimateTokens("\n\n") * optionalSections.size
        val optionalBudget = (tokenBudget - mandatoryCost - separatorCost).coerceAtLeast(0)
        val sectionBudget = optionalBudget / optionalSections.size
        val retainedOptional = optionalSections.mapNotNull { (content, retention) ->
            if (sectionBudget == 0) return@mapNotNull null
            when (retention) {
                Retention.HEAD -> truncateHead(content, sectionBudget)
                Retention.TAIL -> truncateTail(content, sectionBudget)
            }.takeIf(String::isNotEmpty)
        }
        return (listOf(input.mandatorySystemContract) + retainedOptional).joinToString("\n\n")
    }

    private fun truncateHead(content: String, tokenBudget: Int): String {
        if (tokenBudget <= 0) return ""
        var end = 0
        while (end < content.length) {
            val next = content.offsetByCodePoints(end, 1)
            if (estimateTokens(content.substring(0, next)) > tokenBudget) break
            end = next
        }
        return content.substring(0, end)
    }

    private fun truncateTail(content: String, tokenBudget: Int): String {
        if (tokenBudget <= 0) return ""
        var start = content.length
        while (start > 0) {
            val previous = content.offsetByCodePoints(start, -1)
            if (estimateTokens(content.substring(previous)) > tokenBudget) break
            start = previous
        }
        return content.substring(start)
    }

    companion object {
        const val MESSAGE_OVERHEAD_TOKENS = 4
        private const val MINIMUM_SAFETY_MARGIN_TOKENS = 32
        private const val SAFETY_MARGIN_DIVISOR = 10
        private const val UTF8_BYTES_PER_TOKEN = 3.0
    }

    private enum class Retention {
        HEAD,
        TAIL
    }
}

const val DEFAULT_AI_SYSTEM_CONTRACT: String =
    "Clender AI operation contract: reply with JSON only when changing schedules. " +
        "Use {\"operations\":[...]} and only actions add, update, delete, reply. " +
        "Allowed event fields are event_type, title, start_time, end_time, description, " +
        "estimated_duration; delete/update require a positive event_id. " +
        "Times use strict YYYY-MM-DD HH:mm local wall-clock format."

private fun MessageRole.requestRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.THINK -> "think"
}
