package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.harness.*
import io.parchment.ai.model.*
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Whole user journeys through the real engine, against a scripted provider. */
class WorkflowEndToEndTest {
    private fun call(id: String, name: String, arguments: JsonObject) = buildJsonObject {
        put("type", "function_call"); put("call_id", id); put("name", name); put("arguments", arguments.toString())
    }
    private fun reply(text: String) = buildJsonObject {
        put("type", "message"); put("role", "assistant")
        putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }
    }

    private class Provider(val steps: List<List<JsonObject>>) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private val round = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += exchange.requestBody.bufferedReader().readText()
                val output = steps.getOrElse(round.getAndIncrement()) { steps.last() }
                val body = buildJsonObject {
                    put("id", "r"); put("status", "completed"); put("model", "fixture-model")
                    put("output", JsonArray(output))
                    putJsonObject("usage") { put("input_tokens", 10); put("output_tokens", 10); put("total_tokens", 20) }
                }.toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val profile = ProviderProfile("fixture", "Fixture", ProviderProtocol.CUSTOM, customDialect = CUSTOM_DIALECT_OPENAI_RESPONSES,
            baseUrl = "http://127.0.0.1:${server.address.port}", defaultModel = "fixture-model", thinkingEffort = "none",
            customModelSpecs = mapOf("fixture-model" to ModelPricingSpec("fixture-model")))
        override fun close() = server.stop(0)
    }

    private fun user(id: String, text: String) = MessageNode(id, MessageRole.USER, listOf(MessageVariant("$id-v", content = text)))

    private fun run(provider: Provider, session: ChatSession, history: List<MessageNode>): List<StreamUpdate> = runBlocking {
        withTimeout(20_000) { AiProviderEngine.streamGeneration(provider.profile, session, history).toList() }
    }

    @Test fun roleplayFromAnImportedCardSendsThePersonaAndOnlyTheLoreThatApplies() {
        val card = """{"spec":"chara_card_v3","spec_version":"3.0","data":{
            "name":"Mira","description":"{{char}} guards the archive for {{user}}.","first_mes":"Halt.","mes_example":"Mira: Who goes?",
            "post_history_instructions":"Stay in the present tense.","future_field":1,
            "character_book":{"entries":[
              {"name":"Vault","content":"The vault hides a silver key.","keys":["vault"],"enabled":true,"insertion_order":1},
              {"name":"Moon","content":"The moon festival is unrelated.","keys":["festival"],"enabled":true,"insertion_order":2},
              {"name":"Rule","content":"Archive doors lock at dusk.","keys":[],"constant":true,"enabled":true,"insertion_order":3}
            ]}}}"""
        val imported = assertIs<CardAndPromptCodec.ImportResult.CardImported>(CardAndPromptCodec.importFromBytes("mira.json", card.encodeToByteArray()).getOrThrow()).card
        Provider(listOf(listOf(reply("She steps aside.")))).use { provider ->
            val session = ChatSession("chat", "Roleplay", 1L, 1L, provider.profile.id, activeCard = imported)
            val greeting = MessageNode("greeting", MessageRole.ASSISTANT, listOf(MessageVariant("greeting-v", content = imported.firstMessage)))
            val updates = run(provider, session, listOf(greeting, user("u", "I ask about the vault.")))
            assertEquals("She steps aside.", assertIs<StreamUpdate.Completed>(updates.last()).fullContent)
            val request = provider.requests.single()
            listOf("guards the archive", "Mira: Who goes?", "Stay in the present tense.", "The vault hides a silver key.", "Archive doors lock at dusk.", "Halt.").forEach {
                assertTrue(it in request, "Missing: $it")
            }
            assertFalse("{{char}}" in request, "Card macros are filled in")
            assertFalse("The moon festival" in request, "Lore whose keyword did not come up stays out")
        }
    }

    @Test fun dailyChatSendsTheChosenVersionAndLeavesOutExcludedMessages() {
        Provider(listOf(listOf(reply("Noted.")))).use { provider ->
            val regenerated = MessageNode("a1", MessageRole.ASSISTANT, listOf(
                MessageVariant("old", content = "Discarded first attempt"),
                MessageVariant("new", content = "Chosen second attempt")
            ), activeVariantIndex = 1)
            val excluded = user("hidden", "Private aside").copy(includedInContext = false)
            val session = ChatSession("chat", "Daily", 1L, 1L, provider.profile.id, modelOverride = "chosen-model")
            assertIs<StreamUpdate.Completed>(run(provider, session, listOf(user("u1", "Hello"), regenerated, excluded, user("u2", "Next"))).last())
            val request = provider.requests.single()
            assertTrue("Chosen second attempt" in request)
            assertFalse("Discarded first attempt" in request)
            assertFalse("Private aside" in request)
            assertEquals("chosen-model", Json.parseToJsonElement(request).jsonObject.getValue("model").jsonPrimitive.content, "An explicit model choice is kept")
        }
    }

    @Test fun anAgentGoalPlansWritesARealFileAndStopsWhenComplete() {
        val root = Files.createTempDirectory("parchment-workflow-")
        try {
            val todos = buildJsonObject {
                put("goal", "Write the note")
                putJsonArray("todos") { add(buildJsonObject { put("id", "1"); put("content", "Write it"); put("status", "in_progress") }) }
            }
            val script = listOf(
                listOf(call("c1", "todo_write", todos)),
                listOf(reply("Writing the note now."), call("c2", "fs_write", buildJsonObject { put("path", "note.md"); put("content", "Finished") })),
                listOf(call("c3", "fs_read", buildJsonObject { put("path", "note.md") })),
                listOf(call("c4", "goal_complete", buildJsonObject { put("summary", "Wrote the note"); put("verification", "Read it back") })),
                listOf(reply("All done."))
            )
            Provider(script).use { provider ->
                val policy = AgentPermissionPolicy(workspacePath = root.toString(), workspaceMode = WorkspaceMode.READ_WRITE, approvalMode = ToolApprovalMode.NEVER)
                val session = ChatSession("chat", "Agent", 1L, 1L, provider.profile.id, workspacePath = policy.workspacePath,
                    workspaceMode = policy.workspaceMode, permissionOverrides = policy.asOverrides())
                val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(policy, goalModeActive = true) })
                val updates = runBlocking {
                    withContext(AgentToolRunContext(broker)) {
                        withTimeout(20_000) { AiProviderEngine.streamGeneration(provider.profile, session, listOf(user("u", "/goal Write the note"))).toList() }
                    }
                }
                val completed = assertIs<StreamUpdate.Completed>(updates.last())
                assertEquals("Finished", root.resolve("note.md").toFile().readText())
                assertEquals(5, provider.requests.size, "No extra nudge after the goal is complete")
                assertTrue("Finished" in provider.requests[3], "The file's contents reach the model")
                assertTrue("All done." in completed.fullContent)
                assertTrue(updates.filterIsInstance<StreamUpdate.GoalProgress>().any { it.goalTracker.isCompleted })
                runBlocking { broker.close() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
