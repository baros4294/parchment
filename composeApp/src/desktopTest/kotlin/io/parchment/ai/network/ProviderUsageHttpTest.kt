package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ModelPricingSpec
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderUsageHttpTest {
    private val model = "usage-fixture-model"
    private data class Reply(val body: String, val sse: Boolean, val status: Int = 200)

    private fun usage(dialect: UsageDialect, round: Int): JsonObject = buildJsonObject {
        val input = if (round == 0) 100 else 200
        val cached = if (round == 0) 0 else 40
        val created = if (round == 0) 20 else 30
        val output = if (round == 0) 20 else 30
        val reasoning = if (round == 0) 5 else 7
        when (dialect) {
            UsageDialect.OPENAI_RESPONSES, UsageDialect.OPENAI_CHAT -> {
                val responses = dialect == UsageDialect.OPENAI_RESPONSES
                put(if (responses) "input_tokens" else "prompt_tokens", input)
                put(if (responses) "output_tokens" else "completion_tokens", output)
                putJsonObject(if (responses) "input_tokens_details" else "prompt_tokens_details") {
                    put("cached_tokens", cached)
                    if (responses) {
                        put("cache_write_tokens", created)
                        putJsonObject("cache_write_tokens_details") {
                            put("ttl_30m_tokens", if (round == 0) created else 0)
                            put("ttl_12h_tokens", if (round == 1) created else 0)
                        }
                    }
                }
                putJsonObject(if (responses) "output_tokens_details" else "completion_tokens_details") { put("reasoning_tokens", reasoning) }
            }
            UsageDialect.ANTHROPIC -> {
                put("input_tokens", input)
                put("cache_read_input_tokens", cached)
                put("cache_creation_input_tokens", created)
                putJsonObject("cache_creation") {
                    put("ephemeral_5m_input_tokens", if (round == 0) created else 0)
                    put("ephemeral_1h_input_tokens", if (round == 1) created else 0)
                }
                put("output_tokens", output)
            }
            UsageDialect.GEMINI_INTERACTIONS -> {
                put("total_input_tokens", input); put("total_cached_tokens", cached)
                put("total_output_tokens", output); put("total_thought_tokens", reasoning)
            }
            UsageDialect.GEMINI_CONTENT -> {
                put("promptTokenCount", input); put("cachedContentTokenCount", cached)
                put("candidatesTokenCount", output); put("thoughtsTokenCount", reasoning)
            }
        }
    }

    private fun event(type: String, body: JsonObject): String = "event: $type\ndata: $body\n\n"
    private fun args(): JsonObject = buildJsonObject { put("expression", "2+2") }
    private fun packet(dialect: UsageDialect, round: Int, sse: Boolean, terminalOnly: Boolean = false, partialFinalText: Boolean = false): Reply {
        val id = "response-$round"
        val tool = round == 0
        val stats = usage(dialect, round)
        when (dialect) {
            UsageDialect.OPENAI_RESPONSES -> {
                val output = buildJsonArray {
                    add(if (tool) buildJsonObject {
                        put("type", "function_call"); put("id", "item-1"); put("call_id", "call-1")
                        put("name", "calc_eval"); put("arguments", args().toString())
                    } else buildJsonObject {
                        put("type", "message"); put("role", "assistant"); put("status", "completed")
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", "Done.") }) })
                    })
                }
                val root = buildJsonObject { put("id", id); put("status", "completed"); put("output", output); put("usage", stats) }
                if (!sse) return Reply(root.toString(), false)
                val start = event("response.created", buildJsonObject {
                    put("type", "response.created"); put("response", buildJsonObject { put("id", id); put("status", "in_progress") })
                })
                val text = if (tool) "" else event("response.output_text.delta", buildJsonObject {
                    put("type", "response.output_text.delta"); put("delta", "Done.")
                })
                val final = event("response.completed", buildJsonObject { put("type", "response.completed"); put("response", root) })
                return Reply(start + text + final + final, true)
            }
            UsageDialect.OPENAI_CHAT -> {
                val message = buildJsonObject {
                    put("role", "assistant")
                    if (tool) put("tool_calls", buildJsonArray { add(buildJsonObject {
                        put("id", "call-1"); put("type", "function")
                        put("function", buildJsonObject { put("name", "calc_eval"); put("arguments", args().toString()) })
                    }) }) else put("content", "Done.")
                }
                val root = buildJsonObject {
                    put("id", id); put("usage", stats)
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("finish_reason", if (tool) "tool_calls" else "stop")
                        put(if (sse) "delta" else "message", message)
                    }) })
                }
                return if (!sse) Reply(root.toString(), false) else {
                    val counters = buildJsonObject { put("id", id); put("choices", JsonArray(emptyList())); put("usage", stats) }
                    Reply("data: $root\n\ndata: $counters\n\ndata: [DONE]\n\n", true)
                }
            }
            UsageDialect.GEMINI_INTERACTIONS -> {
                val outputs = buildJsonArray { add(if (tool) buildJsonObject {
                    put("type", "function_call"); put("id", "call-1"); put("name", "calc_eval"); put("arguments", args())
                } else buildJsonObject { put("type", "text"); put("text", "Done.") }) }
                val root = buildJsonObject { put("id", id); put("status", "completed"); put("outputs", outputs); put("usage", stats) }
                if (!sse) return Reply(root.toString(), false)
                val start = event("interaction.created", buildJsonObject {
                    put("event_type", "interaction.created")
                    put("interaction", buildJsonObject { put("id", id); put("status", "in_progress") })
                })
                fun stepDelta(text: String) = event("step.delta", buildJsonObject {
                    put("event_type", "step.delta"); put("index", 0)
                    put("delta", buildJsonObject { put("type", "text"); put("text", text) })
                    put("metadata", buildJsonObject { put("total_usage", stats) })
                })
                if (terminalOnly) return Reply(start + (if (partialFinalText && !tool) stepDelta("Do") else "") +
                    event("interaction.completed", buildJsonObject { put("event_type", "interaction.completed"); put("interaction", root) }), true)
                val stepStart = event("step.start", buildJsonObject {
                    put("event_type", "step.start"); put("index", 0)
                    put("step", if (tool) buildJsonObject {
                        put("type", "function_call"); put("id", "call-1"); put("name", "calc_eval"); put("arguments", buildJsonObject {})
                    } else buildJsonObject { put("type", "model_output") })
                })
                val delta = if (tool) event("step.delta", buildJsonObject {
                    put("event_type", "step.delta"); put("index", 0)
                    put("delta", buildJsonObject { put("type", "arguments_delta"); put("arguments", args().toString()) })
                    put("metadata", buildJsonObject { put("total_usage", stats) })
                }) else stepDelta("Done.")
                val stop = event("step.stop", buildJsonObject { put("event_type", "step.stop"); put("index", 0); put("usage", stats) })
                val final = event("interaction.completed", buildJsonObject {
                    put("event_type", "interaction.completed")
                    put("interaction", buildJsonObject { put("id", id); put("status", "completed") })
                })
                return Reply(start + stepStart + delta + stop + final, true)
            }
            UsageDialect.GEMINI_CONTENT -> {
                val root = buildJsonObject {
                    put("responseId", id); put("usageMetadata", stats)
                    put("candidates", buildJsonArray { add(buildJsonObject {
                        put("finishReason", "STOP")
                        put("content", buildJsonObject {
                            put("role", "model"); put("parts", buildJsonArray { add(if (tool) buildJsonObject {
                                put("functionCall", buildJsonObject { put("id", "call-1"); put("name", "calc_eval"); put("args", args()) })
                            } else buildJsonObject { put("text", "Done.") }) })
                        })
                    }) })
                }
                return Reply(if (sse) "data: $root\n\n" else root.toString(), sse)
            }
            UsageDialect.ANTHROPIC -> {
                val content = buildJsonArray { add(if (tool) buildJsonObject {
                    put("type", "tool_use"); put("id", "call-1"); put("name", "calc_eval"); put("input", args())
                } else buildJsonObject { put("type", "text"); put("text", "Done.") }) }
                val root = buildJsonObject {
                    put("id", id); put("type", "message"); put("role", "assistant")
                    put("content", content); put("usage", stats); put("stop_reason", if (tool) "tool_use" else "end_turn")
                }
                if (!sse) return Reply(root.toString(), false)
                val start = event("message_start", buildJsonObject {
                    put("type", "message_start"); put("message", buildJsonObject {
                        put("id", id); put("usage", JsonObject(stats + ("output_tokens" to kotlinx.serialization.json.JsonPrimitive(1))))
                    })
                })
                val block = event("content_block_start", buildJsonObject {
                    put("type", "content_block_start"); put("index", 0); put("content_block", content[0])
                })
                val delta = event("message_delta", buildJsonObject {
                    put("type", "message_delta"); put("usage", buildJsonObject { put("output_tokens", if (tool) 20 else 30) })
                })
                return Reply(start + block + delta + delta + event("message_stop", buildJsonObject { put("type", "message_stop") }), true)
            }
        }
    }

    private fun verify(dialect: UsageDialect, streaming: Boolean, terminalOnly: Boolean = false, partialFinalText: Boolean = false) = runBlocking {
        val calls = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "usage-fixture-http").apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val reply = if (dialect == UsageDialect.GEMINI_CONTENT && exchange.requestURI.path.contains("interactions")) {
                Reply("""{"error":{"message":"Fixture has no Interactions endpoint"}}""", false, 404)
            } else {
                val round = calls.getAndIncrement()
                requests.add(Json.parseToJsonElement(body).jsonObject)
                if (round > 1) Reply("""{"error":{"message":"Fixture request limit reached"}}""", false, 400)
                else packet(dialect, round, streaming, terminalOnly, partialFinalText)
            }
            exchange.responseHeaders.set("Content-Type", if (reply.sse) "text/event-stream" else "application/json")
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val workspace = java.nio.file.Files.createTempDirectory("parchment-usage-http-")
        try {
            val nativeContent = dialect == UsageDialect.GEMINI_CONTENT
            val profile = ProviderProfile(id = "fixture", name = "Fixture", protocol = if (nativeContent) ProviderProtocol.GEMINI_INTERACTIONS else ProviderProtocol.CUSTOM,
                customDialect = if (nativeContent) "" else dialect.wireName,
                baseUrl = "http://127.0.0.1:${server.address.port}", defaultModel = model,
                useLegacyFallbackEndpoint = nativeContent, thinkingEffort = "none", timeoutMinutes = 1, maxOutputTokens = 128,
                inputPricePerM = 1.0, outputPricePerM = 2.0, cacheReadPricePerM = 0.1,
                customModelSpecs = mapOf(model to ModelPricingSpec(model, outputModalities = listOf("text"), inputModalities = listOf("text"))))
            val session = ChatSession(id = "fixture", title = "Fixture", createdAt = 1, updatedAt = 1, profileId = profile.id, modelOverride = model,
                workspacePath = workspace.toString(), workspaceMode = io.parchment.ai.model.WorkspaceMode.READ_ONLY)
            val user = MessageNode(id = "user", role = MessageRole.USER,
                variants = listOf(MessageVariant(id = "user-variant", content = "Calculate 2+2, then reply Done.")))
            val updates = withTimeout(20_000) { AiProviderEngine.streamGeneration(profile, session, listOf(user)).toList() }
            val errors = updates.filterIsInstance<StreamUpdate.Error>()
            assertTrue(errors.isEmpty(), "Unexpected fixture errors: $errors")
            val completed = updates.filterIsInstance<StreamUpdate.Completed>().singleOrNull()
            assertNotNull(completed)
            assertEquals("Done.", completed.fullContent)
            assertEquals(2, calls.get())
            val recordedRequests = if (nativeContent) 3 else 2
            assertEquals(recordedRequests, completed.apiUsageRecords.size)
            assertEquals(recordedRequests, completed.apiUsageRecords.map { it.requestId }.distinct().size)
            assertEquals(recordedRequests, completed.metrics.reportedRequests)
            assertEquals(!nativeContent, completed.metrics.usageIsComplete)
            assertEquals(!nativeContent, completed.metrics.costIsAvailable)
            if (nativeContent) {
                val failedDiscovery = completed.apiUsageRecords.first()
                assertEquals(UsageDialect.GEMINI_INTERACTIONS.wireName, failedDiscovery.wireDialect)
                assertEquals(null, failedDiscovery.usage.inputTokens)
                assertEquals(null, failedDiscovery.usage.outputTokens)
            }
            assertEquals(if (dialect == UsageDialect.ANTHROPIC) 390L else 300L, completed.metrics.inputTokens)
            assertEquals(40L, completed.metrics.cachedInputTokens)
            val writes = dialect == UsageDialect.ANTHROPIC || dialect == UsageDialect.OPENAI_RESPONSES
            assertEquals(if (writes) 50L else 0L, completed.metrics.cacheCreationInputTokens)
            assertEquals(if (dialect == UsageDialect.ANTHROPIC) 0L else 12L, completed.metrics.reasoningTokens)
            assertEquals(if (dialect == UsageDialect.OPENAI_RESPONSES || dialect == UsageDialect.OPENAI_CHAT) 38L else 50L, completed.metrics.outputTokens)
            val expectedCost = when (dialect) {
                UsageDialect.OPENAI_RESPONSES -> 0.000414
                UsageDialect.OPENAI_CHAT -> 0.000364
                UsageDialect.ANTHROPIC -> 0.000489
                else -> 0.000388
            }
            assertEquals(expectedCost, completed.metrics.estimatedCostUsd, 1e-12)
            assertEquals("calc_eval", completed.toolCalls.single().name)
            assertFalse(completed.toolCalls.single().isError)
            assertTrue(completed.toolCalls.single().resultText.contains("4"))
            assertTrue(requests[1].toString().contains("calc_eval"))
            assertTrue(requests[1].toString().contains("4"))
            assertTrue(updates.filterIsInstance<StreamUpdate.UsageUpdated>().isNotEmpty())
        } finally {
            server.stop(0)
            executor.shutdownNow()
            java.nio.file.Files.delete(workspace)
        }
    }

    @Test fun responsesSse() = verify(UsageDialect.OPENAI_RESPONSES, true)
    @Test fun responsesJson() = verify(UsageDialect.OPENAI_RESPONSES, false)
    @Test fun chatSse() = verify(UsageDialect.OPENAI_CHAT, true)
    @Test fun chatJson() = verify(UsageDialect.OPENAI_CHAT, false)
    @Test fun interactionsSse() = verify(UsageDialect.GEMINI_INTERACTIONS, true)
    @Test fun interactionsTerminalOnlyTextDoesNotTriggerExtraRequests() = verify(UsageDialect.GEMINI_INTERACTIONS, true, terminalOnly = true)
    @Test fun interactionsFinalTextCompletesAStreamedPrefix() = verify(UsageDialect.GEMINI_INTERACTIONS, true, terminalOnly = true, partialFinalText = true)
    @Test fun interactionsJson() = verify(UsageDialect.GEMINI_INTERACTIONS, false)
    @Test fun generateContentSse() = verify(UsageDialect.GEMINI_CONTENT, true)
    @Test fun generateContentJson() = verify(UsageDialect.GEMINI_CONTENT, false)
    @Test fun anthropicSse() = verify(UsageDialect.ANTHROPIC, true)
    @Test fun anthropicJson() = verify(UsageDialect.ANTHROPIC, false)
}
