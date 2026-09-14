package com.molotov.clender.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSchedulingPromptContractTest {
    @Test
    fun everyRequestMustReadCurrentTimeAndLatestSnapshotBeforeScheduling() {
        val contract = requestContract()
        assertTrue(contract.contains("每次操作前，必须先读取本轮上下文中的 Current local date/time"))
        assertTrue(contract.contains("Visible schedules（最新可见事项快照）"))
        assertTrue(contract.contains("上下文缺失、事项被截断或目标不明确时，只返回reply"))
        assertTrue(contract.contains("所有响应必须包含至少一项非空reply"))
        assertTrue(contract.contains("思考内容不能代替reply"))
        listOf("新增示例：", "修改示例：", "删除示例：", "普通回复示例：").forEach { label ->
            val example = contract.substringAfter(label).substringBefore('\n')
            val parsed = AiResponseParser().parse(example) as AiParseResult.Operations
            assertTrue(parsed.operations.any { it is AiOperation.Reply && it.message.isNotBlank() })
        }
    }

    @Test
    fun contractLimitsRootFieldsAndDistinguishesAddDefaultsFromUpdatePolicy() {
        val contract = requestContract()
        assertTrue(contract.contains("根对象只允许operations"))
        assertTrue(contract.contains("新增reminder默认普通通知"))
        assertTrue(contract.contains("没有新增操作的默认值"))
        assertTrue(contract.contains("未传字段保留旧值"))
        assertTrue(contract.contains("notification_enabled=true,alarm_enabled=false"))
    }

    @Test
    fun contractUsesOnlyCurrentVisibleIdsInsteadOfDeletedHistory() {
        assertTrue(requestContract().contains("event_id必须是本轮可见快照中的正整数"))
    }

    @Test
    fun alertUpdateExampleIsExecutableAndOperationsCannotBeHiddenInReplyText() {
        val contract = requestContract()
        val example = contract.substringAfter("修改示例：").substringBefore('\n')
        val parsed = AiResponseParser().parse(example)
        assertTrue(parsed is AiParseResult.Operations)
        val operations = (parsed as AiParseResult.Operations).operations
        assertTrue(operations.any { it is AiOperation.Update })
        assertTrue(operations.any { it is AiOperation.Reply })
        assertTrue(contract.contains("reply.message只放自然语言，禁止嵌入operations"))
        assertTrue(contract.contains("通知、闹钟、计时可以与普通事项操作混合在同一数组中"))
    }

    @Test
    fun providerContractIncludesAnExecutableEnvelopeWithExplicitActionDiscriminator() {
        val contract = requestContract()
        val example = contract.substringAfter("新增示例：").substringBefore('\n')
        val parsed = AiResponseParser().parse(example)
        assertTrue("The exact prompt example must parse", parsed is AiParseResult.Operations)
        val operations = (parsed as AiParseResult.Operations).operations
        assertEquals(2, operations.size)
        assertTrue(operations.first() is AiOperation.Add)
        assertTrue(operations.last() is AiOperation.Reply)
        assertTrue(contract.contains("每项action只能是add、update、delete或reply"))
    }

    @Test
    fun providerContractIncludesExecutableFlatUpdateAndDeleteExamples() {
        val contract = requestContract()
        val update = contract.substringAfter("修改示例：").substringBefore('\n')
        val deletion = contract.substringAfter("删除示例：").substringBefore('\n')
        val parsedUpdate = AiResponseParser().parse(update)
        val parsedDeletion = AiResponseParser().parse(deletion)
        assertTrue("The exact update example must parse", parsedUpdate is AiParseResult.Operations)
        assertTrue(
            "The exact delete example must parse",
            parsedDeletion is AiParseResult.Operations
        )
        assertTrue(
            (parsedUpdate as AiParseResult.Operations).operations.first() is AiOperation.Update
        )
        assertTrue(
            (parsedDeletion as AiParseResult.Operations).operations.first() is AiOperation.Delete
        )
    }

    @Test
    fun providerContractExplicitlyForbidsNestedPatchAndExtraOperationFields() {
        val contract = requestContract()
        assertTrue(contract.contains("所有字段与action同级"))
        assertTrue(contract.contains("禁止嵌套patch"))
        assertTrue(contract.contains("delete只允许action,event_id"))
        assertTrue(contract.contains("reply只允许action,message"))
        assertTrue(contract.contains("禁止嵌套patch或额外字段"))
    }

    @Test
    fun providerContractRequestsChineseThinkingAndRepliesAndDistinguishesAdvanceAlerts() {
        val contract = requestContract()
        assertTrue(contract.contains("思考内容和回复均使用中文"))
        assertTrue(contract.startsWith("你是 Clender 日程助手，思考内容和回复均使用中文"))
        assertTrue(contract.contains("开始后多少分钟到期"))
        assertTrue(contract.contains("再另建reminder，不能用计时器代替"))
        assertTrue(contract.contains("示例日期和ID不得用作当前日期或真实操作对象"))
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
                contextWindow = 4_096,
                maxOutputTokens = 100
            )
        )
        val contract = result.messages.first().content

        assertTrue(contract.startsWith(DEFAULT_AI_SYSTEM_CONTRACT))
        assertTrue(contract.contains("完整JSON"))
        assertTrue(contract.contains("不要声称已执行成功"))
        assertTrue(result.inputTokenEstimate <= 3_587)
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
            contextWindow = 4_096,
            maxOutputTokens = 100
        )
    ).messages.first().content
}
