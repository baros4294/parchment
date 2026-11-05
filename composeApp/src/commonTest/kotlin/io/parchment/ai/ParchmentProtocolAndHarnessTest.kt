package io.parchment.ai

import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.AssistantAgent
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.SystemPromptPreset
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.WebSourceCitation
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.platform.normalizePickedBinaryFile
import io.parchment.ai.protocol.CardAndPromptCodec
import io.parchment.ai.protocol.O200kTokenizer
import io.parchment.ai.ui.BUILTIN_LAB_SVG_PATHS
import io.parchment.ai.ui.DiffLineKind
import io.parchment.ai.ui.computeUnifiedLineDiff
import io.parchment.ai.ui.extractLatestThoughtPeek
import io.parchment.ai.ui.findClosingMarkdownLinkParen
import io.parchment.ai.ui.formatDurationFromMs
import io.parchment.ai.ui.parseSvgColorString
import io.parchment.ai.ui.parseSvgPathNormalized
import io.parchment.ai.ui.parseSvgSceneDocument
import io.parchment.ai.ui.resolveLabSlugForModelOrProvider
import io.parchment.ai.ui.AiGeneratedChatArtifact
import io.parchment.ai.ui.extractSessionVaultItems
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

            // Verify fs_edit reports exact 1-based line numbers when old_text matches multiple locations
            WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_dup_write",
                toolName = "fs_write",
                argumentsJson = """{"path":"chapter1.md","content":"token_alpha\nmiddle_line\ntoken_alpha"}"""
            )
            val dupEdit = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_dup_edit",
                toolName = "fs_edit",
                argumentsJson = """{"path":"chapter1.md","old_text":"token_alpha","new_text":"token_beta","replace_all":false}"""
            )
            assertTrue(dupEdit.isError)
            assertTrue(dupEdit.resultText.contains("lines 1, 3"), "fs_edit must report exact matching line numbers (1, 3): ${dupEdit.resultText}")

            // Verify agent_memory append preserves blank lines without injecting 'N:' line numbers
            WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_mem_1",
                toolName = "agent_memory",
                argumentsJson = """{"action":"overwrite","content":"Rule 1\n\nRule 2"}"""
            )
            WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_mem_2",
                toolName = "agent_memory",
                argumentsJson = """{"action":"append","content":"Rule 3"}"""
            )
            val memRead = WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_ONLY,
                callId = "call_mem_3",
                toolName = "agent_memory",
                argumentsJson = """{"action":"read"}"""
            )
            assertFalse(memRead.resultText.contains("2: 2:"), "agent_memory append must not corrupt blank lines with line prefixes: ${memRead.resultText}")
            assertTrue(memRead.resultText.contains("Rule 3"))
        } finally {
            // Never call wipeAllLocalData() in tests; only clean up the isolated test file
            WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_cleanup",
                toolName = "fs_write",
                argumentsJson = """{"path":"chapter1.md","content":""}"""
            )
        }
    }

    @Test
    fun testClipboardAndEmojiKitchenMimeAndExtensionNormalization() {
        // 1. Extensionless PNG (e.g. Gboard Emoji Kitchen sticker URI with numeric displayName)
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val normPng = normalizePickedBinaryFile("100004921", pngHeader, explicitMimeType = "image/png", fallbackTimestamp = 1700L)
        assertEquals("image/png", normPng.mimeType)
        assertEquals("100004921.png", normPng.fileName)

        // 2. Extensionless GIF (e.g. Gboard GIF search commitContent)
        val gifHeader = "GIF89a\u0001\u0000\u0001\u0000".encodeToByteArray()
        val normGif = normalizePickedBinaryFile("gboard_sticker", gifHeader, explicitMimeType = "", fallbackTimestamp = 1701L)
        assertEquals("image/gif", normGif.mimeType)
        assertEquals("gboard_sticker.gif", normGif.fileName)

        // 3. Extensionless WebP (e.g. Android sticker mashup)
        val webpHeader = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50
        )
        val normWebp = normalizePickedBinaryFile("", webpHeader, explicitMimeType = "image/webp", fallbackTimestamp = 1702L)
        assertEquals("image/webp", normWebp.mimeType)
        assertEquals("pasted-image-1702.webp", normWebp.fileName)

        // 4. PDF magic header detection
        val pdfBytes = "%PDF-1.7\n1 0 obj".encodeToByteArray()
        val normPdf = normalizePickedBinaryFile("manuscript_proof", pdfBytes, explicitMimeType = "", fallbackTimestamp = 1703L)
        assertEquals("application/pdf", normPdf.mimeType)
        assertEquals("manuscript_proof.pdf", normPdf.fileName)
    }

    @Test
    fun testGoalTodoWriteStopHookAndCircuitBreaker() {
        val breaker = WorkspaceHarness.LoopCircuitBreaker(initialGoal = null)

        // 1. Normal chat / roleplay turn (no goal, no todos) -> Stop Hook must NOT fire (null)
        assertEquals(null, breaker.evaluateStopHookPrompt(), "Normal chat without todos must exit in 1 pass")

        // 2. Model calls todo_write (works even with WorkspaceMode.OFF!)
        val todoCall = WorkspaceHarness.executeToolCall(
            workspaceRoot = "",
            mode = WorkspaceMode.OFF,
            callId = "tc_todo_1",
            toolName = "todo_write",
            argumentsJson = """{"goal":"Write 2-part treatise","todos":[{"id":"1","content":"Part 1","status":"completed"},{"id":"2","content":"Part 2","status":"in_progress"}]}"""
        )
        assertFalse(todoCall.isError, "todo_write must work even when WorkspaceMode is OFF")
        breaker.onToolExecuted(todoCall)

        // 3. Stop Hook must fire while step 2 is still 'in_progress'
        val stopHook1 = breaker.evaluateStopHookPrompt()
        assertTrue(stopHook1 != null && stopHook1.contains("Part 2"), "Stop hook must remind model of incomplete step 2")

        // 4. Model calls goal_complete -> Stop Hook must release and return null
        val doneCall = WorkspaceHarness.executeToolCall(
            workspaceRoot = "",
            mode = WorkspaceMode.OFF,
            callId = "tc_done_1",
            toolName = "goal_complete",
            argumentsJson = """{"summary":"Treatise completed","verification":"Both parts verified"}"""
        )
        breaker.onToolExecuted(doneCall)
        assertTrue(breaker.goalState?.isCompleted == true)
        assertEquals(null, breaker.evaluateStopHookPrompt(), "Stop hook must not fire after goal_complete")

        // 5. Repeated identical tool errors must trip the Circuit Breaker on the 3rd repeat
        val failingCall = WorkspaceHarness.executeToolCall(
            workspaceRoot = "",
            mode = WorkspaceMode.OFF,
            callId = "fail_1",
            toolName = "fs_write",
            argumentsJson = """{"path":"a.txt","content":"x"}"""
        )
        assertTrue(failingCall.isError)
        breaker.onToolExecuted(failingCall)
        assertEquals(null, breaker.checkTripped())
        val warnCall = breaker.onToolExecuted(failingCall)
        assertTrue(warnCall.resultText.contains("CIRCUIT BREAKER WARNING"))
        assertEquals(null, breaker.checkTripped())
        breaker.onToolExecuted(failingCall)
        assertTrue(breaker.checkTripped() != null, "3 consecutive identical failing tool calls must trip the circuit breaker")

        // 6. Rolling Context Compactor must compact verbose outputs older than 8 steps
        val longOutput = "Line of file content\n".repeat(60)
        val compactedOld = WorkspaceHarness.compactStaleToolOutputText(1, 15, "fs_read", longOutput)
        assertTrue(compactedOld.startsWith("[Compacted step #1"), "Old verbose tool output (>8 steps ago) must be compacted")
        val recentUnchanged = WorkspaceHarness.compactStaleToolOutputText(12, 15, "fs_read", longOutput)
        assertEquals(longOutput, recentUnchanged, "Recent tool output (<=8 steps ago) must remain verbatim")
    }

    @Test
    fun testInterruptedThinkingAndToolTurnPreservationAcrossAllProtocols() {
        val session = ChatSession(
            id = "sess-test-proto",
            title = "Protocol Test",
            createdAt = 1L,
            updatedAt = 2L,
            profileId = "prof-1"
        )

        val userTurn1 = MessageNode(
            id = "u1",
            role = MessageRole.USER,
            variants = listOf(
                MessageVariant(
                    id = "v-u1",
                    content = "Inspect the project structure.",
                    attachments = listOf(
                        PromptAttachment(
                            id = "att-pdf",
                            fileName = "spec.pdf",
                            mimeType = "application/pdf",
                            base64Data = "JVBERi0xLjcK"
                        )
                    )
                )
            )
        )

        // Assistant turn interrupted after thinking & executing a tool (content has an inline interruption notice, no prose yet)
        val interruptedAssistantTurn = MessageNode(
            id = "a1",
            role = MessageRole.ASSISTANT,
            variants = listOf(
                MessageVariant(
                    id = "v-a1",
                    content = "*[Stream paused/interrupted: socket timeout — tap '→ Continue' or reply 'continue' to resume seamlessly]*",
                    thinkingSummary = "I should list the workspace directory first to see the project layout.",
                    thinkingSignature = "sig_argon_crypto_991",
                    toolCalls = listOf(
                        ToolCallRecord(
                            id = "call_fs_1",
                            name = "fs_list",
                            argumentsJson = """{"path":""}""",
                            resultText = "composeApp/\nbuild.gradle.kts",
                            isError = false,
                            thoughtSignature = "sig_argon_crypto_991"
                        )
                    )
                )
            )
        )

        val userTurn2 = MessageNode(
            id = "u2",
            role = MessageRole.USER,
            variants = listOf(
                MessageVariant(
                    id = "v-u2",
                    content = "continue"
                )
            )
        )

        val rawHistory = listOf(userTurn1, interruptedAssistantTurn, userTurn2)
        val sanitized = AiProviderEngine.sanitizeContextNodes(rawHistory)
        assertEquals(3, sanitized.size, "Interrupted assistant turn with thinking/tools MUST NOT be dropped by sanitizeContextNodes")
        assertEquals("", AiProviderEngine.stripInlineErrorNotices(interruptedAssistantTurn.activeVariant.content))

        // 1. Verify Gemini Interactions API stateless steps array
        val interactionsSteps = AiProviderEngine.buildGeminiInteractionsTurnsArray(sanitized, session, "")
        val stepTypes = interactionsSteps.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
        assertEquals(
            listOf("user_input", "thought", "function_call", "function_result", "user_input"),
            stepTypes,
            "Gemini Interactions input must contain user_input -> thought -> function_call -> function_result -> user_input"
        )
        // Check PDF attachment is mapped to "document", not "image"
        val firstUserContent = interactionsSteps[0].jsonObject["content"]!!.jsonArray
        assertEquals("document", firstUserContent[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        // Check thought step preserves both signature and summary
        val thoughtStep = interactionsSteps[1].jsonObject
        assertEquals("sig_argon_crypto_991", thoughtStep["signature"]?.jsonPrimitive?.contentOrNull)
        assertTrue(thoughtStep["summary"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("project layout"))
        // Check function_result step wraps result in {"result": ...}
        val fnResultStep = interactionsSteps[3].jsonObject
        assertEquals("call_fs_1", fnResultStep["call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "composeApp/\nbuild.gradle.kts",
            fnResultStep["result"]?.jsonObject?.get("result")?.jsonPrimitive?.contentOrNull
        )

        // 2. Verify Google GenerateContent (:streamGenerateContent) contents
        val genContents = AiProviderEngine.buildGeminiGenerateContentContents(sanitized, session, "")
        assertTrue(genContents.size >= 3, "GenerateContent must keep user1, model thought+functionCall, and user functionResponse+continue separated")
        val firstUserText = genContents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Inspect the project structure.", firstUserText, "First user prompt must not be concatenated with 'continue'")

        // 3. Verify OpenAI Responses API (/v1/responses) input items
        val responsesItems = AiProviderEngine.buildOpenAiResponsesInputArray(sanitized, session, "")
        val respTypes = responsesItems.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: it.jsonObject["role"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("user", "reasoning", "function_call", "function_call_output", "user"), respTypes)
        assertEquals("sig_argon_crypto_991", responsesItems[1].jsonObject["encrypted_content"]?.jsonPrimitive?.contentOrNull)
        val firstRespUserContent = responsesItems[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_file", firstRespUserContent[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)

        // 4. Verify OpenAI Chat Completions (/v1/chat/completions) messages
        val chatMsgs = AiProviderEngine.buildOpenAiChatCompletionsMessages(sanitized, session, "Sys", "")
        val chatRoles = chatMsgs.map { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), chatRoles)

        // 5. Verify Anthropic Messages (/v1/messages) base messages
        val anthropicMsgs = AiProviderEngine.buildAnthropicBaseMessages(sanitized, session, "")
        assertEquals(listOf("user", "assistant", "user"), anthropicMsgs.map { it.first })
        assertEquals("document", anthropicMsgs[0].second[0]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("thinking", anthropicMsgs[1].second[0]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool_use", anthropicMsgs[1].second[1]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool_result", anthropicMsgs[2].second[0]["type"]?.jsonPrimitive?.contentOrNull)

        // 6. Verify adjacent user messages are always separated by \n\n if an assistant message between them is muted
        val mutedHistory = AiProviderEngine.sanitizeContextNodes(
            listOf(userTurn1, interruptedAssistantTurn.copy(includedInContext = false), userTurn2)
        )
        val mergedInteractions = AiProviderEngine.buildGeminiInteractionsTurnsArray(mutedHistory, session, "")
        val mergedUserTxt = mergedInteractions[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Inspect the project structure.\n\ncontinue", mergedUserTxt)

        // 7. Verify omp-inspired 10x 60s patient auto-retry configuration, error classification, and tool intent telemetry
        assertEquals(10, AiProviderEngine.MAX_PRE_TOKEN_RETRIES, "omp-inspired patient retry must allow 10 attempts")
        assertEquals(60, AiProviderEngine.RETRY_WAIT_SECONDS, "omp-inspired patient retry must wait 60s per attempt")
        assertTrue(AiProviderEngine.isTransientOrProxyTimeoutError(RuntimeException("HTTP 429: Too Many Requests")))
        assertTrue(AiProviderEngine.isTransientOrProxyTimeoutError(RuntimeException("HTTP 524: A timeout occurred")))
        assertTrue(AiProviderEngine.isTransientOrProxyTimeoutError(RuntimeException("Upstream proxy reset connection")))
        assertFalse(AiProviderEngine.isTransientOrProxyTimeoutError(RuntimeException("HTTP 401: invalid_api_key")))
        assertFalse(AiProviderEngine.isTransientOrProxyTimeoutError(RuntimeException("HTTP 403: permission_denied")))
        assertEquals(
            "Reading src/Main.kt (lines 10..59)",
            WorkspaceHarness.describeToolCallIntent("fs_read", """{"path":"src/Main.kt","start_line":10,"max_lines":50}""")
        )

        // 8. Verify chronological interleaved multi-step replay (think1 -> tool1 -> think2 -> tool2)
        val interleavedTurn = MessageNode(
            id = "a-interleaved",
            role = MessageRole.ASSISTANT,
            variants = listOf(
                MessageVariant(
                    id = "v-interleaved",
                    content = "Both steps finished.",
                    thinkingSummary = "Think step 1\nThink step 2",
                    thinkingSignature = "sig_2",
                    toolCalls = listOf(
                        ToolCallRecord(
                            id = "c1",
                            name = "fs_list",
                            argumentsJson = """{"path":""}""",
                            resultText = "src/",
                            thoughtSignature = "sig_1",
                            precedingThinking = "Think step 1"
                        ),
                        ToolCallRecord(
                            id = "c2",
                            name = "fs_read",
                            argumentsJson = """{"path":"src/Main.kt"}""",
                            resultText = "fun main() {}",
                            thoughtSignature = "sig_2",
                            precedingThinking = "Think step 2"
                        )
                    )
                )
            )
        )
        val interleavedSteps = AiProviderEngine.buildGeminiInteractionsTurnsArray(listOf(userTurn1, interleavedTurn), session, "")
        val interleavedTypes = interleavedSteps.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
        assertEquals(
            listOf("user_input", "thought", "function_call", "function_result", "thought", "function_call", "function_result", "model_output"),
            interleavedTypes,
            "Multi-step tool turn must replay in chronological interleaved order: thought1 -> call1 -> result1 -> thought2 -> call2 -> result2 -> model_output"
        )
        assertTrue(OtaUpdateEngine.compareVersions("1.2.0", "1.2.0-beta.1") > 0, "Stable release 1.2.0 must be newer than 1.2.0-beta.1")

        // 9. Verify Smart Primary-First Legacy Protocol Fallback Classifier & Zero-Loss Mid-Turn Handoff
        assertFalse(
            AiProviderEngine.shouldFallbackToLegacyProtocol(RuntimeException("HTTP 401: invalid_api_key"), useLegacyFallbackEndpoint = true),
            "Auth 401 error must fail fast and never fall back to legacy endpoint"
        )
        assertFalse(
            AiProviderEngine.shouldFallbackToLegacyProtocol(RuntimeException("HTTP 403: permission_denied"), useLegacyFallbackEndpoint = true),
            "Auth 403 error must fail fast and never fall back to legacy endpoint"
        )
        assertTrue(
            AiProviderEngine.shouldFallbackToLegacyProtocol(RuntimeException("HTTP 404: Not Found"), useLegacyFallbackEndpoint = false),
            "Unsupported 404 endpoint must always auto-fallback even when toggle is off"
        )
        assertFalse(
            AiProviderEngine.shouldFallbackToLegacyProtocol(RuntimeException("HTTP 502: Bad Gateway"), useLegacyFallbackEndpoint = false),
            "When toggle is off, non-404 errors must not fall back"
        )
        assertTrue(
            AiProviderEngine.shouldFallbackToLegacyProtocol(
                AiProviderEngine.StepRetriesExhaustedException("Retries exhausted", RuntimeException("HTTP 502: Bad Gateway")),
                useLegacyFallbackEndpoint = true
            ),
            "When useLegacyFallbackEndpoint is on, primary protocol errors after probe retries must fall back to legacy endpoint"
        )
        assertTrue(
            AiProviderEngine.shouldFallbackToLegacyProtocol(RuntimeException("HTTP 400: unsupported_parameter"), useLegacyFallbackEndpoint = true),
            "When useLegacyFallbackEndpoint is on, HTTP 400 schema rejection on primary endpoint must fall back to legacy endpoint"
        )

        val (handoffNodes, handoffPrefix) = AiProviderEngine.buildEffectiveHistoryAndPrefix(
            baseHistoryNodes = listOf(userTurn1),
            continuePrefix = "Partial prose before step 2 failed.",
            thinkingText = "Step 1 thought",
            trailingThinkingText = "Step 2 thought",
            signatureText = "sig_step_1",
            executedTools = listOf(
                ToolCallRecord(
                    id = "call_1",
                    name = "fs_list",
                    argumentsJson = """{"path":""}""",
                    resultText = "README.md",
                    thoughtSignature = "sig_step_1",
                    precedingThinking = "Step 1 thought"
                )
            ),
            generatedMedia = emptyList(),
            protocol = io.parchment.ai.model.ProviderProtocol.GEMINI_INTERACTIONS,
            model = "gemini-4-argon",
            passTag = "test-fallback"
        )
        assertEquals(3, handoffNodes.size, "Mid-turn fallback handoff must append synthesized partial assistant turn + resume user prompt")
        assertEquals("", handoffPrefix, "When tool calls exist, partial prose is embedded in synthesized assistant turn and resume prompt")
        assertEquals(1, handoffNodes[1].activeVariant.toolCalls.size, "Synthesized assistant node must preserve completed tool calls")
        assertEquals("Step 2 thought", handoffNodes[1].activeVariant.trailingThinking, "Synthesized assistant node must preserve trailing thinking")

        // 10. Verify Reactive Context-Length Overflow Detection & Auto-Compaction
        assertTrue(AiProviderEngine.isContextLengthExceededError(RuntimeException("HTTP 400: context_length_exceeded - maximum context length is 128000")))
        assertTrue(AiProviderEngine.isContextLengthExceededError(RuntimeException("HTTP 400: prompt is too long: 205000 tokens > 200000 maximum")))
        val sixTurnHistory = (1..6).map { i ->
            MessageNode(
                id = "t-$i",
                role = if (i % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                variants = listOf(MessageVariant(id = "v-$i", content = "Turn $i content"))
            )
        }
        val compactedOverflow = AiProviderEngine.compactHistoryNodesOnOverflow(sixTurnHistory)
        assertTrue(compactedOverflow.first().activeVariant.content.contains("Auto-Compacted Earlier Conversation Continuity Digest"))
        assertEquals(4, compactedOverflow.count { it.includedInContext }, "Digest node + 3 newest turns must remain active after compacting 3 oldest of 6 turns")

        // 11. Verify LCS Unified Line Diff Engine (Green ADDED / Red REMOVED / CONTEXT) & Official Lab SVG Slug Resolver
        val diffLines = computeUnifiedLineDiff(
            oldText = "fun hello() {\n    println(\"old\")\n}",
            newText = "fun hello() {\n    println(\"new1\")\n    println(\"new2\")\n}"
        )
        assertEquals(1, diffLines.count { it.kind == DiffLineKind.REMOVED && it.text.contains("old") })
        assertEquals(2, diffLines.count { it.kind == DiffLineKind.ADDED && it.text.contains("new") })
        assertEquals(2, diffLines.count { it.kind == DiffLineKind.CONTEXT })

        assertEquals("gemini", resolveLabSlugForModelOrProvider("gemini-4-argon"))
        assertEquals("openai", resolveLabSlugForModelOrProvider("gpt-5-pro"))
        assertEquals("openai", resolveLabSlugForModelOrProvider("o3-mini"))
        assertEquals("claude", resolveLabSlugForModelOrProvider("anthropic/claude-sonnet-4"))
        assertEquals("deepseek", resolveLabSlugForModelOrProvider("deepseek-ai/DeepSeek-R1"))
        assertEquals("grok", resolveLabSlugForModelOrProvider("x-ai/grok-4"))
        assertEquals("qwen", resolveLabSlugForModelOrProvider("Qwen/Qwen3-235B"))
        assertEquals("doubao", resolveLabSlugForModelOrProvider("seedream-4-0"))

        // 12. Verify Smart Duration Formatter (50.5s -> 20m21.2s -> 2h15m -> 1d4h15m)
        assertEquals("50.5s", formatDurationFromMs(50_500L))
        assertEquals("20m21.2s", formatDurationFromMs(1_221_200L))
        assertEquals("2h15m", formatDurationFromMs(8_145_000L))
        assertEquals("1d4h15m", formatDurationFromMs(101_700_000L))

        // 13. Verify SVGO Arc-Flag Normalization keeps all 18 official Lab SVG Icons strictly inside 24x24 viewBox
        for ((slug, pathList) in BUILTIN_LAB_SVG_PATHS) {
            assertTrue(pathList.isNotEmpty(), "Lab '$slug' must have at least one SVG path")
            for (dStr in pathList) {
                val parsed = parseSvgPathNormalized(dStr)
                val bounds = parsed.getBounds()
                assertTrue(
                    bounds.left >= -1.5f && bounds.top >= -1.5f && bounds.right <= 25.5f && bounds.bottom <= 25.5f,
                    "Lab '$slug' SVG bounds $bounds overflowed 24x24 viewBox!"
                )
            }
        }

        // 14. Verify Live Single-Thought Peek Extractor
        val multiThoughtVar = MessageVariant(
            id = "v-peek",
            content = "",
            thinkingSummary = "**Crafting Some Comedy**\nOkay, so I'm trying to come up with something funny.\n\n**Refining Humorous Angles**\nI'm exploring genuinely funny ideas, rejecting clichés and focusing on fresh angles."
        )
        val (peekHeading, peekText) = extractLatestThoughtPeek(multiThoughtVar)
        assertEquals("Refining Humorous Angles", peekHeading)
        assertTrue(peekText.startsWith("I'm exploring genuinely funny ideas"))

        // 15. Verify Balanced-Parenthesis Markdown Link Parser & HTML-to-Markdown Converter
        val wikiLink = "[Kotlin](https://en.wikipedia.org/wiki/Kotlin_(programming_language)) is great."
        val openParenIdx = wikiLink.indexOf('(')
        val closeParenIdx = findClosingMarkdownLinkParen(wikiLink, openParenIdx)
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", wikiLink.substring(openParenIdx + 1, closeParenIdx))

        val htmlDoc = "<html><head><script>alert(1)</script></head><body><h1>Title</h1><p>Hello <a href=\"https://example.com\">World</a> &amp; Friends!</p></body></html>"
        val cleanMd = WorkspaceHarness.convertHtmlToReadableText(htmlDoc, "text/html")
        assertTrue(cleanMd.contains("# Title"))
        assertTrue(cleanMd.contains("[World](https://example.com) & Friends!"))
        assertTrue(!cleanMd.contains("alert(1)"))

        // 16. Verify 2026 Harness Tools: calc_eval, fs_multi_edit, fs_glob, fs_move, fs_delete
        val mathRec = WorkspaceHarness.executeToolCall("", WorkspaceMode.OFF, "c_math", "calc_eval", """{"expression":"(2^10 + sqrt(144)) / 2"}""")
        assertTrue(!mathRec.isError, "calc_eval failed: ${mathRec.resultText}")
        assertTrue(mathRec.resultText.endsWith("= 518"), "Expected 518, got: ${mathRec.resultText}")

        val tempWs = "${PlatformBridge.getAppStorageDirectoryPath()}/test-2026-tools-${PlatformBridge.currentTimeMillis()}"
        WorkspaceHarness.executeToolCall(tempWs, WorkspaceMode.READ_WRITE, "c_w1", "fs_write", """{"path":"src/App.kt","content":"val a = 1\nval b = 2\nval c = 3"}""")
        val multiEditRec = WorkspaceHarness.executeToolCall(
            tempWs,
            WorkspaceMode.READ_WRITE,
            "c_me",
            "fs_multi_edit",
            """{"path":"src/App.kt","edits":[{"old_text":"val a = 1","new_text":"val a = 10"},{"old_text":"val c = 3","new_text":"val c = 30"}]}"""
        )
        assertTrue(!multiEditRec.isError, "fs_multi_edit failed: ${multiEditRec.resultText}")
        val readAfterMulti = WorkspaceHarness.executeToolCall(tempWs, WorkspaceMode.READ_ONLY, "c_r1", "fs_read", """{"path":"src/App.kt"}""")
        assertTrue(readAfterMulti.resultText.contains("val a = 10") && readAfterMulti.resultText.contains("val c = 30"))

        val globRec = WorkspaceHarness.executeToolCall(tempWs, WorkspaceMode.READ_ONLY, "c_glob", "fs_glob", """{"pattern":"**/*.kt"}""")
        assertTrue(globRec.resultText.contains("src/App.kt"), "fs_glob should find src/App.kt: ${globRec.resultText}")

        val moveRec = WorkspaceHarness.executeToolCall(tempWs, WorkspaceMode.READ_WRITE, "c_mv", "fs_move", """{"source_path":"src/App.kt","dest_path":"src/MainApp.kt"}""")
        assertTrue(!moveRec.isError, "fs_move failed: ${moveRec.resultText}")
        val delRec = WorkspaceHarness.executeToolCall(tempWs, WorkspaceMode.READ_WRITE, "c_del", "fs_delete", """{"path":"src/MainApp.kt"}""")
        assertTrue(!delRec.isError, "fs_delete failed: ${delRec.resultText}")

        // 17. Verify Foolproof Zero-Answer Auto-Continuation History Builder (Stage 1 & Stage 2)
        val baseNodes = listOf(
            MessageNode(id = "u-1", role = MessageRole.USER, variants = listOf(MessageVariant(id = "vu-1", content = "Explain quantum tunneling.")))
        )
        val (nudgeNodes1, nudgePrefix1) = AiProviderEngine.buildEffectiveHistoryAndPrefix(
            baseHistoryNodes = baseNodes,
            continuePrefix = "",
            thinkingText = "I should explain wavefunctions and barrier penetration.",
            signatureText = "sig_quantum_123",
            executedTools = emptyList(),
            generatedMedia = emptyList(),
            protocol = ProviderProtocol.GEMINI_INTERACTIONS,
            model = "gemini-2.5-pro",
            passTag = "test-nudge-1",
            forceVisibleThoughtSummary = false
        )
        assertEquals("", nudgePrefix1)
        assertEquals(3, nudgeNodes1.size)
        assertTrue(nudgeNodes1.last().activeVariant.content.contains("write the complete final response to the user now"))

        val (nudgeNodes2, _) = AiProviderEngine.buildEffectiveHistoryAndPrefix(
            baseHistoryNodes = baseNodes,
            continuePrefix = "",
            thinkingText = "I should explain wavefunctions and barrier penetration.",
            signatureText = "sig_quantum_123",
            executedTools = emptyList(),
            generatedMedia = emptyList(),
            protocol = ProviderProtocol.GEMINI_INTERACTIONS,
            model = "gemini-2.5-pro",
            passTag = "test-nudge-2",
            forceVisibleThoughtSummary = true
        )
        assertTrue(nudgeNodes2[1].activeVariant.content.contains("[Completed internal reasoning:"))

        // 18. Verify Full-Spec Hierarchical SVG Scene-Graph Parser (gradients, <g transform>, <circle>, <rect>, <polygon>, <line>, <path>, named colors)
        val pelicanBicycleSvg = """
            <svg viewBox="0 0 800 600" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="sky" x1="0%" y1="0%" x2="0%" y2="100%">
                  <stop offset="0%" stop-color="skyblue"/>
                  <stop offset="100%" stop-color="#E0F7FA"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="800" height="600" fill="url(#sky)"/>
              <g transform="translate(200, 400)">
                <circle cx="0" cy="0" r="60" fill="none" stroke="#333333" stroke-width="8"/>
                <circle cx="300" cy="0" r="60" fill="none" stroke="rgb(51, 51, 51)" stroke-width="8"/>
                <line x1="0" y1="0" x2="150" y2="-120" stroke="crimson" stroke-width="6"/>
                <polygon points="150,-120 300,0 120,0" fill="none" stroke="crimson" stroke-width="6"/>
              </g>
              <g transform="translate(320, 220) rotate(-10)">
                <ellipse cx="0" cy="0" rx="55" ry="35" fill="white" stroke="#222" stroke-width="3"/>
                <path d="M 30 -40 Q 110 -50 130 -20 Q 90 25 30 -10 Z" fill="gold" stroke="darkorange" stroke-width="3"/>
              </g>
            </svg>
        """.trimIndent()
        val svgDoc = parseSvgSceneDocument(pelicanBicycleSvg)
        assertTrue(svgDoc != null, "SVG document must parse")
        assertEquals(800f, svgDoc.width)
        assertEquals(600f, svgDoc.height)
        assertTrue(svgDoc.gradients.containsKey("sky"), "Must parse <linearGradient id='sky'>")
        assertEquals(7, svgDoc.drawItems.size, "Must parse all 7 shape primitives (<rect>, 2 <circle>, <line>, <polygon>, <ellipse>, <path>)")
        assertTrue(parseSvgColorString("skyblue") != null)
        assertTrue(parseSvgColorString("rgb(255, 128, 0)") != null)

        // 19. Verify Line-Grounded Web Source Citation Binding (`bindSourcesToMessageLines`)
        val sampleReply = """
            Kotlin 2.4.21 is the current stable release on Maven Central.
            Compose Multiplatform 1.12.2 updates Skia to Milestone 150 and adds Window API v2.
            For OpenAI Responses API, use {"type": "web_search"} for native grounding.
        """.trimIndent()
        val rawCitations = listOf(
            AiProviderEngine.RawWebCitation(
                url = "https://kotlinlang.org/docs/releases.html",
                title = "Kotlin Release Process & 2.4.21",
                groundedText = "Kotlin 2.4.21 is the current stable release"
            ),
            AiProviderEngine.RawWebCitation(
                url = "https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/",
                title = "Compose Multiplatform 1.12.2 Skia Milestone 150"
            ),
            AiProviderEngine.RawWebCitation(
                url = "https://developers.openai.com/api/docs/guides/tools-web-search",
                title = "OpenAI Responses API Web Search Tool"
            )
        )
        val boundSources = AiProviderEngine.bindSourcesToMessageLines(sampleReply, rawCitations)
        assertEquals(3, boundSources.size)
        assertEquals(listOf(1), boundSources[0].groundedLineNumbers)
        assertTrue(boundSources[0].groundedLines.first().contains("Kotlin 2.4.21"))
        assertEquals("kotlinlang.org", boundSources[0].domain)
        assertTrue(boundSources[1].groundedLineNumbers.contains(2))
        assertTrue(boundSources[1].groundedLines.first().contains("Compose Multiplatform 1.12.2"))
        assertTrue(boundSources[2].groundedLineNumbers.contains(3))
        assertTrue(boundSources[2].groundedLines.first().contains("OpenAI Responses API"))

        // 20. Verify Dynamic Modality Discovery from `/v1/models` JSON (Zero Hardcoded Model Names)
        val modelsJson = """
            {
              "data": [
                {"id": "future-omni-chat-2027", "output_modalities": ["text", "image"]},
                {"id": "future-studio-painter-v9", "type": "text-to-image"},
                {"id": "future-cinema-director-x1", "architecture": {"modality": "text->video"}}
              ]
            }
        """.trimIndent()
        val parsedIds = AiProviderEngine.parseModelsListJson(modelsJson)
        assertEquals(3, parsedIds.size)
        val dummyProf = ProviderProfile(id = "p1", name = "Test", protocol = ProviderProtocol.OPENAI_RESPONSES)
        assertEquals(listOf("text", "image"), AiProviderEngine.resolveKnownOutputModalities(dummyProf, "future-omni-chat-2027"))
        assertEquals(listOf("image"), AiProviderEngine.resolveKnownOutputModalities(dummyProf, "future-studio-painter-v9"))
        assertEquals(listOf("video"), AiProviderEngine.resolveKnownOutputModalities(dummyProf, "future-cinema-director-x1"))
        assertEquals("image", AiProviderEngine.detectMediaEndpointRedirectFromError(RuntimeException("HTTP 400: This model only supports v1/images/generations")))
        assertEquals("video", AiProviderEngine.detectMediaEndpointRedirectFromError(RuntimeException("HTTP 400: Use /api/v3/contents/generations/tasks for video generation")))

        // 21. Verify Right Sidebar Vault Extraction (`extractSessionVaultItems` — AI Generated, User Uploaded, Web Sources)
        val vaultSession = ChatSession(
            id = "sess-vault",
            title = "Vault Test",
            createdAt = 0L,
            updatedAt = 0L,
            profileId = "p1",
            messages = listOf(
                MessageNode(
                    id = "u1",
                    role = MessageRole.USER,
                    variants = listOf(
                        MessageVariant(
                            id = "vu1",
                            content = "Here is the spec.",
                            attachments = listOf(
                                PromptAttachment(
                                    id = "att1",
                                    fileName = "spec.pdf",
                                    mimeType = "application/pdf",
                                    localFilePath = "/tmp/spec.pdf"
                                )
                            )
                        )
                    )
                ),
                MessageNode(
                    id = "a1",
                    role = MessageRole.ASSISTANT,
                    variants = listOf(
                        MessageVariant(
                            id = "va1",
                            content = "I generated the module and searched the docs.",
                            toolCalls = listOf(
                                ToolCallRecord(
                                    id = "tc-write",
                                    name = "fs_write",
                                    argumentsJson = """{"path":"src/Engine.kt","content":"class Engine {}\n"}""",
                                    resultText = "Wrote file"
                                )
                            ),
                            mediaItems = listOf(
                                GeneratedMediaItem(
                                    id = "m1",
                                    kind = MediaKind.IMAGE,
                                    localFilePath = "/tmp/diagram.png",
                                    aspectRatio = "16:9"
                                )
                            ),
                            webSources = boundSources
                        )
                    )
                )
            )
        )
        val (aiVaultItems, uploadedVaultItems, sourceVaultItems) = extractSessionVaultItems(vaultSession)
        assertEquals(2, aiVaultItems.size, "Must extract both AI workspace file write and AI generated image")
        assertTrue(aiVaultItems[0] is AiGeneratedChatArtifact.WorkspaceFileArtifact)
        assertTrue(aiVaultItems[1] is AiGeneratedChatArtifact.MediaArtifact)
        assertEquals(1, uploadedVaultItems.size, "Must extract user uploaded PDF")
        assertEquals("spec.pdf", uploadedVaultItems[0].attachment.fileName)
        assertEquals(3, sourceVaultItems.size, "Must extract all 3 grounded web sources")

        // 22. Verify Unified `o200k_base` Tokenizer & Context Window Breakdown Sum Invariant
        val testAsst = AssistantAgent(id = "a1", name = "Scholar", systemPrompt = "You are a precise research assistant.")
        val breakdown = O200kTokenizer.computeContextWindowBreakdown(
            session = vaultSession.copy(systemPrompt = "Always cite line numbers.", storySummary = "Prior chapter summary."),
            assistant = testAsst,
            profile = dummyProf.copy(contextWindowTokens = 128_000, enableWebSearch = true),
            draftPrompt = "Drafting next question about Kotlin 2.4.21",
            draftPrefill = "",
            pendingAttachments = emptyList(),
            goalModeActive = false
        )
        assertTrue(breakdown.systemAndPersonaTokens > 0)
        assertTrue(breakdown.lorebookAndMemoryTokens > 0)
        assertTrue(breakdown.workspaceAndToolsTokens > 0)
        assertEquals(1024, breakdown.uploadedFilesTokens, "PDF attachment must count 1024 document tokens")
        assertEquals(1, breakdown.uploadedFilesCount)
        assertEquals(2, breakdown.activeTurnsCount)
        assertTrue(breakdown.chatHistoryTokens > 0)
        assertTrue(breakdown.composerDraftTokens > 0)
        assertEquals(
            breakdown.systemAndPersonaTokens +
                breakdown.lorebookAndMemoryTokens +
                breakdown.workspaceAndToolsTokens +
                breakdown.uploadedFilesTokens +
                breakdown.chatHistoryTokens +
                breakdown.composerDraftTokens,
            breakdown.totalUsedTokens,
            "totalUsedTokens in Context Ring must equal exact sum of all 6 breakdown categories"
        )
        assertEquals(128_000 - breakdown.totalUsedTokens, breakdown.freeSpaceTokens)

        // 23. Verify Free Multi-Engine `web_search` URL Unwrapping & Multi-Result Citation Extraction
        val ddgRedirect = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Freleases.html&rut=abc"
        assertEquals("https://kotlinlang.org/docs/releases.html", WorkspaceHarness.unwrapDuckDuckGoRedirectUrl(ddgRedirect))
        val webSearchToolRecord = ToolCallRecord(
            id = "call_ws_1",
            name = "web_search",
            argumentsJson = """{"query":"kotlin 2.4.21 compose 1.12.2"}""",
            resultText = """
                ### Web Search Results for "kotlin 2.4.21 compose 1.12.2"
                1. **[Kotlin Release Process](https://kotlinlang.org/docs/releases.html)**
                   URL: https://kotlinlang.org/docs/releases.html
                   Snippet: Kotlin 2.4.21 is the latest stable release.
                2. **[Compose Multiplatform 1.12.2](https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/)**
                   URL: https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/
                   Snippet: Compose Multiplatform 1.12.2 updates Skia to Milestone 150.
            """.trimIndent()
        )
        val extractedSearchCits = AiProviderEngine.extractCitationsFromToolCall(webSearchToolRecord)
        assertEquals(2, extractedSearchCits.size, "Must extract all search results from web_search tool output")
        assertEquals("https://kotlinlang.org/docs/releases.html", extractedSearchCits[0].url)
        assertEquals("Kotlin Release Process", extractedSearchCits[0].title)
        assertTrue(extractedSearchCits[0].snippet.contains("2.4.21"))
    }
    @Test
    fun testShellExecLargeOutputAndStdinEofNoDeadlock() {
        val tempRoot = "${PlatformBridge.getAppStorageDirectoryPath()}/test-shell-${PlatformBridge.currentTimeMillis()}"
        // Ensure workspace directory exists
        WorkspaceHarness.executeToolCall(
            workspaceRoot = tempRoot,
            mode = WorkspaceMode.READ_WRITE,
            callId = "call_init",
            toolName = "fs_write",
            argumentsJson = """{"path":"marker.txt","content":"ok"}"""
        )
        try {
            // Emit >80 KB of stdout lines (>20x the 4 KB Windows pipe buffer) to prove concurrent pipe draining never deadlocks
            val isWin = !PlatformBridge.isAndroid && PlatformBridge.platformName.lowercase().contains("win")
            val cmd = if (isWin) {
                "1..2200 | ForEach-Object { \"Line_\" + ${'$'}_ + \"_0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\" }"
            } else {
                "seq 1 2200 | while read i; do echo \"Line_${'$'}{i}_0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\"; done"
            }
            val res = PlatformBridge.workspaceShellExec(tempRoot, cmd, timeoutSeconds = 15)
            assertTrue(res.isSuccess, "workspaceShellExec must not deadlock on >64 KB stdout: ${res.exceptionOrNull()?.message}")
            val out = res.getOrThrow()
            assertTrue(out.startsWith("Exit Code: 0"), "Expected Exit Code: 0, got: ${out.take(120)}")
            assertTrue(out.contains("Line_1_"), "Output should contain initial lines")
        } finally {
            WorkspaceHarness.executeToolCall(
                workspaceRoot = tempRoot,
                mode = WorkspaceMode.READ_WRITE,
                callId = "call_clean",
                toolName = "fs_write",
                argumentsJson = """{"path":"marker.txt","content":""}"""
            )
        }
    }
}
