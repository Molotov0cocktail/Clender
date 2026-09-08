package com.molotov.clender.app.ai

data class AssistantMessagePresentation(val modelBody: String, val executionFeedback: String?)

fun assistantHistoryContent(content: String): String {
    val presentation = presentAssistantMessage(content)
    return if (presentation.modelBody.isNotBlank()) {
        presentation.modelBody
    } else {
        presentation.executionFeedback?.let { "应用已处理该历史请求，实际结果：$it" }.orEmpty()
    }
}

fun presentAssistantMessage(content: String): AssistantMessagePresentation =
    if (content.trim() == LEGACY_SAFETY_REJECTION) {
        AssistantMessagePresentation("", LEGACY_SAFETY_REJECTION)
    } else {
        presentWrappedMessage(content)
    }

private fun presentWrappedMessage(content: String): AssistantMessagePresentation {
    val first = receiptPrefix.find(content.trim())
        ?: return AssistantMessagePresentation(content, null)
    val feedback = normalizeReceipt(first.groupValues[1])
    var body = content.trim().substring(first.range.last + 1).trimStart()
    while (true) {
        val nested = receiptPrefix.find(body) ?: break
        body = body.substring(nested.range.last + 1).trimStart()
    }
    return AssistantMessagePresentation(body, feedback)
}

private const val LEGACY_SAFETY_REJECTION = "The assistant response could not be applied safely."

private val receiptPrefix = Regex(
    "^(No schedule changes were made\\. 本轮未修改日程。|本轮未修改日程。|" +
        "(?:[0-9]+ schedule operation\\(s\\) completed\\. )?已实际完成 [0-9]+ 项日程操作。|" +
        "已实际完成 [0-9]+ 项日程操作，[0-9]+ 项未能执行。请先核对日历再重试。|" +
        "AI 请求未完成，本轮未修改日程。|" +
        "AI 返回的操作格式不符合要求，本轮未修改日程。请重试或拆分请求。)" +
        "(?:\\s*(?:Assistant reply / 模型回复：|模型回复：)\\s*|\\s*$)"
)

private fun normalizeReceipt(receipt: String): String = when {
    receipt.startsWith("No schedule changes were made.") -> "本轮未修改日程。"
    " schedule operation(s) completed. " in receipt -> receipt.substringAfter("completed. ")
    else -> receipt
}
