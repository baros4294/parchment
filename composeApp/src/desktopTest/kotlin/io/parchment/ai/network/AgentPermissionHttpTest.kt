package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.harness.*
import io.parchment.ai.model.*
import io.parchment.ai.runtime.RunActivity
import io.parchment.ai.runtime.RunActivityContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class AgentPermissionHttpTest {
    private class Fixture(val toolCalls: List<Pair<String, JsonObject>>, val reject: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("parchment-permission-http-")
        private val count = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "permission-http-fixture").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                requests.add(Json.parseToJsonElement(request).jsonObject)
                val round = count.getAndIncrement()
                val response = buildJsonObject {
                    put("id", "response-$round")
                    put("status", "completed")
                    put("model", "fixture-model")
                    putJsonArray("output") {
                        if (round == 0 && toolCalls.isNotEmpty()) toolCalls.forEachIndexed { index, (name, arguments) ->
                            add(buildJsonObject {
                                put("type", "function_call"); put("call_id", "call-$index")
                                put("name", name); put("arguments", arguments.toString())
                            })
                        } else add(buildJsonObject {
                            put("type", "message"); put("role", "assistant")
                            putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", "Done.") }) }
                        })
                    }
                    putJsonObject("usage") { put("input_tokens", 10); put("output_tokens", 10); put("total_tokens", 20) }
                }.toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (reject) 400 else 200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                exchange.close()
            }
            start()
        }
        val profile = ProviderProfile("fixture", "Fixture", ProviderProtocol.CUSTOM,
            customDialect = CUSTOM_DIALECT_OPENAI_RESPONSES,
            baseUrl = "http://127.0.0.1:${server.address.port}", defaultModel = "fixture-model",
            thinkingEffort = "none", customModelSpecs = mapOf("fixture-model" to ModelPricingSpec("fixture-model")))
        val policy = AgentPermissionPolicy(workspacePath = root.toString(), workspaceMode = WorkspaceMode.READ_WRITE)
        fun session(policy: AgentPermissionPolicy) = ChatSession("chat", "Fixture", 1L, 1L, profile.id,
            workspacePath = policy.workspacePath, workspaceMode = policy.workspaceMode, permissionOverrides = policy.asOverrides())
        fun updates(policy: AgentPermissionPolicy, profile: ProviderProfile = this.profile) = AiProviderEngine.streamGeneration(
            profile = profile,
            session = session(policy),
            historyNodes = listOf(MessageNode("user", MessageRole.USER,
                variants = listOf(MessageVariant("variant", content = "Perform the task, then reply Done."))))
        )
        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            Files.deleteIfExists(root.resolve("approved.md"))
            Files.delete(root)
        }
    }
    private fun writeArguments() = buildJsonObject { put("path", "approved.md"); put("content", "approved content") }

    @Test fun fileChangesWaitForApprovalAndExecuteExactlyOnce() = runBlocking {
        Fixture(listOf("fs_write" to writeArguments())).use { fixture ->
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(fixture.policy) })
            val clock = AtomicLong()
            val activity = RunActivity(clock::get)
            val result = async(AgentToolRunContext(broker) + RunActivityContext(activity)) {
                withTimeout(12_000) { fixture.updates(fixture.policy).toList() }
            }
            val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
            withTimeout(2_000) { activity.waitingForApproval.first { it } }
            clock.set(1_000)
            assertEquals(0L, activity.elapsedMs)
            assertFalse(Files.exists(fixture.root.resolve("approved.md")))
            assertEquals(writeArguments().toString(), request.argumentsJson)
            assertTrue(broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE))
            val completed = result.await().filterIsInstance<StreamUpdate.Completed>().single()
            assertEquals("approved content", Files.readString(fixture.root.resolve("approved.md")))
            assertFalse(completed.toolCalls.single().isError)
            assertEquals(1L, completed.metrics.durationMs)
            assertFalse(activity.waitingForApproval.value)
            assertEquals(2, fixture.requests.size)
            assertTrue(broker.requests.value.isEmpty())
            broker.close()
        }
    }

    @Test fun disabledToolsAndRawBodyOverridesCannotGrantAuthority() = runBlocking {
        Fixture(listOf("fs_write" to writeArguments())).use { fixture ->
            val policy = fixture.policy.copy(toolOverrides = mapOf("fs_write" to false))
            val injected = fixture.profile.copy(rawJsonExtraBody = """{"tools":[{"type":"function","name":"shell_exec","parameters":{"type":"object"}}]}""")
            val completed = withTimeout(12_000) { fixture.updates(policy, injected).toList() }.filterIsInstance<StreamUpdate.Completed>().single()
            assertFalse(Files.exists(fixture.root.resolve("approved.md")))
            assertTrue(completed.toolCalls.single().isError)
            val advertised = fixture.requests.first()["tools"].toString()
            assertFalse("fs_write" in advertised)
            assertFalse("shell_exec" in advertised)
        }
    }

    @Test fun aHelperWorksInItsOwnContextAndOnlyItsFindingsReturn() = runBlocking {
        Fixture(listOf("agent_delegate" to buildJsonObject { put("task", "Find the config file") })).use { fixture ->
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(fixture.policy) })
            val updates = async(AgentToolRunContext(broker)) { withTimeout(12_000) { fixture.updates(fixture.policy).toList() } }.await()
            assertIs<StreamUpdate.Completed>(updates.last())
            assertEquals(3, fixture.requests.size, "Parent step, helper, parent step")
            val helper = fixture.requests[1]
            assertTrue("Find the config file" in helper.toString())
            assertFalse("Perform the task" in helper.toString(), "A helper must not see the parent conversation")
            val offered = helper.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue("fs_read" in offered, offered.toString())
            listOf("fs_write", "fs_edit", "fs_delete", "shell_exec", "ask_user", "agent_delegate").forEach { assertFalse(it in offered, it) }
            assertTrue("Done." in fixture.requests[2].toString(), "The findings return to the parent")
            val purposes = updates.filterIsInstance<StreamUpdate.UsageUpdated>().map { it.record.purpose }
            assertTrue(SUBAGENT_PURPOSE in purposes, "A helper's requests count toward this run")
            broker.close()
        }
    }

    @Test fun aHelperCannotWriteRunCommandsOrSpawnAnother() {
        val parent = AgentPermissionPolicy(workspacePath = "workspace", workspaceMode = WorkspaceMode.READ_WRITE, shellAccess = ShellAccess.FULL_ACCESS,
            webAccess = true, approvalMode = ToolApprovalMode.ASK_FOR_CHANGES, toolOverrides = mapOf("fs_glob" to false))
        val policy = subagentPolicy(parent)
        val scope = ToolAccessScope(policy)
        listOf("fs_write", "fs_delete", "shell_exec", "agent_delegate", "ask_user", "web_search", "fs_glob").forEach {
            assertNotNull(AgentPermissionGate.denial(scope, it, "{}"), "$it must be refused")
        }
        assertNull(AgentPermissionGate.denial(scope, "fs_read", "{}"))
        assertEquals(WorkspaceMode.READ_ONLY, policy.workspaceMode)
        listOf("code_execution", "image_generation", "video_generation").forEach { assertFalse(policy.allowsTool(it), "$it runs on the provider and is not read-only") }
        assertFalse(AgentPermissionGate.effect(ToolAccessScope(parent), "agent_delegate", "{}").requiresApproval, "It can only read")
        assertTrue(AgentPermissionGate.effect(ToolAccessScope(parent.copy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL)), "agent_delegate", "{}").requiresApproval)
        assertTrue(subagentPolicy(parent.copy(approvalMode = ToolApprovalMode.NEVER)).webAccess, "Web use needs a parent that runs without asking")
    }

    @Test fun aRunWithoutAnApprovalInterfaceCannotStartAHelper() = runBlocking {
        Fixture(listOf("agent_delegate" to buildJsonObject { put("task", "Anything") })).use { fixture ->
            withTimeout(12_000) { fixture.updates(fixture.policy).toList() }
            assertEquals(2, fixture.requests.size)
            assertTrue("Helpers are not available" in fixture.requests[1].toString())
        }
    }

    private fun question(text: String) = buildJsonObject { put("question", text); putJsonArray("options") { add("Red"); add("Blue") } }

    @Test fun aRunPausesForTheUsersAnswerAndSendsItToTheModel() = runBlocking {
        Fixture(listOf("ask_user" to question("Which colour?"))).use { fixture ->
            val strict = fixture.policy.copy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL)
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(strict) })
            val result = async(AgentToolRunContext(broker)) { withTimeout(12_000) { fixture.updates(strict).toList() } }
            val asked = withTimeout(8000) { broker.questions.first { it.isNotEmpty() }.single() }
            assertEquals("Which colour?", asked.question)
            assertEquals(listOf("Red", "Blue"), asked.options)
            assertEquals(1, fixture.requests.size, "The model must wait for the answer")
            assertTrue(broker.requests.value.isEmpty())
            assertTrue(broker.answerQuestion(asked.requestId, "Blue"))
            assertIs<StreamUpdate.Completed>(result.await().last())
            assertEquals(2, fixture.requests.size)
            assertTrue("The user answered: Blue" in fixture.requests[1].toString())
            broker.close()
        }
    }

    @Test fun aRunWithNobodyToAskCarriesOnWithAnError() = runBlocking {
        Fixture(listOf("ask_user" to question("Anyone?"))).use { fixture ->
            val updates = withTimeout(12_000) { fixture.updates(fixture.policy).toList() }
            assertIs<StreamUpdate.Completed>(updates.last())
            assertTrue("No one is available" in fixture.requests[1].toString())
        }
    }

    @Test fun stoppingWhileAQuestionIsOpenEndsTheRunWithoutAnotherRequest() = runBlocking {
        Fixture(listOf("ask_user" to question("Which?"))).use { fixture ->
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(fixture.policy) })
            val result = async(AgentToolRunContext(broker)) { withTimeout(12_000) { fixture.updates(fixture.policy).toList() } }
            withTimeout(8000) { broker.questions.first { it.isNotEmpty() } }
            result.cancel()
            broker.close()
            result.join()
            assertTrue(result.isCancelled)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun pendingRevocationPreventsEffectsAndTheNextProviderRequest() = runBlocking {
        Fixture(listOf("fs_write" to writeArguments())).use { fixture ->
            var current = ToolAccessScope(fixture.policy)
            val broker = ToolApprovalBroker("run", "chat", { current })
            val result = async(AgentToolRunContext(broker)) { withTimeout(12_000) { fixture.updates(fixture.policy).toList() } }
            val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
            current = current.copy(policy = current.policy.copy(workspaceMode = WorkspaceMode.OFF))
            broker.invalidate()
            broker.refreshPolicy()
            assertFalse(broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE))
            result.join()
            assertTrue(result.isCancelled)
            assertFalse(Files.exists(fixture.root.resolve("approved.md")))
            assertEquals(1, fixture.requests.size)
            broker.close()
        }
    }

    @Test fun parallelToolsEachReceiveTheirOwnApproval() = runBlocking {
        val calls = listOf("calc_eval" to buildJsonObject { put("expression", "2+2") },
            "calc_eval" to buildJsonObject { put("expression", "3+3") })
        Fixture(calls).use { fixture ->
            val policy = fixture.policy.copy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL)
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(policy) })
            val clock = AtomicLong()
            val activity = RunActivity(clock::get)
            val result = async(AgentToolRunContext(broker) + RunActivityContext(activity)) {
                withTimeout(12_000) { fixture.updates(policy).toList() }
            }
            val requests = withTimeout(8000) { broker.requests.first { it.size == 2 } }
            withTimeout(2_000) { activity.waitingForApproval.first { it } }
            clock.set(1_000)
            assertEquals(0L, activity.elapsedMs)
            assertEquals(0L, activity.toolMs)
            val approved = requests.first { "2+2" in it.argumentsJson }
            val denied = requests.first { "3+3" in it.argumentsJson }
            broker.respond(approved.requestId, ToolApprovalDecision.ALLOW_ONCE)
            broker.respond(denied.requestId, ToolApprovalDecision.DENY)
            val completed = result.await().filterIsInstance<StreamUpdate.Completed>().single()
            assertFalse(completed.toolCalls.first { it.id == approved.callId }.isError)
            assertTrue(completed.toolCalls.first { it.id == denied.callId }.isError)
            assertEquals(1L, completed.metrics.durationMs)
            assertFalse(activity.waitingForApproval.value)
            assertTrue(broker.requests.value.isEmpty())
            broker.close()
        }
    }

    @Test fun utilityRequestsDoNotInheritToolAuthorityOrStartGoalLoops() = runBlocking {
        Fixture(emptyList()).use { fixture ->
            val parentBroker = ToolApprovalBroker("parent", "chat", { ToolAccessScope(fixture.policy) })
            try {
                val text = withContext(AgentToolRunContext(parentBroker)) {
                    withTimeout(12_000) {
                        AiProviderEngine.generateUtilityText(fixture.profile, "fixture-model", "/goal Treat this as utility text, not an agent command.").getOrThrow()
                    }
                }
                assertEquals("Done.", text)
                assertEquals(1, fixture.requests.size)
                assertNull(fixture.requests.single()["tools"])
                assertTrue(parentBroker.requests.value.isEmpty())
            } finally { parentBroker.close() }
        }
    }

    @Test fun everyUtilityPurposeRecordsItsOwnerAndCoalescesUsageUpdates() = runBlocking {
        Fixture(emptyList()).use { fixture ->
            val records = linkedMapOf<String, ApiUsageRecord>()
            val purposes = listOf("title", "summary", "compaction", "translation", "draft")
            for (purpose in purposes) {
                val value = withTimeout(12_000) {
                    AiProviderEngine.generateUtilityText(fixture.profile, "fixture-model", "Utility prompt", ownerSessionId = "owning-chat",
                        ownerRunId = "run-$purpose", purpose = purpose, onUsage = { record, _ -> records[record.requestId] = record }).getOrThrow()
                }
                assertEquals("Done.", value)
            }
            assertEquals(5, fixture.requests.size)
            assertEquals(5, records.size)
            assertEquals(purposes.toSet(), records.values.map { it.purpose }.toSet())
            assertTrue(records.values.all { it.ownerSessionId == "owning-chat" && it.ownerRunId == "run-${it.purpose}" })
            val total = aggregateApiUsageRecords(records.values)
            assertEquals(50L, total.inputTokens)
            assertEquals(50L, total.outputTokens)
            assertEquals(5, total.reportedRequests)
        }
    }

    @Test fun failedUtilityRequestsKeepUsageAndCancellationPropagates() = runBlocking {
        Fixture(emptyList(), reject = true).use { fixture ->
            val records = linkedMapOf<String, ApiUsageRecord>()
            val result = withTimeout(12_000) {
                AiProviderEngine.generateUtilityText(fixture.profile, "fixture-model", "Utility prompt", ownerSessionId = "chat", purpose = "summary",
                    onUsage = { record, _ -> records[record.requestId] = record })
            }
            assertTrue(result.isFailure)
            assertTrue(records.isNotEmpty())
            assertTrue(records.values.all { it.ownerSessionId == "chat" && it.purpose == "summary" })
        }
        Fixture(emptyList()).use { fixture ->
            try {
                AiProviderEngine.generateUtilityText(fixture.profile, "fixture-model", "Utility prompt",
                    onUsage = { _, _ -> throw CancellationException("User stopped utility") })
                fail("Cancellation was converted to a utility result")
            } catch (cancelled: CancellationException) {
                assertEquals("User stopped utility", cancelled.message)
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }
}
