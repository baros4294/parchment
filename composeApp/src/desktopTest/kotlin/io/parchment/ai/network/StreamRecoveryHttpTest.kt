package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.ktor.utils.io.ByteReadChannel
import io.parchment.ai.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StreamRecoveryHttpTest {
    private data class Reply(val body: String, val status: Int = 200)

    private class Server(private val replies: List<Reply>) : AutoCloseable {
        private val next = AtomicInteger()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.add(exchange.requestBody.bufferedReader().use { it.readText() })
                val reply = replies[next.getAndIncrement().coerceAtMost(replies.lastIndex)]
                val bytes = reply.body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val baseUrl get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    private fun profile(server: Server, dialect: String) = ProviderProfile(
        "fixture", "Fixture", ProviderProtocol.CUSTOM, customDialect = dialect, baseUrl = server.baseUrl,
        defaultModel = "fixture-model", thinkingEffort = "none", customModelSpecs = mapOf("fixture-model" to ModelPricingSpec("fixture-model"))
    )

    private fun updates(profile: ProviderProfile) = AiProviderEngine.streamGeneration(
        profile = profile,
        session = ChatSession("chat", "Fixture", 1L, 1L, profile.id),
        historyNodes = listOf(MessageNode("user", MessageRole.USER, listOf(MessageVariant("variant", content = "Hello"))))
    )

    /** Stops at the first retry notice so a test never sits through the real retry wait. */
    private fun outcome(profile: ProviderProfile): StreamUpdate = runBlocking {
        withTimeout(20_000) {
            updates(profile).first { it is StreamUpdate.Completed || (it is StreamUpdate.Status && "Auto-retrying (" in it.message) }
        }
    }

    private fun assertResumes(update: StreamUpdate, reason: String = "Stream ended before") {
        val status = assertIs<StreamUpdate.Status>(update, "An unfinished stream must not be accepted as the answer")
        assertTrue(reason in status.message, status.message)
    }

    private val chatText = """{"choices":[{"delta":{"content":"partial"}}]}"""
    private val responsesText = """{"type":"response.output_text.delta","delta":"partial"}"""
    private val anthropicText = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"partial"}}"""
    private val geminiStatus = """{"event_type":"interaction.status_update","interaction_id":"i"}"""
    private val generateText = """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""

    private val cutStreams = mapOf(
        CUSTOM_DIALECT_OPENAI_CHAT to sse(chatText),
        CUSTOM_DIALECT_OPENAI_RESPONSES to sse(responsesText),
        CUSTOM_DIALECT_ANTHROPIC to sse(anthropicText),
        CUSTOM_DIALECT_GEMINI to sse(geminiStatus)
    )

    private val finishedStreams = mapOf(
        CUSTOM_DIALECT_OPENAI_CHAT to sse(chatText, """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
        CUSTOM_DIALECT_OPENAI_RESPONSES to sse(responsesText, """{"type":"response.completed","response":{"id":"r","status":"completed","output":[]}}"""),
        CUSTOM_DIALECT_ANTHROPIC to sse(anthropicText, """{"type":"message_stop"}"""),
        CUSTOM_DIALECT_GEMINI to sse("""{"event_type":"interaction.complete","interaction":{"id":"i","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"partial"}]}]}}""")
    )

    @Test fun cutStreamsResumeOnEveryCustomWire() {
        for ((dialect, body) in cutStreams) Server(listOf(Reply(body))).use { server ->
            assertResumes(outcome(profile(server, dialect)))
        }
    }

    @Test fun finishedStreamsCompleteOnEveryCustomWire() {
        for ((dialect, body) in finishedStreams) Server(listOf(Reply(body))).use { server ->
            assertEquals("partial", assertIs<StreamUpdate.Completed>(outcome(profile(server, dialect)), dialect).fullContent)
        }
    }

    private fun geminiProfile(server: Server) = ProviderProfile(
        "gemini", "Gemini", ProviderProtocol.GEMINI_INTERACTIONS, baseUrl = server.baseUrl,
        defaultModel = "fixture-model", thinkingEffort = "none", customModelSpecs = mapOf("fixture-model" to ModelPricingSpec("fixture-model"))
    )

    @Test fun generateContentNeedsAFinishReason() {
        val missing = Reply("""{"error":{"message":"missing"}}""", 404)
        Server(listOf(missing, Reply(sse(generateText)))).use { assertResumes(outcome(geminiProfile(it))) }
        val done = """{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"STOP"}]}"""
        Server(listOf(missing, Reply(sse(done)))).use { assertIs<StreamUpdate.Completed>(outcome(geminiProfile(it))) }
    }

    @Test fun doneMarkerAloneFinishesAStream() = Server(listOf(Reply(sse(chatText, "[DONE]")))).use { server ->
        assertIs<StreamUpdate.Completed>(outcome(profile(server, CUSTOM_DIALECT_OPENAI_CHAT)))
        Unit
    }

    @Test fun responsesFailureEventIsAnError() {
        val failed = """{"type":"response.failed","response":{"error":{"message":"server melted"}}}"""
        Server(listOf(Reply(sse(responsesText, failed)))).use { server ->
            assertResumes(outcome(profile(server, CUSTOM_DIALECT_OPENAI_RESPONSES)), "server melted")
        }
    }

    @Test fun customProfilesNeverSwitchWireAfterAFailure() {
        for (dialect in listOf(CUSTOM_DIALECT_OPENAI_RESPONSES, CUSTOM_DIALECT_GEMINI)) {
            Server(listOf(Reply("""{"error":{"message":"missing"}}""", 404))).use { server ->
                runBlocking { withTimeout(20_000) { runCatching { updates(profile(server, dialect)).toList() } } }
                assertEquals(1, server.requests.size, "$dialect must not try another endpoint: ${server.requests}")
            }
        }
    }

    @Test fun anAcceptedVideoJobIsNeverSubmittedTwice() {
        Server(listOf(Reply("""{"id":"task-1","status":"queued"}"""), Reply("down", 500))).use { server ->
            val video = ProviderProfile(
                "video", "Video", ProviderProtocol.OPENAI_RESPONSES, baseUrl = server.baseUrl, endpointPathOverride = "/v1/videos/generations",
                defaultModel = "fixture-model", thinkingEffort = "none", customModelSpecs = mapOf("fixture-model" to ModelPricingSpec("fixture-model"))
            )
            val updates = runBlocking { withTimeout(40_000) { runCatching { updates(video).toList() } } }
            assertEquals(1, server.requests.count { it.isNotEmpty() }, "The job must be created once")
            assertEquals(6, server.requests.size, "One create and five status checks")
            assertFalse(updates.getOrDefault(emptyList()).any { it is StreamUpdate.Status && "Auto-retrying (" in it.message })
        }
    }

    @Test fun newInstallsKeepNothingOnProviderServers() {
        assertTrue(io.parchment.ai.ui.createDefaultAppState().profiles.none { it.keepsProviderState })
    }

    @Test fun storageIsItsOwnChoiceAndChainingImpliesIt() {
        fun requestFor(change: (ProviderProfile) -> ProviderProfile, dialect: String) =
            Server(listOf(Reply(finishedStreams.getValue(dialect)))).use { server ->
                assertIs<StreamUpdate.Completed>(outcome(change(profile(server, dialect))))
                Json.parseToJsonElement(server.requests.single()).jsonObject
            }
        fun storeOn(dialect: String, change: (ProviderProfile) -> ProviderProfile) = requestFor(change, dialect)["store"]?.jsonPrimitive?.boolean

        assertEquals(false, storeOn(CUSTOM_DIALECT_OPENAI_RESPONSES) { it })
        assertEquals(true, storeOn(CUSTOM_DIALECT_OPENAI_RESPONSES) { it.copy(storeServerSide = true) })
        assertEquals(true, storeOn(CUSTOM_DIALECT_OPENAI_RESPONSES) { it.copy(useStatefulChaining = true) })
        assertEquals(false, storeOn(CUSTOM_DIALECT_GEMINI) { it })
        assertNull(storeOn(CUSTOM_DIALECT_GEMINI) { it.copy(storeServerSide = true) }, "Gemini stores unless told not to")
    }

    @Test fun storedGeminiInteractionsRecoverByPollingWithoutChaining() {
        val poll = """{"id":"i","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"partial"}]}]}"""
        Server(listOf(Reply(sse(geminiStatus)), Reply(poll))).use { server ->
            val completed = assertIs<StreamUpdate.Completed>(outcome(profile(server, CUSTOM_DIALECT_GEMINI).copy(storeServerSide = true)))
            assertEquals("partial", completed.fullContent)
            assertEquals(1, server.requests.count { it.isNotEmpty() }, "Recovery reads the interaction instead of sending it again")
        }
    }

    @Test fun onlyStoredGeminiInteractionsAreCancelled() = runBlocking {
        Server(listOf(Reply("{}"))).use { server ->
            AiProviderEngine.cancelBackgroundInteraction(profile(server, CUSTOM_DIALECT_GEMINI), "i")
            AiProviderEngine.cancelBackgroundInteraction(profile(server, CUSTOM_DIALECT_OPENAI_RESPONSES).copy(storeServerSide = true), "i")
            assertEquals(0, server.requests.size)
            AiProviderEngine.cancelBackgroundInteraction(profile(server, CUSTOM_DIALECT_GEMINI).copy(storeServerSide = true), "i")
            assertEquals(1, server.requests.size)
        }
    }

    @Test fun chatToolCallsWithoutAnIndexStaySeparate() {
        val first = """{"choices":[{"delta":{"tool_calls":[{"id":"a","function":{"name":"fs_list","arguments":"{\"pa"}}]}}]}"""
        val rest = """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"th\":\"x\"}"}}]}}]}"""
        val second = """{"choices":[{"delta":{"tool_calls":[{"id":"b","function":{"name":"fs_read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        val answer = """{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}"""
        Server(listOf(Reply(sse(first, rest, second)), Reply(sse(answer)))).use { server ->
            val completed = runBlocking { withTimeout(20_000) { updates(profile(server, CUSTOM_DIALECT_OPENAI_CHAT)).toList() } }
                .filterIsInstance<StreamUpdate.Completed>().single()
            assertEquals(listOf("a" to "fs_list", "b" to "fs_read"), completed.toolCalls.map { it.id to it.name })
            assertEquals("""{"path":"x"}""", completed.toolCalls.first().argumentsJson)
        }
    }

    @Test fun jsonSplitAcrossDataLinesIsDeliveredWhole() = runBlocking {
        val seen = mutableListOf<String>()
        AiProviderEngine.readSseOrJsonFallback(
            channel = ByteReadChannel("data: {\"a\":{\"b\":1}\ndata: ,\"c\":\"}\"}\n\n".toByteArray()),
            streamFinished = { true },
            onSseEvent = { _, data -> seen += data },
            onPlainJsonFallback = {}
        )
        assertEquals(1, Json.parseToJsonElement(seen.single().replace("\n", "")).jsonObject["a"]!!.jsonObject["b"]!!.jsonPrimitive.int)
    }
}
