package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Providers cache a request's unchanged beginning, so this measures how much of each step's request
 * was already sent by the step before it during a long tool-using run.
 */
class PromptCacheStabilityTest {
    private val steps = 40

    private fun sharedPrefixShare(requests: List<String>): List<Double> = requests.zipWithNext { earlier, later ->
        earlier.commonPrefixWith(later).length.toDouble() / earlier.length
    }

    private fun capturedRun(): List<String> {
        val root = Files.createTempDirectory("parchment-cache-")
        try {
            root.resolve("source.txt").toFile().writeText((1..200).joinToString("\n") { "line $it of a file the agent keeps reading" })
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val count = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    requests.add(exchange.requestBody.bufferedReader().use { it.readText() })
                    val round = count.getAndIncrement()
                    val output = buildJsonArray {
                        if (round < steps) add(buildJsonObject {
                            put("type", "function_call"); put("call_id", "call-$round"); put("name", "fs_read")
                            put("arguments", """{"path":"source.txt","note":"$round"}""")
                        }) else add(buildJsonObject {
                            put("type", "message"); put("role", "assistant")
                            putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", "Done.") }) }
                        })
                    }
                    val body = buildJsonObject { put("id", "r$round"); put("status", "completed"); put("output", output) }.toString().toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
            try {
                val policy = AgentPermissionPolicy(workspacePath = root.toString(), workspaceMode = WorkspaceMode.READ_ONLY, approvalMode = ToolApprovalMode.NEVER)
                val profile = ProviderProfile("fixture", "Fixture", ProviderProtocol.CUSTOM, customDialect = CUSTOM_DIALECT_OPENAI_RESPONSES,
                    baseUrl = "http://127.0.0.1:${server.address.port}", defaultModel = "fixture-model", thinkingEffort = "none",
                    customModelSpecs = mapOf("fixture-model" to ModelPricingSpec("fixture-model")))
                val session = ChatSession("chat", "Fixture", 1L, 1L, profile.id, workspacePath = policy.workspacePath,
                    workspaceMode = policy.workspaceMode, permissionOverrides = policy.asOverrides())
                runBlocking {
                    withTimeout(40_000) {
                        AiProviderEngine.streamGeneration(profile = profile, session = session, historyNodes = listOf(
                            MessageNode("user", MessageRole.USER, listOf(MessageVariant("v", content = "Read the file repeatedly.")))
                        )).toList()
                    }
                }
            } finally { server.stop(0) }
            return requests.toList()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun longToolRunsKeepMostOfEachRequestCacheable() {
        val requests = capturedRun()
        assertEquals(steps + 1, requests.size)
        val shares = sharedPrefixShare(requests)
        val average = shares.average()
        val broken = shares.count { it < 0.5 }
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "TODO.md").isFile }
        File(repo, ".parchment-local/verification/cache-stability.txt").apply { parentFile.mkdirs() }.writeText(
            "steps=$steps average_shared_prefix=${"%.3f".format(average)} steps_under_half=$broken " +
                "total_request_chars=${requests.sumOf { it.length }} average_request_chars=${requests.sumOf { it.length } / requests.size} " +
                "largest_request_chars=${requests.maxOf { it.length }}\n"
        )
        assertTrue(average >= 0.65, "Most of each request must repeat the one before: $average")
    }
}
