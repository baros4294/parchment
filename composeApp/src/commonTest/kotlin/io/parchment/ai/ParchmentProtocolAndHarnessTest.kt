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
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.appendSignatureChunkSafe
import io.parchment.ai.network.applyCrossProviderReasoningHandoff
import io.parchment.ai.network.bindSourcesToMessageLines
import io.parchment.ai.network.buildAnthropicBaseMessages
import io.parchment.ai.network.buildEffectiveHistoryAndPrefix
import io.parchment.ai.network.buildGeminiGenerateContentContents
import io.parchment.ai.network.buildGeminiInteractionsTurnsArray
import io.parchment.ai.network.buildOpenAiChatCompletionsMessages
import io.parchment.ai.network.buildOpenAiResponsesInputArray
import io.parchment.ai.network.compactHistoryNodesOnOverflow
import io.parchment.ai.network.detectMediaEndpointRedirectFromError
import io.parchment.ai.network.extractCitationsFromToolCall
import io.parchment.ai.network.extractCleanApiErrorSummary
import io.parchment.ai.network.isContextLengthExceededError
import io.parchment.ai.network.isGeminiServerSideToolSignature
import io.parchment.ai.network.isTransientOrProxyTimeoutError
import io.parchment.ai.network.parseModelsListJson
import io.parchment.ai.network.resolveKnownOutputModalities
import io.parchment.ai.network.resolveModelModalityProfile
import io.parchment.ai.network.sanitizeContextNodes
import io.parchment.ai.network.sanitizeGeminiThoughtSignature
import io.parchment.ai.network.shouldFallbackToLegacyProtocol
import io.parchment.ai.network.stripInlineErrorNotices
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
import io.parchment.ai.ui.BUILTIN_SLASH_COMMANDS
import io.parchment.ai.ui.KissIcon
import io.parchment.ai.ui.SettingsCategory
import io.parchment.ai.ui.buildExportFileNameForChat
import io.parchment.ai.ui.decodeChatSessionsFromRawJson
import io.parchment.ai.ui.extractSessionVaultItems
import io.parchment.ai.ui.parseKissLabelIcons
import io.parchment.ai.ui.resolveToolKissIcon
import io.parchment.ai.ui.saveSingleChatSessionToDisk
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            goalModeActive = true,
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
            goalModeActive = true,
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
        val compactedOld = WorkspaceHarness.compactStaleToolOutputText(1, 30, "fs_read", longOutput)
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

        // 7. Verify 10x 60s patient auto-retry configuration, error classification, and tool intent telemetry
        assertEquals(10, AiProviderEngine.MAX_PRE_TOKEN_RETRIES, "Patient retry must allow 10 attempts")
        assertEquals(60, AiProviderEngine.RETRY_WAIT_SECONDS, "Patient retry must wait 60s per attempt")
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

        // 9. Falling back from the primary to the legacy protocol, and handing a turn over mid-reply without loss
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

        // 11. Line diffs (added, removed, context) and AI lab logo lookup
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

        // 13. Arc-flag normalization keeps all 18 built-in lab logos inside their 24x24 viewBox
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
        val mathRec = WorkspaceHarness.executeToolCall("", WorkspaceMode.OFF, "c_math", "calc_eval", """{"expression":"(2^10 + sqrt(144)) / 2"}""", goalModeActive = true)
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

        // 17. History for continuing a reply that came back empty (both stages)
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

        // 20. Modalities come from the `/v1/models` response, not from hardcoded model names
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

        // 24. Verify Complete 17-Tool Harness Registry & Parameter Signatures
        val allHarnessTools = WorkspaceHarness.allTools()
        assertEquals(21, allHarnessTools.size, "WorkspaceHarness must expose all 21 built-in tools")

        // 25. Verify Split Storage Architecture (parchment-settings.json + per-chat files in chats/) & Standalone Chat JSON Import/Export
        val chatA = vaultSession.copy(id = "sess-split-a", title = "Architecture Notes", updatedAt = 1000L)
        val chatB = vaultSession.copy(id = "sess-split-b", title = "Mobile Migration Test", updatedAt = 2000L)
        saveSingleChatSessionToDisk(chatA)
        saveSingleChatSessionToDisk(chatB)
        val loadedChatsFromDisk = PlatformBridge.loadAllChatSessionJsons().flatMap { decodeChatSessionsFromRawJson(it) }
        assertTrue(loadedChatsFromDisk.any { it.id == "sess-split-a" && it.title == "Architecture Notes" })
        assertTrue(loadedChatsFromDisk.any { it.id == "sess-split-b" && it.title == "Mobile Migration Test" })
        val exportedSingleJson = CardAndPromptCodec.prettyJson.encodeToString(ChatSession.serializer(), chatB)
        val decodedImported = decodeChatSessionsFromRawJson(exportedSingleJson)
        assertEquals(1, decodedImported.size)
        assertEquals("Mobile Migration Test", decodedImported.first().title)
        assertTrue(buildExportFileNameForChat(chatB).endsWith(".chat.json"))
        PlatformBridge.deleteChatSessionFile("sess-split-a")
        PlatformBridge.deleteChatSessionFile("sess-split-b")

        // 26. Verify Deep Package Tree Traversal (depth >= 10) for workspaceList, workspaceGlob, and workspaceSearch
        val deepRoot = "${PlatformBridge.getAppStorageDirectoryPath()}/test-deep-ws-${PlatformBridge.currentTimeMillis()}"
        val deepPath = "composeApp/src/commonMain/kotlin/io/parchment/ai/harness/DeepModule.kt"
        val deepWrite = WorkspaceHarness.executeToolCall(
            workspaceRoot = deepRoot,
            mode = WorkspaceMode.READ_WRITE,
            callId = "call_deep_w",
            toolName = "fs_write",
            argumentsJson = """{"path":"$deepPath","content":"object DeepHarnessMarker { val version = 36 }"}"""
        )
        assertFalse(deepWrite.isError, "Deep fs_write should succeed")
        val deepSearch = PlatformBridge.workspaceSearch(deepRoot, "DeepHarnessMarker", ".kt").getOrThrow()
        assertTrue(deepSearch.contains("DeepModule.kt"), "workspaceSearch must find files at depth 9+: $deepSearch")
        val deepGlob = PlatformBridge.workspaceGlob(deepRoot, "**/*.kt", 50).getOrThrow()
        assertTrue(deepGlob.contains("DeepModule.kt"), "workspaceGlob must find files at depth 9+: $deepGlob")
        val deepList = PlatformBridge.workspaceList(deepRoot, "", recursive = true).getOrThrow()
        assertTrue(deepList.contains("DeepModule.kt"), "workspaceList(recursive=true) must find files at depth 9+: $deepList")
        PlatformBridge.workspaceDelete(deepRoot, "composeApp")
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
            // The limit only catches a deadlock; a cold CI runner can spend 10+ seconds just starting PowerShell.
            val res = PlatformBridge.workspaceShellExec(tempRoot, cmd, timeoutSeconds = 60)
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

    @Test
    fun testCompleteKissIconCoverageAndEmojiElimination() {
        // 1. All 18 Assistant Persona icon presets must map to a dedicated KissIcon with zero remaining text
        val assistantPresets = listOf(
            "💬", "🤖", "🧪", "✏️", "📚", "⚡",
            "🎭", "🧭", "🧠", "🌐", "🎯", "💻",
            "🔧", "✦", "🛡", "🎨", "👑", "🔥"
        )
        for (preset in assistantPresets) {
            val parsed = parseKissLabelIcons(preset)
            assertNotNull(parsed.leadingIcon, "Assistant icon preset '$preset' must resolve to a KissIcon")
            assertEquals("", parsed.cleanText, "Assistant icon preset '$preset' must strip the emoji completely")
        }

        // 2. All Settings categories must resolve to a KissIcon
        for (cat in SettingsCategory.entries) {
            val parsed = parseKissLabelIcons(cat.icon)
            assertNotNull(parsed.leadingIcon, "SettingsCategory.${cat.name} icon '${cat.icon}' must resolve to a KissIcon")
        }

        // 3. All built-in Slash Commands must resolve to a leading KissIcon
        for (cmd in BUILTIN_SLASH_COMMANDS) {
            val parsed = parseKissLabelIcons(cmd.label)
            assertNotNull(parsed.leadingIcon, "Slash command '${cmd.label}' must resolve to a leading KissIcon")
            assertEquals(cmd.command, parsed.cleanText)
        }

        // 4. All 17 built-in Workspace Harness tools must resolve to specific KissIcons
        for (tool in WorkspaceHarness.allTools()) {
            val icon = resolveToolKissIcon(tool.name)
            assertNotNull(icon)
        }
        assertEquals(KissIcon.CALCULATOR, resolveToolKissIcon("calc_eval"))
        assertEquals(KissIcon.DICE, resolveToolKissIcon("dice_roll"))

        // 5. Key UI chips, buttons, and viewer controls must strip emojis and resolve to KissIcons
        val shellChip = parseKissLabelIcons("🐚 Shell: ON")
        assertEquals(KissIcon.TERMINAL, shellChip.leadingIcon)
        assertEquals("Shell: ON", shellChip.cleanText)

        val toolsChip = parseKissLabelIcons("🔧 17/17 Tools ▾")
        assertEquals(KissIcon.WRENCH, toolsChip.leadingIcon)
        assertEquals("17/17 Tools", toolsChip.cleanText)
        assertEquals(KissIcon.CHEVRON_DOWN, toolsChip.trailingIcon)

        val tabsBtn = parseKissLabelIcons("⊞ 4 Tabs")
        assertEquals(KissIcon.LAYOUT_GRID, tabsBtn.leadingIcon)
        assertEquals("4 Tabs", tabsBtn.cleanText)

        val zoomInBtn = parseKissLabelIcons("🔍+")
        assertEquals(KissIcon.ZOOM_IN, zoomInBtn.leadingIcon)
        assertEquals("", zoomInBtn.cleanText)

        val zoomOutBtn = parseKissLabelIcons("🔍−")
        assertEquals(KissIcon.ZOOM_OUT, zoomOutBtn.leadingIcon)
        assertEquals("", zoomOutBtn.cleanText)

        val extLinkBtn = parseKissLabelIcons("↗")
        assertEquals(KissIcon.EXTERNAL_LINK, extLinkBtn.leadingIcon)
        assertEquals("", extLinkBtn.cleanText)

        val atBtn = parseKissLabelIcons("@")
        assertEquals(KissIcon.AT_SIGN, atBtn.leadingIcon)
        assertEquals("", atBtn.cleanText)
    }

    @Test
    fun testExtractCleanApiErrorSummaryAndHttp400GenerateNonRetry() {
        val raw400 = """Gemini Interactions HTTP 400: {"error":{"code":"invalid_request","message":"The model could not generate a valid response for this interaction."}}"""
        val cleaned = AiProviderEngine.extractCleanApiErrorSummary(raw400)
        assertEquals(
            "Gemini Interactions HTTP 400 (invalid_request): The model could not generate a valid response for this interaction.",
            cleaned
        )

        // Verify HTTP 400 containing the word "generate" (which has substring "rate") is NOT misclassified as a transient rate limit
        val err = IllegalStateException(raw400)
        assertTrue(!AiProviderEngine.isTransientOrProxyTimeoutError(err))

        // Verify genuine HTTP 400 rate limit / resource_exhausted IS classified as retryable
        val rateLimit400 = IllegalStateException("""HTTP 400: {"error":{"code":"resource_exhausted","message":"Rate limit exceeded, please try again"}}""")
        assertTrue(AiProviderEngine.isTransientOrProxyTimeoutError(rateLimit400))
    }

    @Test
    fun testContextCompactionPlanMutesOlderTurnsAndCompactsHeavyTools() {
        val bigToolOutput1 = "Line of file content ".repeat(150) // ~3150 chars
        val bigToolOutput2 = "Directory listing entry ".repeat(120) // ~2880 chars
        val smallRecentOutput = "Exit Code: 0\nBuild succeeded"

        val liveTools = listOf(
            ToolCallRecord(id = "tc-1", name = "fs_read", argumentsJson = """{"path":"A.kt"}""", resultText = bigToolOutput1),
            ToolCallRecord(id = "tc-2", name = "fs_search", argumentsJson = """{"query":"foo"}""", resultText = bigToolOutput2),
            ToolCallRecord(id = "tc-3", name = "shell_exec", argumentsJson = """{"command":"echo ok"}""", resultText = smallRecentOutput)
        )

        val nodes = (1..5).map { idx ->
            MessageNode(
                id = "node-$idx",
                role = if (idx % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                variants = listOf(
                    MessageVariant(
                        id = "var-$idx",
                        content = "Turn #$idx content with important project decisions.",
                        providerInteractionId = "inter-$idx"
                    )
                )
            )
        }

        assertTrue(O200kTokenizer.hasCompactableContext(nodes, keepRecentTurnsCount = 2, liveTargetTools = liveTools))

        val plan = O200kTokenizer.prepareCompactionPlan(
            existingStorySummary = "• Initial project setup completed.",
            historyNodes = nodes,
            keepRecentTurnsCount = 2,
            liveTargetTools = liveTools,
            liveTargetThinking = "Reasoning step ".repeat(300)
        )

        assertTrue(plan.didCompactAnything)
        // First 3 of 5 turns should be muted, keeping the last 2 turns active
        assertEquals(setOf("node-1", "node-2", "node-3"), plan.mutedNodeIds)
        assertEquals(2, plan.compactedHistoryNodes.count { it.includedInContext })
        // Kept turns must have their stale providerInteractionId cleared so resumed calls use compacted history
        assertTrue(plan.compactedHistoryNodes.filter { it.includedInContext }.all { it.activeVariant.providerInteractionId.isEmpty() })

        // First tool call in liveTools (> 2 tools ago) must be compacted, while recent small tool call stays intact
        assertTrue(O200kTokenizer.isToolResultCompacted(plan.compactedLiveTools[0]))
        assertEquals(smallRecentOutput, plan.compactedLiveTools[2].resultText)
        assertTrue(plan.transcriptToSummarize.contains("Initial project setup completed"))
        assertTrue(plan.fallbackSummary.isNotBlank())
    }

    @Test
    fun testLiveShellExecStreamingAndProcessKill() {
        val deltas = mutableListOf<String>()
        val rec = WorkspaceHarness.executeToolCall(
            workspaceRoot = ".",
            mode = WorkspaceMode.READ_WRITE,
            callId = "call-live-shell",
            toolName = "shell_exec",
            allowShell = true,
            // A generous limit: a cold CI runner can spend 10+ seconds just starting PowerShell.
            argumentsJson = """{"command":"echo parchment_live_stream_ok","timeout_seconds":60}""",
            onOutputDelta = { partial ->
                synchronized(deltas) { deltas.add(partial) }
            }
        )
        assertTrue(!rec.isError, "Expected shell_exec to succeed: ${rec.resultText}")
        assertTrue(rec.resultText.contains("parchment_live_stream_ok"))
        assertTrue(deltas.any { it.contains("parchment_live_stream_ok") })
    }

    @Test
    fun testLiveAgentPeekSwitchesBetweenToolPeekAndThoughtPeek() {
        // 1. When a tool call is running (or latest step has no trailing thinking), Peek shows Tool Peek
        val runningToolVariant = MessageVariant(
            id = "v-tool-peek",
            content = "",
            thinkingSummary = "**Initial Analysis**\nChecking workspace files.",
            trailingThinking = "",
            toolCalls = listOf(
                ToolCallRecord(
                    id = "tc-1",
                    name = "shell_exec",
                    argumentsJson = """{"command":"./gradlew desktopTest"}""",
                    resultText = "[Running 4.2s]\n> Task :composeApp:desktopTest",
                    isRunning = true
                )
            )
        )
        val toolPeek = io.parchment.ai.ui.extractLatestAgentPeek(runningToolVariant)
        assertTrue(toolPeek.isToolPeek)
        assertTrue(toolPeek.isToolRunning)
        assertEquals("shell_exec", toolPeek.toolName)
        assertTrue(toolPeek.title.contains("Tool #1/1"))
        assertTrue(toolPeek.body.contains("./gradlew desktopTest"))
        assertTrue(toolPeek.body.contains("Task :composeApp:desktopTest"))

        // 2. When the model starts reasoning again after tool execution (`trailingThinking` is non-blank and no tool is running), Peek switches to Thought Peek
        val nextThoughtVariant = runningToolVariant.copy(
            trailingThinking = "**Evaluating Test Results**\nAll unit tests passed cleanly.",
            toolCalls = runningToolVariant.toolCalls.map { it.copy(isRunning = false) }
        )
        val thoughtPeek = io.parchment.ai.ui.extractLatestAgentPeek(nextThoughtVariant)
        assertTrue(!thoughtPeek.isToolPeek)
        assertEquals("Evaluating Test Results", thoughtPeek.title)
        assertTrue(thoughtPeek.body.contains("All unit tests passed cleanly."))
    }

    @Test
    fun testOmpFuzzyWhitespaceEditAndSizeValveCompression() {
        val sampleFile = """
            class Example {
                fun greet(name: String): String {
                    val trimmed = name.trim()
                    return "Hello, " + trimmed
                }
            }
        """.trimIndent()

        // 1. Fuzzy indentation match (model provides 2-space indent instead of 8-space indent)
        val driftedOld = """
          val trimmed = name.trim()
          return "Hello, " + trimmed
        """.trimIndent()
        val driftedNew = """
          val clean = name.trim().ifEmpty { "World" }
          return "Hi, " + clean
        """.trimIndent()

        val fuzzyRes = WorkspaceHarness.applySingleEditWithFuzzySelfHealing(
            existingLf = sampleFile,
            rawOldText = driftedOld,
            rawNewText = driftedNew,
            replaceAll = false,
            relativePath = "Example.kt"
        )
        assertTrue(fuzzyRes.usedFuzzyWhitespace)
        assertTrue(fuzzyRes.updatedLf.contains("        val clean = name.trim().ifEmpty { \"World\" }"))
        assertTrue(fuzzyRes.updatedLf.contains("        return \"Hi, \" + clean"))

        // 2. Accidental `123: ` line-number prefixes copied from `fs_read` output are stripped automatically
        val numberedOld = "2:     fun greet(name: String): String {\n3:         val trimmed = name.trim()"
        val numberedNew = "2:     fun greet(name: String): String {\n3:         val trimmed = name.trim().lowercase()"
        val linePrefixRes = WorkspaceHarness.applySingleEditWithFuzzySelfHealing(
            existingLf = sampleFile,
            rawOldText = numberedOld,
            rawNewText = numberedNew,
            replaceAll = false,
            relativePath = "Example.kt"
        )
        assertTrue(linePrefixRes.updatedLf.contains("val trimmed = name.trim().lowercase()"))
        assertTrue(!linePrefixRes.updatedLf.contains("2: "))

        // 3. Size valve compresses 300-line shell_exec output into Head + Tail bookends and strips ANSI codes
        val noisyShellOut = (1..300).joinToString("\n") { "\u001B[32mLine #$it output\u001B[0m" }
        val compressed = WorkspaceHarness.compressToolOutputWithSizeValve("shell_exec", noisyShellOut)
        assertTrue(!compressed.contains("\u001B["))
        assertTrue(compressed.contains("Line #1 output"))
        assertTrue(compressed.contains("Line #300 output"))
        assertTrue(compressed.contains("middle lines omitted by size valve"))

        // 4. PowerShell CLIXML progress noise is stripped cleanly
        val clixmlOut = "Exit Code: 0\nHello World\n#< CLIXML\n<Objs Version=\"1.1.0.1\"><Obj S=\"progress\">Preparing modules</Obj></Objs>"
        val cleanClixml = WorkspaceHarness.compressToolOutputWithSizeValve("shell_exec", clixmlOut)
        assertTrue(!cleanClixml.contains("CLIXML"))
        assertTrue(!cleanClixml.contains("<Objs"))
        assertTrue(cleanClixml.contains("Hello World"))
    }

    @Test
    fun testFastPrefixSuffixTrimmedDiffAndStorageListing() {
        // 1. 400-line file with a 2-line change in the middle computes an exact unified diff via prefix/suffix trimming
        val prefix = (1..180).joinToString("\n") { "val line$it = $it" }
        val suffix = (183..400).joinToString("\n") { "val line$it = $it" }
        val oldLarge = "$prefix\nval targetA = 1\nval targetB = 2\n$suffix"
        val newLarge = "$prefix\nval targetA = 100\nval targetB = 200\n$suffix"
        val diff = computeUnifiedLineDiff(oldLarge, newLarge)
        assertTrue(diff.any { it.kind == io.parchment.ai.ui.DiffLineKind.REMOVED && it.text.contains("val targetA = 1") })
        assertTrue(diff.any { it.kind == io.parchment.ai.ui.DiffLineKind.ADDED && it.text.contains("val targetA = 100") })
        assertTrue(diff.any { it.kind == io.parchment.ai.ui.DiffLineKind.COLLAPSED })

        // 2. Default PreferencesConfig has autoScrollToBottomOnOpen enabled
        val defaultPrefs = io.parchment.ai.model.PreferencesConfig()
        assertTrue(defaultPrefs.autoScrollToBottomOnOpen)

        // 3. Storage listing returns valid StoredAppFileItem list
        val files = io.parchment.ai.platform.PlatformBridge.listAppStorageFiles()
        assertNotNull(files)
    }

    @Test
    fun testAccurateTokPerSecIncrementalProseCacheAndLazySessionDehydration() {
        // 1. Verify tok/s calculation excludes local tool execution time and TTFT wait, and includes reasoning + output tokens
        val metrics = AiProviderEngine.computeTurnTokenMetrics(
            reportedInputTokens = 2000,
            reportedCachedTokens = 1500,
            reportedReasoningTokens = 300,
            reportedOutputTokens = 100,
            fallbackInputTokens = 2000,
            contentText = "Short final summary after tool execution.",
            thinkingText = "Detailed reasoning across multiple steps.",
            executedTools = listOf(
                ToolCallRecord(
                    id = "tc-slow",
                    name = "shell_exec",
                    argumentsJson = """{"command":"./gradlew test"}""",
                    resultText = "BUILD SUCCESSFUL",
                    durationMs = 40_000L
                )
            ),
            totalDurationMs = 45_000L,      // 45s wall clock (40s spent in shell_exec + 1s TTFT + 4s active LLM streaming)
            ttftMs = 1_000L,
            activeDecodeDurationMs = 4_000L, // 4.0s actual LLM token streaming burst
            totalToolExecutionMs = 40_000L
        )
        // 400 generated tokens (300 reasoning + 100 output) over 4.0s active decode = 100.0 tok/s (not 0.3 tok/s!)
        assertEquals(100.0, metrics.tokensPerSec)
        assertEquals(300L, metrics.reasoningTokens)
        assertEquals(100L, metrics.outputTokens)

        // 2. Verify IncrementalProseBlockCache matches full parseProseBlocks bit-for-bit during streaming
        val sampleMarkdown = buildString {
            appendLine("# Chapter 1: The Architecture")
            appendLine()
            appendLine("First paragraph with **bold** and *italic* prose that exceeds the prefix threshold when combined with subsequent blocks.")
            appendLine()
            appendLine("```kotlin")
            appendLine("fun computeSpeed(tokens: Int, ms: Long): Double = (tokens * 1000.0) / ms")
            appendLine("```")
            appendLine()
            appendLine("> A blockquote noting that incremental prefix caching turns O(N) into O(1).")
            appendLine()
            repeat(8) { idx ->
                appendLine("Paragraph #$idx with enough literary prose content to cross the 512-character stable prefix boundary cleanly during 120Hz VSync streaming.")
                appendLine()
            }
        }
        val cache = io.parchment.ai.ui.IncrementalProseBlockCache()
        var step = 17
        while (step <= sampleMarkdown.length) {
            val sub = sampleMarkdown.substring(0, step)
            val expected = io.parchment.ai.ui.parseProseBlocks(sub, enableMath = true)
            val actual = cache.parseIncremental(sub, enableMath = true)
            assertEquals(expected, actual, "IncrementalProseBlockCache mismatch at length $step")
            step += 29
        }
        assertEquals(
            io.parchment.ai.ui.parseProseBlocks(sampleMarkdown, enableMath = true),
            cache.parseIncremental(sampleMarkdown, enableMath = true)
        )

        // 3. Verify lazy session dehydration & on-demand disk hydration preserves message count, media paths, and full disk fidelity
        val testSessId = "sess-dehydrate-test-${PlatformBridge.currentTimeMillis()}"
        val persistedAttachmentPath = PlatformBridge.saveGeneratedMediaFile("att-photo.png", ByteArray(1500), testSessId)
        val fullSession = ChatSession(
            id = testSessId,
            title = "Heavy Session",
            createdAt = 1000L,
            updatedAt = 2000L,
            profileId = "prof-1",
            storySummary = "Long story summary...",
            messages = listOf(
                MessageNode(
                    id = "n1",
                    role = MessageRole.USER,
                    variants = listOf(
                        MessageVariant(
                            id = "v1",
                            content = "First user message with attachment",
                            attachments = listOf(
                                PromptAttachment(
                                    id = "att1",
                                    fileName = "photo.png",
                                    mimeType = "image/png",
                                    base64Data = "AAAA".repeat(500),
                                    localFilePath = persistedAttachmentPath
                                )
                            )
                        )
                    )
                ),
                MessageNode(
                    id = "n2",
                    role = MessageRole.ASSISTANT,
                    variants = listOf(
                        MessageVariant(
                            id = "v2",
                            content = "Final assistant reply with important conclusion.",
                            thinkingSummary = "Long chain of thought ".repeat(200),
                            toolCalls = listOf(
                                ToolCallRecord(id = "tc1", name = "fs_read", argumentsJson = "{}", resultText = "File contents ".repeat(200))
                            )
                        )
                    )
                )
            )
        )
        io.parchment.ai.ui.saveSingleChatSessionToDisk(fullSession)

        val stub = io.parchment.ai.ui.dehydrateChatSessionToCompactStub(fullSession)
        assertTrue(stub.isCompactStub)
        assertEquals(2, stub.messages.size, "Dehydrated stub must preserve exact message count for sidebar badges")
        assertTrue(stub.messages.first().activeVariant.content.isEmpty(), "Earlier messages in stub must be empty")
        assertTrue(stub.messages.last().activeVariant.thinkingSummary.isEmpty(), "Thinking summary must be freed in stub")
        assertTrue(stub.messages.last().activeVariant.toolCalls.isEmpty(), "Tool calls must be freed in stub")
        assertEquals(persistedAttachmentPath, stub.messages.last().activeVariant.attachments.first().localFilePath)

        // Pinning/renaming a dehydrated stub and saving to disk must NOT overwrite the full messages on disk!
        val pinnedStub = stub.copy(title = "Renamed Heavy Session", isPinned = true, updatedAt = 3000L)
        io.parchment.ai.ui.saveSingleChatSessionToDisk(pinnedStub)

        val rehydrated = io.parchment.ai.ui.hydrateSessionFromDiskIfNeeded(pinnedStub)
        assertFalse(rehydrated.isCompactStub)
        assertEquals("Renamed Heavy Session", rehydrated.title)
        assertTrue(rehydrated.isPinned)
        assertEquals(2, rehydrated.messages.size)
        assertEquals("First user message with attachment", rehydrated.messages[0].activeVariant.content)
        assertTrue(rehydrated.messages[0].activeVariant.attachments[0].base64Data.isEmpty(), "Redundant base64Data should be stripped when localFilePath exists")
        assertTrue(rehydrated.messages[1].activeVariant.thinkingSummary.contains("Long chain of thought"))
        assertEquals(1, rehydrated.messages[1].activeVariant.toolCalls.size)

        PlatformBridge.deleteChatSessionFile(testSessId)

        // 4. Verify uncapped existingStorySummary preservation (> 2400 chars) in prepareCompactionPlan
        val longExistingSummary = (1..80).joinToString("\n") { "• Milestone #$it: Completed detailed architectural verification and preserved full context without truncation." }
        assertTrue(longExistingSummary.length > 5000, "Test summary must exceed legacy 2400-char limit")
        val compactionNodes = (1..6).map { idx ->
            MessageNode(
                id = "cn-$idx",
                role = if (idx % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                variants = listOf(MessageVariant(id = "cv-$idx", content = "Turn #$idx detailed discussion."))
            )
        }
        val compactionPlan = O200kTokenizer.prepareCompactionPlan(
            existingStorySummary = longExistingSummary,
            historyNodes = compactionNodes,
            keepRecentTurnsCount = 2
        )
        assertTrue(compactionPlan.transcriptToSummarize.contains("Milestone #1:"))
        assertTrue(compactionPlan.transcriptToSummarize.contains("Milestone #80:"))
        assertTrue(compactionPlan.fallbackSummary.contains("Milestone #1:"), "Fallback summary must never slice off the beginning of existingStorySummary")
        assertTrue(compactionPlan.fallbackSummary.contains("Milestone #80:"))

        // 5. Verify split O(1) computeBaseContextWindowBreakdown + applyComposerDraftToBreakdown matches full computeContextWindowBreakdown
        val dummyAssistant = io.parchment.ai.model.AssistantAgent(id = "a-perf", name = "Perf", systemPrompt = "System prompt")
        val dummyProfile = io.parchment.ai.model.ProviderProfile(id = "p-perf", name = "OpenAI", protocol = ProviderProtocol.OPENAI_RESPONSES)
        val baseBreakdown = O200kTokenizer.computeBaseContextWindowBreakdown(
            session = fullSession,
            assistant = dummyAssistant,
            profile = dummyProfile,
            goalModeActive = true
        )
        val combinedFast = O200kTokenizer.applyComposerDraftToBreakdown(
            baseBreakdown = baseBreakdown,
            draftPrompt = "Draft message with several words",
            draftPrefill = "Sure, here is",
            pendingAttachments = emptyList()
        )
        val fullDirect = O200kTokenizer.computeContextWindowBreakdown(
            session = fullSession,
            assistant = dummyAssistant,
            profile = dummyProfile,
            draftPrompt = "Draft message with several words",
            draftPrefill = "Sure, here is",
            pendingAttachments = emptyList(),
            goalModeActive = true
        )
        assertEquals(fullDirect, combinedFast)

        // 6. Verify countLinesFast and cached parseKissLabelIcons
        assertEquals(1, io.parchment.ai.ui.countLinesFast(""))
        assertEquals(3, io.parchment.ai.ui.countLinesFast("a\nb\nc"))
        val iconA = io.parchment.ai.ui.parseKissLabelIcons("⚡ Shorten conversation")
        val iconB = io.parchment.ai.ui.parseKissLabelIcons("⚡ Shorten conversation")
        assertEquals(iconA, iconB)
        assertEquals("Shorten conversation", iconA.cleanText)
    }

    @Test
    fun testNestedMarkdownProseLatexPerChatMediaFolderAndZipBundleRoundTrip() {
        val palette = io.parchment.ai.ui.buildPaletteFromConfig(io.parchment.ai.model.ThemeConfig())

        // 1. Verify exact user screenshot case: outer *...* italic containing nested **`#48`** bold-code and `.APK` inline code
        val screenshotLine = "*(Quick status note: GitHub Actions Run **`#48`** on commit `af62c48` finished with **`SUCCESS`** across both the Android Universal `.APK` and Windows `.EXE` builds!)*"
        val styledScreenshot = io.parchment.ai.ui.styleLiteraryParagraph(
            raw = screenshotLine,
            palette = palette,
            indentChars = 0,
            enableMath = true,
            enableInlineDollarMath = true
        )
        assertEquals(
            "(Quick status note: GitHub Actions Run #48 on commit af62c48 finished with SUCCESS across both the Android Universal .APK and Windows .EXE builds!)",
            styledScreenshot.text,
            "Outer *...* italic and inner **`...`** bold-code must strip all Markdown asterisks cleanly without leaking literal '*'"
        )
        assertTrue(
            styledScreenshot.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic },
            "Outer span must be styled as Italic"
        )
        assertTrue(
            styledScreenshot.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.SemiBold },
            "Inner **...** spans must be styled as SemiBold"
        )

        // 2. Verify currency "$50 to $100" is NOT mangled as math while "$x_1 + \mathcal{O}(N)$" IS rendered as LaTeX math
        val currencyAndMath = "Cost is $50 to $100 for $\\mathcal{O}(N \\log N)$ complexity."
        val styledMath = io.parchment.ai.ui.styleLiteraryParagraph(currencyAndMath, palette, 0, enableMath = true, enableInlineDollarMath = true)
        assertTrue(styledMath.text.contains("$50 to $100"), "Currency range must remain literal")
        assertTrue(styledMath.text.contains("𝒪(N log N)"), "\\mathcal{O} must render as Unicode script 𝒪")

        // 3. Verify Per-Chat Folder Hierarchy (chats/<sessionId>/chat.json + chats/<sessionId>/media/) & .chat.zip Bundle Export/Import
        val chatId = "sess-zip-bundle-${PlatformBridge.currentTimeMillis()}"
        val samplePngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04)
        val savedMediaPath = PlatformBridge.saveGeneratedMediaFile("att-diagram.png", samplePngBytes, sessionId = chatId)
        assertTrue(
            savedMediaPath.replace('\\', '/').contains("chats/$chatId/media/att-diagram.png"),
            "Media file must be saved inside chats/<sessionId>/media/"
        )

        val sessionWithMedia = ChatSession(
            id = chatId,
            title = "Per-Chat Folder Test",
            createdAt = 1000L,
            updatedAt = 2000L,
            profileId = "prof-gemini-interactions",
            messages = listOf(
                MessageNode(
                    id = "m1",
                    role = MessageRole.USER,
                    variants = listOf(
                        MessageVariant(
                            id = "v1",
                            content = "Attached diagram",
                            attachments = listOf(
                                PromptAttachment(
                                    id = "a1",
                                    fileName = "att-diagram.png",
                                    mimeType = "image/png",
                                    localFilePath = savedMediaPath
                                )
                            )
                        )
                    )
                )
            )
        )
        io.parchment.ai.ui.saveSingleChatSessionToDisk(sessionWithMedia)
        assertEquals(1, io.parchment.ai.ui.countSessionVaultItemsFast(sessionWithMedia))

        val zipBytes = io.parchment.ai.ui.encodeChatSessionsToZipBundleBytes(listOf(sessionWithMedia))
        assertTrue(zipBytes.size > 64, "Exported .chat.zip bundle must be non-empty")

        // Delete original chat folder (including its media/) and re-import from the .chat.zip bundle!
        PlatformBridge.deleteChatSessionFile(chatId)
        assertEquals(null, PlatformBridge.loadChatSessionJsonById(chatId))

        val reimported = io.parchment.ai.ui.decodeChatSessionsFromPickedFile(
            fileName = io.parchment.ai.ui.buildExportZipFileNameForChat(sessionWithMedia),
            bytes = zipBytes
        )
        assertEquals(1, reimported.size)
        val restoredChat = reimported.first()
        assertEquals("Per-Chat Folder Test", restoredChat.title)
        val restoredMediaPath = restoredChat.messages.first().activeVariant.attachments.first().localFilePath
        val restoredBytes = PlatformBridge.readBinaryFileBytes(restoredMediaPath)
        assertNotNull(restoredBytes, "Media file extracted from .chat.zip into chats/<sessionId>/media/ must be readable")
        assertEquals(samplePngBytes.toList(), restoredBytes.toList())

        PlatformBridge.deleteChatSessionFile(chatId)

        // 4. Verify Gemini ThoughtSignature Sanitizer (splits glued Base64 protobufs & strips 08 02 server-side search signatures),
        //    Parallel + Sequential Tool Step Batching across all 5 protocols, Cross-Provider Signature Isolation,
        //    and Wall-Clock Generation Duration tok/s calculation
        // 19 bytes (% 3 == 1) -> Base64 ends with "=="
        val fnCallProtobufBytes = byteArrayOf(
            0x12, 0x11, 0x0A, 0x0F,
            0x01, 0x69, 0x14, 0x7d, 0x13,
            0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11, 0x22, 0x33, 0x44
        )
        // 22 bytes (% 3 == 1) -> Base64 ends with "=="
        val serverSearchProtobufBytes = byteArrayOf(
            0x12, 0x14, 0x0A, 0x12,
            0x08, 0x02, 0x12, 0x0E,
            0x01, 0x69, 0x14, 0x7d, 0x13,
            0x55, 0x66, 0x77, 0x44, 0x33, 0x22, 0x11, 0x00, 0x11
        )
        val cleanFnSigB64 = kotlin.io.encoding.Base64.encode(fnCallProtobufBytes)
        val serverSearchSigB64 = kotlin.io.encoding.Base64.encode(serverSearchProtobufBytes)
        val gluedCorruptedSig = serverSearchSigB64 + cleanFnSigB64 + serverSearchSigB64

        assertTrue(AiProviderEngine.isGeminiServerSideToolSignature(serverSearchSigB64), "08 02 protobuf tag must be detected as server-side tool signature")
        assertFalse(AiProviderEngine.isGeminiServerSideToolSignature(cleanFnSigB64), "Standard function_call signature must not be flagged as server-side tool")
        assertEquals(
            cleanFnSigB64,
            AiProviderEngine.sanitizeGeminiThoughtSignature(gluedCorruptedSig, forFunctionCall = true),
            "Sanitizer must split glued Base64 segments and pick the non-(08 02) function_call signature"
        )

        val sigBuilder = StringBuilder()
        AiProviderEngine.appendSignatureChunkSafe(sigBuilder, cleanFnSigB64)
        AiProviderEngine.appendSignatureChunkSafe(sigBuilder, serverSearchSigB64)
        assertEquals(cleanFnSigB64, sigBuilder.toString(), "appendSignatureChunkSafe must never glue a complete Base64 signature or overwrite a function_call sig with a server-tool sig")

        val multiBatchAssistantTurn = MessageNode(
            id = "a-multibatch",
            role = MessageRole.ASSISTANT,
            variants = listOf(
                MessageVariant(
                    id = "v-multibatch",
                    content = "Completed all parallel and sequential tool batches.",
                    thinkingSummary = "Batch 1 reasoning",
                    thinkingSignature = gluedCorruptedSig,
                    providerProtocol = ProviderProtocol.GEMINI_INTERACTIONS,
                    toolCalls = listOf(
                        ToolCallRecord(id = "call_p1", name = "fs_search", argumentsJson = """{"query":"a"}""", resultText = "res_p1", thoughtSignature = gluedCorruptedSig, precedingThinking = "Step 1 think"),
                        ToolCallRecord(id = "call_p2", name = "fs_search", argumentsJson = """{"query":"b"}""", resultText = "res_p2", thoughtSignature = "", precedingThinking = ""),
                        ToolCallRecord(id = "call_s2", name = "fs_read", argumentsJson = """{"path":"x.kt"}""", resultText = "res_s2", thoughtSignature = cleanFnSigB64, precedingThinking = "Step 2 think")
                    )
                )
            )
        )
        val multiBatchHistory = listOf(
            MessageNode(id = "u-mb", role = MessageRole.USER, variants = listOf(MessageVariant(id = "vu-mb", content = "Run tools"))),
            multiBatchAssistantTurn,
            MessageNode(id = "u-mb2", role = MessageRole.USER, variants = listOf(MessageVariant(id = "vu-mb2", content = "Next turn")))
        )

        // Gemini Interactions: parallel calls p1 & p2 must be grouped (thought -> FC1 -> FC2 -> FR1 -> FR2), followed by sequential batch 2 (thought -> FC3 -> FR3 -> model_output)
        val mbInteractions = AiProviderEngine.buildGeminiInteractionsTurnsArray(multiBatchHistory, sessionWithMedia, "")
        val mbIntTypes = mbInteractions.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
        assertEquals(
            listOf("user_input", "thought", "function_call", "function_call", "function_result", "function_result", "thought", "function_call", "function_result", "model_output", "user_input"),
            mbIntTypes,
            "Parallel batch calls must emit all function_calls before function_results, with sanitized thought signature"
        )
        assertEquals(cleanFnSigB64, mbInteractions[1].jsonObject["signature"]?.jsonPrimitive?.contentOrNull)

        // Gemini GenerateContent: batch 1 and batch 2 must be separate model -> user rounds, followed by final model prose turn
        val mbGenContents = AiProviderEngine.buildGeminiGenerateContentContents(multiBatchHistory, sessionWithMedia, "")
        assertEquals(
            listOf("user", "model", "user", "model", "user", "model", "user"),
            mbGenContents.map { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull },
            "GenerateContent must keep each sequential tool batch in its own model->user turn pair before final model prose"
        )

        // OpenAI Chat Completions: final assistant prose must appear AFTER all role='tool' messages
        val mbChatMsgs = AiProviderEngine.buildOpenAiChatCompletionsMessages(multiBatchHistory, sessionWithMedia, "", "")
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "assistant", "tool", "assistant", "user"),
            mbChatMsgs.map { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull },
            "Chat Completions must emit assistant(tool_calls)->tool->tool per batch and closing assistant(content) after all tool results"
        )

        // Cross-provider isolation: Gemini signature must NOT leak into OpenAI Responses encrypted_content or Anthropic thinking.signature
        val mbOpenAiResp = AiProviderEngine.buildOpenAiResponsesInputArray(multiBatchHistory, sessionWithMedia, "")
        assertTrue(
            mbOpenAiResp.none { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" },
            "Gemini signature must never be sent as OpenAI Responses encrypted_content"
        )
        val mbAnthropic = AiProviderEngine.buildAnthropicBaseMessages(multiBatchHistory, sessionWithMedia, "")
        assertTrue(
            mbAnthropic.flatMap { it.second }.none { it["type"]?.jsonPrimitive?.contentOrNull == "thinking" },
            "Gemini signature must never be sent as Anthropic thinking.signature"
        )

        // Self-healing foldHistoricalToolsToText mode
        val mbFolded = AiProviderEngine.buildGeminiInteractionsTurnsArray(multiBatchHistory, sessionWithMedia, "", foldHistoricalToolsToText = true)
        assertEquals(listOf("user_input", "model_output", "user_input"), mbFolded.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull })

        // Wall-clock tok/s calculation (no micro-burst 5000 tok/s inflation)
        val calmMetrics = AiProviderEngine.computeTurnTokenMetrics(
            reportedInputTokens = 100,
            reportedCachedTokens = 0,
            reportedReasoningTokens = 80,
            reportedOutputTokens = 8,
            fallbackInputTokens = 100,
            contentText = "yo, what's good",
            thinkingText = "Thinking about greeting.",
            executedTools = emptyList(),
            totalDurationMs = 10_500L,
            ttftMs = 7_400L,
            activeDecodeDurationMs = 40L,
            totalToolExecutionMs = 0L
        )
        assertTrue(calmMetrics.tokensPerSec in 5.0..20.0, "88 tokens over 10.5s wall-clock generation must be ~8.3 tok/s, got ${calmMetrics.tokensPerSec}")

        // 5. Verify automatic on-load / on-save session signature self-repair (stripRedundantAttachmentBase64)
        val corruptedSession = sessionWithMedia.copy(
            messages = multiBatchHistory
        )
        val selfHealedSession = io.parchment.ai.ui.stripRedundantAttachmentBase64(corruptedSession)
        val healedAsstVar = selfHealedSession.messages[1].activeVariant
        assertEquals(cleanFnSigB64, healedAsstVar.thinkingSignature, "On-load/on-save sanitizer must repair glued variant thinkingSignature")
        assertEquals(cleanFnSigB64, healedAsstVar.toolCalls[0].thoughtSignature, "On-load/on-save sanitizer must repair glued toolCall thoughtSignature")
    }

    @Test
    fun testMultiSourceRegistryUniversalAutoRoutingAndLabIcons() {
        // 1. Verify Big Three + Custom Protocol Consolidation & Legacy BYTEDANCE_ARK Migration
        assertEquals(
            listOf(
                ProviderProtocol.GEMINI_INTERACTIONS,
                ProviderProtocol.OPENAI_RESPONSES,
                ProviderProtocol.ANTHROPIC_MESSAGES,
                ProviderProtocol.CUSTOM
            ),
            ProviderProtocol.entries.toList()
        )
        val legacyJson = """{"profiles":[{"id":"p-bd","name":"Ark","protocol":"BYTEDANCE_ARK","baseUrl":"https://ark.ap-southeast.bytepluses.com/api/v3"}]}"""
        val migratedState = io.parchment.ai.ui.recoverAppStateFromPartialJson(legacyJson)
        val migratedProfile = migratedState.profiles.first { it.id == "p-bd" }
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, migratedProfile.protocol)
        assertEquals("https://ark.ap-southeast.bytepluses.com/api/v3", migratedProfile.baseUrl)

        // 2. Endpoint URLs resolve without a forced path or API version
        val defaultOpenAi = ProviderProfile(id = "p1", name = "OpenAI", protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://api.openai.com")
        assertEquals("https://api.openai.com/v1/responses", AiProviderEngine.resolveSmartEndpointUrl(defaultOpenAi, "/v1/responses", "/responses"))

        val arkBaseProfile = defaultOpenAi.copy(baseUrl = "https://ark.ap-southeast.bytepluses.com/api/v3")
        assertEquals("https://ark.ap-southeast.bytepluses.com/api/v3/chat/completions", AiProviderEngine.resolveSmartEndpointUrl(arkBaseProfile, "/v1/chat/completions", "/chat/completions"))

        val zhipuV4Profile = defaultOpenAi.copy(baseUrl = "https://open.bigmodel.cn/api/paas/v4")
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", AiProviderEngine.resolveSmartEndpointUrl(zhipuV4Profile, "/v1/chat/completions", "/chat/completions"))

        val customProtocolProfile = defaultOpenAi.copy(
            protocol = ProviderProtocol.CUSTOM,
            customDialect = io.parchment.ai.model.CUSTOM_DIALECT_OPENAI_CHAT,
            baseUrl = "https://future-api.example.com/custom/v5/run"
        )
        assertEquals("https://future-api.example.com/custom/v5/run", AiProviderEngine.resolveSmartEndpointUrl(customProtocolProfile, "/v1/responses", "/responses"))

        // Custom protocol with no dialect must resolve to CUSTOM (Send blocked, never guessed).
        val unresolvedCustom = customProtocolProfile.copy(customDialect = "")
        assertEquals(ProviderProtocol.CUSTOM, AiProviderEngine.resolveWireProtocol(unresolvedCustom))
        assertTrue(AiProviderEngine.isCustomDialectUnresolved(unresolvedCustom))
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, AiProviderEngine.resolveWireProtocol(customProtocolProfile))
        assertFalse(AiProviderEngine.isCustomDialectUnresolved(customProtocolProfile))

        val hashTailFreeProfile = defaultOpenAi.copy(baseUrl = "https://my-proxy.example.com/custom-endpoint#")
        assertEquals("https://my-proxy.example.com/custom-endpoint", AiProviderEngine.resolveSmartEndpointUrl(hashTailFreeProfile, "/v1/responses", "/responses"))

        val concreteEndpointInBase = defaultOpenAi.copy(baseUrl = "https://my-gateway.example.com/v1/chat/completions")
        assertEquals("https://my-gateway.example.com/v1/chat/completions", AiProviderEngine.resolveSmartEndpointUrl(concreteEndpointInBase, "/v1/responses", "/responses"))

        // 3. Verify Multi-Source Unified Model Registry Parsers, Canonical Slug Normalization & Modality-Preserving Merge
        assertEquals("seedream-5-0-flash", io.parchment.ai.network.ModelsDevClient.normalizeCanonicalModelSlug("bytedance-seed/seedream-5-0-flash"))
        assertEquals("seedream-5-0-flash", io.parchment.ai.network.ModelsDevClient.normalizeCanonicalModelSlug("bytedance/seedream-5.0-flash"))
        assertEquals("seedream-5-0", io.parchment.ai.network.ModelsDevClient.normalizeCanonicalModelSlug("volcengine/doubao-seedream-5-0-260128"))
        assertEquals("kling-3-0-pro", io.parchment.ai.network.ModelsDevClient.normalizeCanonicalModelSlug("kwaivgi/kling-v3.0-pro"))

        val testCatalog = mutableMapOf<String, io.parchment.ai.model.ModelPricingSpec>()
        io.parchment.ai.network.ModelsDevClient.parseOpenRouterModelsIntoCatalog(
            rawJson = """{"data":[{"id":"bytedance-seed/seedream-5-0-flash","name":"Seedream 5.0 Flash","context_length":0,"architecture":{"modality":"text->image","input_modalities":["text","image"],"output_modalities":["image"]},"pricing":{"prompt":"0.000001","completion":"0.000002"}}]}""",
            catalog = testCatalog
        )
        io.parchment.ai.network.ModelsDevClient.parseOpenRouterModelsIntoCatalog(
            rawJson = """{"data":[{"id":"bytedance/seedance-2.5","name":"Seedance 2.5","input_modalities":["text","image"],"output_modalities":["video"]}]}""",
            catalog = testCatalog,
            forceVideoOutput = true
        )
        io.parchment.ai.network.ModelsDevClient.parseVercelModelsIntoCatalog(
            rawJson = """{"data":[{"id":"bytedance/seedream-5.0-flash","name":"Seedream 5.0 Flash","owned_by":"bytedance","type":"image","context_window":0,"modalities":{"input":["text"],"output":["image"]}}]}""",
            catalog = testCatalog
        )
        io.parchment.ai.network.ModelsDevClient.parseAimlApiModelsIntoCatalog(
            rawJson = """{"data":[{"id":"bytedance/dreamina-seedance-2-5","type":"internal/video-generations/submit","info":{"name":"Dreamina Seedance 2.5","developer":"ByteDance"},"tags":["playground:video"],"aliases":["seedance-2-5-pro"]}]}""",
            catalog = testCatalog
        )
        io.parchment.ai.network.ModelsDevClient.parseLiteLlmModelsIntoCatalog(
            rawJson = """{"volcengine/doubao-seedream-5-0-260128":{"litellm_provider":"volcengine","mode":"image_generation"},"google/veo-3.1-generate-preview":{"litellm_provider":"vertex_ai","mode":"video_generation"},"openai/tts-1-hd":{"litellm_provider":"openai","mode":"audio_speech"}}""",
            catalog = testCatalog
        )

        val lookedUpSeedream = io.parchment.ai.network.ModelsDevClient.lookupSpecInCatalog(testCatalog, "seedream-5.0-flash")
        assertNotNull(lookedUpSeedream)
        assertEquals(listOf("image"), lookedUpSeedream.outputModalities)

        val lookedUpSeedance = io.parchment.ai.network.ModelsDevClient.lookupSpecInCatalog(testCatalog, "seedance-2.5")
        assertNotNull(lookedUpSeedance)
        assertEquals(listOf("video"), lookedUpSeedance.outputModalities)

        val lookedUpAlias = io.parchment.ai.network.ModelsDevClient.lookupSpecInCatalog(testCatalog, "seedance-2-5-pro")
        assertNotNull(lookedUpAlias)
        assertEquals(listOf("video"), lookedUpAlias.outputModalities)

        val lookedUpVeo = io.parchment.ai.network.ModelsDevClient.lookupSpecInCatalog(testCatalog, "veo-3.1")
        assertNotNull(lookedUpVeo)
        assertEquals(listOf("video"), lookedUpVeo.outputModalities)

        // Ensure generic ["text"] fallback never overwrites ["image"] or ["video"] in mergeModelSpecs
        val mergedPreservesMedia = io.parchment.ai.network.ModelsDevClient.mergeModelSpecs(
            existing = lookedUpSeedream,
            incoming = io.parchment.ai.model.ModelPricingSpec(modelId = "seedream-5.0-flash", contextWindowTokens = 128000, outputModalities = listOf("text"))
        )
        assertEquals(listOf("image"), mergedPreservesMedia.outputModalities)

        // 4. Capabilities come from settings, the provider and the registry, never from a model's name
        listOf("flux-writer-unlisted", "wan-chat-unlisted", "veo-lite-assistant-unlisted", "my-realtime-notes-unlisted").forEach { misleading ->
            val profile = AiProviderEngine.resolveModelModalityProfile(defaultOpenAi, misleading)
            assertEquals(listOf("text"), profile.outputModalities, misleading)
            assertFalse(profile.isLiveRealtimeAudio, misleading)
        }
        val imageEndpoint = defaultOpenAi.copy(endpointPathOverride = "/v1/images/generations")
        assertTrue(AiProviderEngine.resolveModelModalityProfile(imageEndpoint, "unlisted-model").isDedicatedImage)
        fun withSpec(id: String, vararg outputs: String) = defaultOpenAi.copy(
            customModelSpecs = mapOf(id to io.parchment.ai.model.ModelPricingSpec(modelId = id, outputModalities = outputs.toList()))
        )
        assertTrue(AiProviderEngine.resolveModelModalityProfile(withSpec("house-model", "video"), "house-model").isDedicatedVideo)
        assertFalse(AiProviderEngine.resolveModelModalityProfile(withSpec("audio-chat", "text", "audio"), "audio-chat").isLiveRealtimeAudio)
        assertTrue(AiProviderEngine.resolveModelModalityProfile(withSpec("voice-live", "text", "audio", "realtime"), "voice-live").isLiveRealtimeAudio)
        // A setting beats what a provider reported for the same model
        AiProviderEngine.parseModelsListJson("""{"data":[{"id":"conflicted-model","output_modalities":["image"]}]}""")
        assertTrue(AiProviderEngine.resolveModelModalityProfile(defaultOpenAi, "conflicted-model").isDedicatedImage)
        assertFalse(AiProviderEngine.resolveModelModalityProfile(withSpec("conflicted-model", "text"), "conflicted-model").isDedicatedImage)

        // 5. Verify Model Lab Icons Are Never Hijacked by fallbackProtocolName ("OPENAI_RESPONSES")
        assertEquals("doubao", resolveLabSlugForModelOrProvider("seedream-5-0-pro", "OPENAI_RESPONSES"))
        assertEquals("doubao", resolveLabSlugForModelOrProvider("bytedance/seedance-2.5", "OPENAI_RESPONSES"))
        assertEquals("doubao", resolveLabSlugForModelOrProvider("bytedance-seed/seed-2-1-turbo", "OPENAI_RESPONSES"))
        assertEquals("gemini", resolveLabSlugForModelOrProvider("deep-research-preview-04-2026", "OPENAI_RESPONSES"))
        assertEquals("google", resolveLabSlugForModelOrProvider("veo-3.1-fast", "OPENAI_RESPONSES"))
        assertEquals("google", resolveLabSlugForModelOrProvider("imagen-4.0-ultra", "OPENAI_RESPONSES"))
        assertEquals("deepseek", resolveLabSlugForModelOrProvider("deepseek-r1", "OPENAI_RESPONSES"))
        assertEquals("qwen", resolveLabSlugForModelOrProvider("wan-3.0-prime", "OPENAI_RESPONSES"))
        assertEquals("minimax", resolveLabSlugForModelOrProvider("hailuo-3-max", "OPENAI_RESPONSES"))
        assertEquals("openai", resolveLabSlugForModelOrProvider("sora-2-pro", "GEMINI_INTERACTIONS"))
        assertEquals("openai", resolveLabSlugForModelOrProvider("Custom Proxy Name", "OPENAI_RESPONSES"))
    }

    @Test
    fun testV2053CustomProtocolReasoningHandoffAndMigrations() {
        // 2. One-shot typeface migration only rewrites the OLD defaults.
        val legacyTheme = io.parchment.ai.model.ThemeConfig(proseFontSizeSp = 16.5f, codeFontSizeSp = 12.5f)
        val migratedTheme = io.parchment.ai.ui.migrateThemeTypefaceDefaults(legacyTheme)
        assertEquals(14.0f, migratedTheme.proseFontSizeSp)
        assertEquals(14.0f, migratedTheme.codeFontSizeSp)
        val userTheme = io.parchment.ai.model.ThemeConfig(proseFontSizeSp = 18.0f, codeFontSizeSp = 11.0f)
        val untouched = io.parchment.ai.ui.migrateThemeTypefaceDefaults(userTheme)
        assertEquals(18.0f, untouched.proseFontSizeSp)
        assertEquals(11.0f, untouched.codeFontSizeSp)

        // 3. Tab strip normalization: dedupe, guarantee active, MRU cap.
        assertEquals(listOf("a", "b"), io.parchment.ai.ui.normalizeOpenTabs(listOf("a", "a", "b"), "b"))
        assertEquals(listOf("a", "b"), io.parchment.ai.ui.normalizeOpenTabs(listOf("a"), "b"))
        val many = (1..20).map { "s$it" }
        val capped = io.parchment.ai.ui.normalizeOpenTabs(many, "s1")
        assertEquals(io.parchment.ai.ui.MAX_OPEN_TABS, capped.size)
        assertTrue("s1" in capped)

        // 4. Cross-provider reasoning handoff: summary text carries, signatures never do.
        val geminiVariant = io.parchment.ai.model.MessageVariant(
            id = "v1",
            content = "Answer from Gemini.",
            thinkingSummary = "I considered the vault layout first.",
            providerProtocol = ProviderProtocol.GEMINI_INTERACTIONS,
            modelUsed = "gemini-4-argon"
        )
        val node = io.parchment.ai.model.MessageNode(
            id = "n1",
            role = io.parchment.ai.model.MessageRole.ASSISTANT,
            variants = listOf(geminiVariant),
            activeVariantIndex = 0
        )
        val handedOff = AiProviderEngine.applyCrossProviderReasoningHandoff(
            nodes = listOf(node),
            currentProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            chainingActive = false
        )
        val handedContent = handedOff.first().activeVariant.content
        assertTrue(handedContent.contains(AiProviderEngine.REASONING_HANDOFF_MARKER))
        assertTrue(handedContent.contains("I considered the vault layout first."))
        assertTrue(handedContent.contains("Answer from Gemini."))

        // Same protocol -> no handoff (the model already owns that reasoning).
        val sameProtocol = AiProviderEngine.applyCrossProviderReasoningHandoff(
            nodes = listOf(node),
            currentProtocol = ProviderProtocol.GEMINI_INTERACTIONS,
            chainingActive = false
        )
        assertEquals("Answer from Gemini.", sameProtocol.first().activeVariant.content)

        // Chaining active -> no handoff (server already holds those steps).
        val chained = AiProviderEngine.applyCrossProviderReasoningHandoff(
            nodes = listOf(node),
            currentProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            chainingActive = true
        )
        assertEquals("Answer from Gemini.", chained.first().activeVariant.content)

        // 5. Privacy-first defaults: stateless unless explicitly enabled.
        val freshProfile = io.parchment.ai.model.ProviderProfile(
            id = "p1",
            name = "Fresh",
            protocol = ProviderProtocol.GEMINI_INTERACTIONS,
            baseUrl = "https://generativelanguage.googleapis.com"
        )
        assertFalse(freshProfile.useStatefulChaining)
        assertFalse(freshProfile.storeServerSide)
        assertEquals("", freshProfile.customDialect)
    }

}
