package io.parchment.ai

import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParchmentProtocolAndHarnessTest {

    @Test
    fun testPngEmbeddedCardRoundTrip() {
        val originalCard = CharacterOrScenarioCard(
            name = "Lady Eleanor of Morpeth",
            description = "{{char}} is an archivist of rare Northumbrian folios.",
            personality = "Observant, dry-witted, precise.",
            scenario = "{{user}} arrives at the candlelit scriptorium during a gale.",
            firstMessage = "*The rain lashed against the leaded glass.* \"You are late, {{user}},\" Eleanor murmured.",
            alternateGreetings = listOf("\"Did you bring the cipher?\" she asked without looking up."),
            systemPrompt = "Write in grounded historical prose.",
            postHistoryInstructions = "Keep the atmosphere quiet and suspenseful."
        )

        val pngBytes = CardAndPromptCodec.exportCardToPngBytes(originalCard)
        assertTrue(CardAndPromptCodec.isPng(pngBytes), "Exported byte array must have a valid PNG signature")

        val imported = CardAndPromptCodec.importFromBytes("eleanor.png", pngBytes).getOrThrow()
        assertTrue(imported is CardAndPromptCodec.ImportResult.CardImported)
        val restored = imported.card

        assertEquals(originalCard.name, restored.name)
        assertEquals(originalCard.description, restored.description)
        assertEquals(originalCard.personality, restored.personality)
        assertEquals(originalCard.scenario, restored.scenario)
        assertEquals(originalCard.firstMessage, restored.firstMessage)
        assertEquals(originalCard.systemPrompt, restored.systemPrompt)
        assertEquals(originalCard.postHistoryInstructions, restored.postHistoryInstructions)

        val compiled = restored.buildCompiledInstructions("Arthur")
        assertTrue(compiled.contains("Lady Eleanor of Morpeth"))
        assertTrue(compiled.contains("Arthur arrives at the candlelit scriptorium"))
    }

    @Test
    fun testPngAndJsonSystemPromptRoundTrip() {
        val preset = SystemPromptPreset(
            id = "preset-test-1",
            name = "Gothic Co-Writer",
            description = "Atmospheric literary co-writing",
            systemPrompt = "Prioritize tactile imagery and subtext.",
            authorsNoteDefault = "Keep scenes unhurried."
        )

        val pngBytes = CardAndPromptCodec.exportSystemPromptToPngBytes(preset)
        val fromPng = CardAndPromptCodec.importFromBytes("prompt.png", pngBytes).getOrThrow()
        assertTrue(fromPng is CardAndPromptCodec.ImportResult.SystemPromptImported)
        assertEquals(preset.name, fromPng.preset.name)
        assertEquals(preset.systemPrompt, fromPng.preset.systemPrompt)

        val jsonBytes = CardAndPromptCodec.exportSystemPromptToJson(preset).encodeToByteArray()
        val fromJson = CardAndPromptCodec.importFromBytes("prompt.json", jsonBytes).getOrThrow()
        assertTrue(fromJson is CardAndPromptCodec.ImportResult.SystemPromptImported)
        assertEquals(preset.systemPrompt, fromJson.preset.systemPrompt)
    }

    @Test
    fun testThemeJsonImport() {
        val customTheme = ThemeConfig(
            name = "Custom Sepia Ink",
            canvasBgHex = "#F2E8CF",
            primaryInkHex = "#221810"
        )
        val jsonBytes = CardAndPromptCodec.prettyJson.encodeToString(ThemeConfig.serializer(), customTheme).encodeToByteArray()
        val imported = CardAndPromptCodec.importFromBytes("custom.theme.json", jsonBytes).getOrThrow()
        assertTrue(imported is CardAndPromptCodec.ImportResult.ThemeImported)
        assertEquals("#F2E8CF", imported.theme.canvasBgHex)
    }

    @Test
    fun testBuiltInWorkspaceAgentHarnessTools() {
        val tempRoot = "${PlatformBridge.getAppStorageDirectoryPath()}/test-workspace-${PlatformBridge.currentTimeMillis()}"
        try {
            // Write a file in READ_WRITE mode
            val writeCall = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_1",
                toolName = "fs_write",
                argumentsJson = """{"path":"chapter1.md","content":"Line one of the manuscript.\nLine two with the secret key."}"""
            )
            assertFalse(writeCall.isError, "fs_write should succeed in READ_WRITE mode: ${writeCall.resultText}")

            // Edit the file in READ_WRITE mode
            val editCall = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_2",
                toolName = "fs_edit",
                argumentsJson = """{"path":"chapter1.md","old_text":"secret key","new_text":"brass lantern"}"""
            )
            assertFalse(editCall.isError, "fs_edit should succeed: ${editCall.resultText}")

            // Read the file in READ_ONLY mode
            val readCall = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_ONLY,
                callId = "call_3",
                toolName = "fs_read",
                argumentsJson = """{"path":"chapter1.md"}"""
            )
            assertFalse(readCall.isError)
            assertTrue(readCall.resultText.contains("brass lantern"))

            // Search in READ_ONLY mode
            val searchCall = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_ONLY,
                callId = "call_4",
                toolName = "fs_search",
                argumentsJson = """{"query":"lantern"}"""
            )
            assertFalse(searchCall.isError)
            assertTrue(searchCall.resultText.contains("chapter1.md"))

            // Ensure write is blocked in READ_ONLY mode
            val blockedWrite = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_ONLY,
                callId = "call_5",
                toolName = "fs_write",
                argumentsJson = """{"path":"chapter1.md","content":"overwritten"}"""
            )
            assertTrue(blockedWrite.isError, "fs_write must be blocked in READ_ONLY mode")
        } finally {
            PlatformBridge.wipeAllLocalData()
        }
    }
}
