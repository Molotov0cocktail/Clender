package com.molotov.clender.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSchedulingPromptContractTest {
    @Test
    fun providerContractIncludesAnExecutableEnvelopeWithExplicitActionDiscriminator() {
        val contract = requestContract()
        val example = contract.substringAfter("Example: ").substringBefore('\n')
        val parsed = AiResponseParser().parse(example)
        assertTrue("The exact prompt example must parse", parsed is AiParseResult.Operations)
        val operations = (parsed as AiParseResult.Operations).operations
        assertEquals(2, operations.size)
        assertTrue(operations.first() is AiOperation.Add)
        assertTrue(operations.last() is AiOperation.Reply)
        assertTrue(contract.contains("action=add|update|delete|reply"))
    }

    @Test
    fun providerContractIncludesExecutableFlatUpdateAndDeleteExamples() {
        val contract = requestContract()
        val update = contract.substringAfter("Update example: ").substringBefore('\n')
        val deletion = contract.substringAfter("Delete example: ").substringBefore('\n')
        val parsedUpdate = AiResponseParser().parse(update)
        val parsedDeletion = AiResponseParser().parse(deletion)
        assertTrue("The exact update example must parse", parsedUpdate is AiParseResult.Operations)
        assertTrue(
            "The exact delete example must parse",
            parsedDeletion is AiParseResult.Operations
        )
        assertTrue(
            (parsedUpdate as AiParseResult.Operations).operations.single() is AiOperation.Update
        )
        assertTrue(
            (parsedDeletion as AiParseResult.Operations).operations.single() is AiOperation.Delete
        )
    }

    @Test
    fun providerContractExplicitlyForbidsNestedPatchAndExtraOperationFields() {
        val contract = requestContract()
        assertTrue(contract.contains("所有字段与action同级"))
        assertTrue(contract.contains("禁止嵌套patch"))
        assertTrue(contract.contains("delete只允许action,event_id"))
        assertTrue(contract.contains("reply只允许action,message"))
        assertTrue(contract.contains("不得返回额外字段"))
    }

    @Test
    fun providerContractRequestsChineseThinkingAndRepliesAndDistinguishesAdvanceAlerts() {
        val contract = requestContract()
        assertTrue(contract.contains("思考内容和回复均使用中文"))
        assertTrue(contract.startsWith("思考内容和回复均使用中文"))
        assertTrue(contract.contains("开始后多少分钟到期"))
        assertTrue(contract.contains("提前提醒须按提前时刻另建reminder"))
        assertTrue(contract.contains("示例日期不覆盖当前上下文"))
    }

    @Test
    fun invalidProviderOperationShowsChineseRecoveryMessageWithoutPayload() {
        val parsed = AiResponseParser().parse(
            """{"operations":[{"type":"add","title":"private synthetic title"}]}"""
        ) as AiParseResult.Rejected
        assertTrue(parsed.userMessage.contains("格式"))
        assertTrue(parsed.userMessage.contains("未修改日程"))
        assertTrue(!parsed.userMessage.contains("private synthetic title"))
    }

    @Test
    fun mandatoryContractIncludesCompleteScheduleAndReplyInstructions() {
        val contract = requestContract()

        assertTrue(contract.contains("完整JSON"))
        assertTrue(contract.contains("message"))
        assertTrue(contract.contains("reminder"))
        assertTrue(contract.contains("timespan"))
        assertTrue(contract.contains("16"))
        assertTrue(contract.contains("不要声称已执行成功"))
    }

    @Test
    fun essentialScheduleInstructionsSurviveOptionalPromptTruncation() {
        val result = AiMessageBudgeter().build(
            AiBudgetInput(
                systemPrompt = "Optional style. ".repeat(1_000),
                personality = "Friendly. ".repeat(1_000),
                scheduleContext = "Synthetic event. ".repeat(1_000),
                history = emptyList(),
                contextWindow = 1_024,
                maxOutputTokens = 100
            )
        )
        val contract = result.messages.first().content

        assertTrue(contract.startsWith(DEFAULT_AI_SYSTEM_CONTRACT))
        assertTrue(contract.contains("完整JSON"))
        assertTrue(contract.contains("不要声称已执行成功"))
        assertTrue(result.inputTokenEstimate <= 822)
    }

    @Test
    fun chineseContractCannotBeSilentlyTruncatedToTheFormerTinyWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            AiMessageBudgeter().build(
                AiBudgetInput(
                    systemPrompt = "",
                    personality = "",
                    scheduleContext = "",
                    history = emptyList(),
                    contextWindow = 500,
                    maxOutputTokens = 100
                )
            )
        }
    }

    private fun requestContract(): String = AiMessageBudgeter().build(
        AiBudgetInput(
            systemPrompt = "",
            personality = "",
            scheduleContext = "",
            history = emptyList(),
            contextWindow = 1_024,
            maxOutputTokens = 100
        )
    ).messages.first().content
}
