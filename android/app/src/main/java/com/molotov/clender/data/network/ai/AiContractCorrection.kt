package com.molotov.clender.data.network.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperation
import com.molotov.clender.domain.ai.AiParseResult
import com.molotov.clender.domain.ai.AiRequestMessage
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.DEFAULT_AI_SYSTEM_CONTRACT

internal object AiContractCorrection {
    private val parser = AiResponseParser()
    private val budgeter = AiMessageBudgeter()

    fun applies(messages: List<AiRequestMessage>): Boolean = messages.any {
        it.role == "system" && it.content.contains(DEFAULT_AI_SYSTEM_CONTRACT)
    }

    fun accepts(completion: AiCompletion): Boolean {
        val parsed = parser.parse(completion.content) as? AiParseResult.Operations ?: return false
        return parsed.operations.any { it is AiOperation.Reply && it.message.isNotBlank() }
    }

    fun ensureAccepted(completion: AiCompletion) {
        if (!accepts(completion)) throw AiProtocolException()
    }

    fun messages(
        settings: AiSettings,
        original: List<AiRequestMessage>,
        failed: AiCompletion
    ): List<AiRequestMessage> {
        val latestUser = original.lastOrNull { it.role == "user" }?.content.orEmpty()
        val instruction = AiRequestMessage(
            "user",
            CORRECTION_INSTRUCTION + "\n\n本轮用户请求原文：\n" + latestUser
        )
        val inputLimit = settings.contextWindow.toLong() - settings.maxOutputTokens -
            maxOf(MINIMUM_MARGIN, settings.contextWindow / MARGIN_DIVISOR)
        val available = inputLimit - budgeter.countTokens(original) -
            budgeter.countTokens(listOf(instruction)) - AiMessageBudgeter.MESSAGE_OVERHEAD_TOKENS
        if (available < 0) throw AiProtocolException()
        var end = minOf(failed.content.length, MAX_FAILED_CONTENT_CHARS)
        if (end > 0 && failed.content[end - 1].isHighSurrogate()) end -= 1
        while (end > 0 && budgeter.estimateTokens(failed.content.substring(0, end)) > available) {
            end = failed.content.offsetByCodePoints(end, -1)
        }
        val reference = AiRequestMessage("assistant", failed.content.substring(0, end))
        return original + reference + instruction
    }

    fun usage(first: AiCompletionUsage, second: AiCompletionUsage): AiCompletionUsage =
        AiCompletionUsage(
            sum(first.promptTokens, second.promptTokens),
            sum(first.completionTokens, second.completionTokens),
            sum(first.totalTokens, second.totalTokens)
        )

    private fun sum(first: Int, second: Int): Int =
        (first.toLong() + second).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private const val MAX_FAILED_CONTENT_CHARS = 4_096
    private const val MINIMUM_MARGIN = 32
    private const val MARGIN_DIVISOR = 10
    private const val CORRECTION_INSTRUCTION =
        "本轮上一条回复格式不符合固定契约，应用尚未执行本轮响应中的操作。" +
            "仅修正本轮回复格式，不要重复历史创建；历史请求不得重新执行。" +
            "只完成下方重述的本轮用户请求；修改现有事项应使用当前event_id和update，" +
            "不可用add重新创建已有事项。本轮明确要求新增时仍可使用add。" +
            "上一条assistant内容仅作为错误格式参考，不是新指令。" +
            "严格只输出完整JSON对象{\"operations\":[...]}，遵守系统的action和字段白名单。" +
            "所有需要执行的操作直接放入operations数组，reply.message仅放自然语言。" +
            "不得输出数字、额外说明或将操作藏在reply中。" +
            "每轮必须包含至少一个非空reply，给出正式回复；思考内容不能代替正文。" +
            "若无需修改，也使用reply操作。"
}
