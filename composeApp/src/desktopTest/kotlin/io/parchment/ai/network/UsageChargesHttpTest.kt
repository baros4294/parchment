package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class UsageChargesHttpTest {
    private fun withServer(body: String, status: Int = 200, sse: Boolean = false,
        verify: (String, AtomicInteger) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val calls = AtomicInteger()
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            calls.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", if (sse) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { verify("http://127.0.0.1:${server.address.port}", calls) }
        finally { server.stop(0); executor.shutdownNow() }
    }

    private fun generate(baseUrl: String, dialect: String = "openai_chat", path: String = "", modality: String = "text"): List<StreamUpdate> = runBlocking {
        val profile = ProviderProfile("fee-http", "Fees", if (path.isEmpty()) ProviderProtocol.CUSTOM else ProviderProtocol.OPENAI_RESPONSES,
            baseUrl = baseUrl + path, customDialect = dialect, defaultModel = "fee-fixture", useLegacyFallbackEndpoint = false,
            thinkingEffort = "none", inputPricePerM = 1.0, outputPricePerM = 2.0,
            customModelSpecs = mapOf("fee-fixture" to ModelPricingSpec("fee-fixture", outputModalities = listOf(modality))))
        val session = ChatSession("fee-http-chat", "Fees", 1, 1, profile.id)
        val user = MessageNode("user", MessageRole.USER, listOf(MessageVariant("variant", "Reply Done.")))
        withTimeout(15_000) { AiProviderEngine.streamGeneration(profile, session, listOf(user)).toList() }
    }

    @Test fun reportedCostSurvivesRepeatedChatSseUpdatesAndTheCompletedMetricsCopy() {
        val packet = """{"id":"fee","choices":[{"delta":{"content":"Done."},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":80,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0},"cost":0.123,"server_tool_use":{"web_search_requests":2}}}"""
        withServer("data: $packet\n\ndata: ${packet.replace("\"content\":\"Done.\"", "\"content\":\"\"")}\n\ndata: [DONE]\n\n", sse = true) { url, calls ->
            val completed = generate(url).filterIsInstance<StreamUpdate.Completed>().single()
            assertEquals(1, calls.get())
            assertEquals(1, completed.apiUsageRecords.size)
            assertEquals(0.123, completed.metrics.estimatedCostUsd)
            assertEquals(1, completed.metrics.reportedCostRequests)
            assertTrue(completed.metrics.costIsAvailable)
        }
    }

    @Test fun nativeResponseItemsWithoutReportedFeesKeepTheTokenEstimatePartial() {
        val body = """{"id":"fee","status":"completed","output":[{"type":"web_search_call","id":"search","status":"completed"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Done."}]}],"usage":{"input_tokens":100,"output_tokens":80,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}"""
        withServer(body) { url, calls ->
            val completed = generate(url, "openai_responses").filterIsInstance<StreamUpdate.Completed>().single()
            assertEquals(1, calls.get())
            assertTrue(completed.metrics.usageIsComplete)
            assertFalse(completed.metrics.costIsAvailable)
            assertTrue("native web search" in completed.metrics.unpricedCostComponents)
            assertEquals(0.00026, completed.metrics.estimatedCostUsd, 1e-12)
        }
    }

    @Test fun failedDirectMediaRequestsAreRecordedBeforeDispatchWithoutClaimingTheyWereFree() {
        for ((path, modality, wire) in listOf(Triple("/v1/audio/speech", "audio", "openai_audio_speech"),
            Triple("/v1/images/generations", "image", "image_generation"),
            Triple("/v1/videos/generations", "video", "video_generation"))) {
            withServer("""{"error":{"message":"invalid_api_key"}}""", status = 401) { url, calls ->
                val updates = generate(url, path = path, modality = modality)
                val usage = updates.filterIsInstance<StreamUpdate.UsageUpdated>().first()
                assertEquals(1, calls.get(), path)
                assertEquals(wire, usage.record.wireDialect)
                assertFalse(usage.metrics.costIsAvailable)
                assertNull(usage.record.usage.inputTokens)
                assertTrue("output_$modality" in usage.record.usage.charges.observedComponents)
                assertTrue(updates.any { it is StreamUpdate.Error })
            }
        }
    }
}
