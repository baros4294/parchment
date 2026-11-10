package io.parchment.ai.network

import io.parchment.ai.harness.*
import io.parchment.ai.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class HistoryReplayContractTest {
    private val session = ChatSession(id = "s", title = "t", createdAt = 1L, updatedAt = 2L, profileId = "p")

    private fun user(text: String = "hi", attachments: List<PromptAttachment> = emptyList()) =
        MessageNode("u-$text", MessageRole.USER, listOf(MessageVariant(id = "vu-$text", content = text, attachments = attachments)))

    private fun history(variant: MessageVariant) = listOf(
        user(), MessageNode("a", MessageRole.ASSISTANT, listOf(variant)), user("next")
    )

    private fun tool(id: String, thought: String = "", signature: String = "", startsStep: Boolean = false) = ToolCallRecord(
        id = id, name = "fs_read", argumentsJson = "{}", resultText = "ok",
        precedingThinking = thought, thoughtSignature = signature, startsToolStep = startsStep
    )

    private fun attachment(name: String, mime: String) = PromptAttachment(id = name, fileName = name, mimeType = mime, base64Data = "AAAA")

    private fun JsonElement.field(name: String) = jsonObject[name]?.jsonPrimitive?.content

    private fun chatReasoning(messages: JsonArray) = messages.mapNotNull { it.field("reasoning_content") }

    @Test fun paddedEncryptedReasoningIsReplayedButGluedTokensAreNot() {
        val token = "gAAAAABtoken-value_123=="
        val items = AiProviderEngine.buildOpenAiResponsesInputArray(history(MessageVariant(
            id = "v", content = "done", providerProtocol = ProviderProtocol.OPENAI_RESPONSES,
            toolCalls = listOf(tool("call_1", signature = token))
        )), session, "")
        assertEquals(token, items.single { it.field("type") == "reasoning" }.field("encrypted_content"))
        assertFalse(AiProviderEngine.isValidOpenAiEncryptedReasoning("gAAAAABone==gAAAAABtwo=="))
    }

    @Test fun chatReasoningStaysWithItsOwnWireFamily() {
        val turn = MessageVariant(
            id = "v", content = "closing", trailingThinking = "before closing",
            toolCalls = listOf(tool("call_1", thought = "before call"))
        )
        val own = AiProviderEngine.buildOpenAiChatCompletionsMessages(
            history(turn.copy(providerProtocol = ProviderProtocol.OPENAI_RESPONSES)), session, "", ""
        )
        assertEquals(listOf("before call", "before closing"), chatReasoning(own))
        val foreign = AiProviderEngine.buildOpenAiChatCompletionsMessages(
            history(turn.copy(providerProtocol = ProviderProtocol.ANTHROPIC_MESSAGES)), session, "", ""
        )
        assertEquals(emptyList(), chatReasoning(foreign))
    }

    @Test fun foldedChatHistoryOmitsReasoningOnPlainTurns() {
        val turn = MessageVariant(id = "v", content = "answer", thinkingSummary = "why", providerProtocol = ProviderProtocol.OPENAI_RESPONSES)
        assertEquals(listOf("why"), chatReasoning(AiProviderEngine.buildOpenAiChatCompletionsMessages(history(turn), session, "", "")))
        val folded = AiProviderEngine.buildOpenAiChatCompletionsMessages(history(turn), session, "", "", foldHistoricalToolsToText = true)
        assertEquals(emptyList(), chatReasoning(folded))
    }

    @Test fun geminiCarriesForeignReasoningOnlyInTheHandoffNote() {
        val handedOff = AiProviderEngine.applyCrossProviderReasoningHandoff(history(MessageVariant(
            id = "v", content = "answer", thinkingSummary = "foreign thoughts", providerProtocol = ProviderProtocol.ANTHROPIC_MESSAGES
        )), ProviderProtocol.GEMINI_INTERACTIONS, chainingActive = false)
        val wire = JsonArray(AiProviderEngine.buildGeminiGenerateContentContents(handedOff, session, "")).toString()
        assertEquals(2, wire.split("foreign thoughts").size, wire)
        assertFalse("Completed internal reasoning" in wire, wire)

        val withTools = JsonArray(AiProviderEngine.buildGeminiGenerateContentContents(history(MessageVariant(
            id = "v", content = "answer", providerProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            toolCalls = listOf(tool("call_1", thought = "foreign step"))
        )), session, "")).toString()
        assertFalse("foreign step" in withTools, withTools)
    }

    @Test fun toolIdsFitEveryWireAndStayPairedAndUnique() {
        val longId = "x".repeat(64)
        val turn = MessageVariant(id = "v", content = "done", toolCalls = listOf(
            tool("functions.fs_read:0"), tool(""), tool(""), tool(longId)
        ))
        val anthropic = AiProviderEngine.buildAnthropicBaseMessages(history(turn), session, "").flatMap { it.second }
        val useIds = anthropic.filter { it.field("type") == "tool_use" }.map { it.field("id").orEmpty() }
        val resultIds = anthropic.filter { it.field("type") == "tool_result" }.map { it.field("tool_use_id").orEmpty() }
        assertEquals(useIds, resultIds)
        assertEquals(4, useIds.toSet().size)
        assertTrue(useIds.all { Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it) }, useIds.toString())
        assertEquals(longId, useIds.last(), "IDs that already fit are left alone")

        val chat = AiProviderEngine.buildOpenAiChatCompletionsMessages(history(turn), session, "", "")
        val chatIds = chat.flatMap { it.jsonObject["tool_calls"]?.jsonArray.orEmpty() }.map { it.field("id").orEmpty() }
        assertEquals(chat.mapNotNull { it.field("tool_call_id") }, chatIds)
        assertTrue(chatIds.all { it.length <= 40 }, chatIds.toString())
        assertEquals(chatIds, AiProviderEngine.buildOpenAiChatCompletionsMessages(history(turn), session, "", "")
            .flatMap { it.jsonObject["tool_calls"]?.jsonArray.orEmpty() }.map { it.field("id").orEmpty() }, "IDs stay stable across requests")
    }

    @Test fun sequentialStepsWithoutThinkingStayApartFromParallelCalls() {
        val turn = MessageVariant(id = "v", content = "done", toolCalls = listOf(
            tool("a", startsStep = true), tool("b"), tool("c", startsStep = true)
        ))
        val roles = AiProviderEngine.buildOpenAiChatCompletionsMessages(history(turn), session, "", "").map { it.field("role") }
        assertEquals(listOf("user", "assistant", "tool", "tool", "assistant", "tool", "assistant", "user"), roles)
    }

    @Test fun onlyTheFirstCallOfAStepIsMarked() {
        val seen = mutableListOf<ToolCallRecord>()
        runBlocking {
            AiProviderEngine.executeWorkspaceToolBatchWithLiveProgress(
                workspaceRoot = "", mode = WorkspaceMode.OFF, allowShell = false,
                accessScope = ToolAccessScope(AgentPermissionPolicy()),
                calls = listOf(Triple("a", "missing_tool", "{}"), Triple("b", "missing_tool", "{}")),
                circuitBreaker = WorkspaceHarness.LoopCircuitBreaker(),
                onStatus = {}, onToolCall = { seen += it }, onGoalProgress = {}
            )
        }
        assertEquals(mapOf("a" to true, "b" to false), seen.associate { it.id to it.startsToolStep })
    }

    @Test fun attachmentsUseTheTypeEachWireAccepts() {
        val nodes = listOf(user(attachments = listOf(
            attachment("a.pdf", "application/pdf"), attachment("b.mp4", "video/mp4"), attachment("c.png", "image/png")
        )))
        val anthropic = AiProviderEngine.buildAnthropicBaseMessages(nodes, session, "").single().second
        assertEquals(listOf("document", "text", "image", "text"), anthropic.map { it.field("type") })
        assertTrue(anthropic[1].field("text").orEmpty().contains("b.mp4"))

        val chat = AiProviderEngine.buildOpenAiChatCompletionsMessages(nodes, session, "", "").single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "file", "text", "image_url"), chat.map { it.field("type") })

        val responses = AiProviderEngine.buildOpenAiResponsesInputArray(nodes, session, "").single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("input_text", "input_file", "input_text", "input_image"), responses.map { it.field("type") })
    }

    @Test fun chainsAreReusedOnlyByTheirOwnProtocolAndModel() {
        val previous = MessageVariant(
            id = "v", content = "x", providerInteractionId = "resp_1",
            providerProtocol = ProviderProtocol.OPENAI_RESPONSES, modelUsed = "model-a"
        )
        assertEquals("resp_1", AiProviderEngine.reusableProviderChainId(previous, ProviderProtocol.OPENAI_RESPONSES, "model-a"))
        assertEquals("", AiProviderEngine.reusableProviderChainId(previous, ProviderProtocol.OPENAI_RESPONSES, "model-b"))
        assertEquals("", AiProviderEngine.reusableProviderChainId(previous, ProviderProtocol.GEMINI_INTERACTIONS, "model-a"))
    }
}
