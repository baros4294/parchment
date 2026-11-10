package io.parchment.ai.harness

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.model.*
import io.parchment.ai.network.*
import io.parchment.ai.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.awt.event.KeyEvent
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ComputerUseTest {
    private class FakeControl(override val isAvailable: Boolean = true) : ComputerControl {
        val actions = Collections.synchronizedList(mutableListOf<ComputerAction>())
        var captures = 0
        override fun screenshot(): Result<ComputerScreenshot> {
            captures++
            return Result.success(ComputerScreenshot("SCREENBYTES", 1280, 720))
        }
        override fun act(action: ComputerAction): Result<String> {
            actions += action
            return Result.success("Done: ${action.action}.")
        }
    }

    private val realControl = ComputerUse.control
    private val fake = FakeControl()

    @BeforeTest fun useFake() { ComputerUse.control = fake }
    @AfterTest fun restore() { ComputerUse.control = realControl }

    private val allowed = AgentPermissionPolicy(approvalMode = ToolApprovalMode.NEVER, computerAccess = true)
    private val click = """{"action":"click","x":10,"y":20}"""

    @Test fun itIsOffUntilChosen() {
        val off = ToolAccessScope(AgentPermissionPolicy(approvalMode = ToolApprovalMode.NEVER))
        assertTrue("turned off" in assertNotNull(AgentPermissionGate.denial(off, ComputerUse.TOOL_NAME, click)))
        assertFalse(WorkspaceHarness.activeToolsForMode(WorkspaceMode.OFF, "", policy = off.policy).any { it.name == ComputerUse.TOOL_NAME })
        assertNull(AgentPermissionGate.denial(ToolAccessScope(allowed), ComputerUse.TOOL_NAME, click))
        assertTrue(WorkspaceHarness.activeToolsForMode(WorkspaceMode.OFF, "", policy = allowed).any { it.name == ComputerUse.TOOL_NAME })
    }

    @Test fun everyActionAsksEvenWhenToolsRunWithoutAsking() {
        ToolApprovalMode.entries.forEach { mode ->
            assertTrue(AgentPermissionGate.effect(ToolAccessScope(allowed.copy(approvalMode = mode)), ComputerUse.TOOL_NAME, click).requiresApproval, mode.name)
        }
    }

    @Test fun importsAndHelpersNeverHaveIt() {
        val imported = ChatSession("c", "C", 1L, 1L, "p", permissionOverrides = allowed.asOverrides()).withImportedPermissions()
        assertFalse(AgentPermissionResolver.resolve(imported).computerAccess)
        assertFalse(AssistantAgent("a", "A", permissionDefaults = allowed).withImportedPermissions().permissionDefaults!!.computerAccess)
        assertNotNull(AgentPermissionGate.denial(ToolAccessScope(subagentPolicy(allowed)), ComputerUse.TOOL_NAME, click))
    }

    @Test fun badRequestsNeverReachTheComputer() {
        listOf("""{"action":"format_disk"}""", """{"action":"click"}""", """{"action":"type"}""", """{"action":"key"}""", "[1]", """{"action":"type","text":"${"x".repeat(4001)}"}""").forEach {
            assertTrue(ComputerUse.perform(it) { false }.isError, it)
        }
        assertTrue(fake.actions.isEmpty())
        assertEquals(0, fake.captures)
    }

    @Test fun anActionIsFollowedByAFreshScreenshot() {
        val outcome = ComputerUse.perform(click) { false }
        assertFalse(outcome.isError)
        assertEquals("click", fake.actions.single().action)
        assertEquals("SCREENBYTES", outcome.screenshot?.jpegBase64)
    }

    @Test fun aStoppedRunTakesNoFurtherScreenshot() {
        assertNull(ComputerUse.perform(click) { true }.screenshot)
        assertEquals(0, fake.captures)
    }

    @Test fun unavailableControlSaysHowToEnableIt() {
        ComputerUse.control = FakeControl(isAvailable = false)
        assertTrue("Accessibility" in ComputerUse.perform("""{"action":"screenshot"}""") { false }.text)
    }

    @Test fun keysAndPointsMapSafely() {
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_A), parseKeyCombination("Ctrl + Shift + a"))
        assertEquals(listOf(KeyEvent.VK_F5), parseKeyCombination("f5"))
        assertNull(parseKeyCombination("ctrl+nonsense"))
        assertEquals(1920 to 1080, mapScreenshotPoint(1280, 720, 1280.0 / 1920.0, 1921, 1081))
        assertNull(mapScreenshotPoint(5000, 10, 0.5, 1920, 1080), "Outside the screen")
        assertNull(mapScreenshotPoint(-1, 10, 0.5, 1920, 1080))
        assertNull(mapScreenshotPoint(1, 1, 0.0, 1920, 1080), "No screenshot yet")
    }

    private val shot = ToolCallRecord("c", ComputerUse.TOOL_NAME, "{}", resultText = "Done.", screenshotJpegBase64 = "BYTES")

    @Test fun eachFormatCarriesTheScreenshotInItsOwnShape() {
        assertTrue("data:image/jpeg;base64,BYTES" in responsesScreenshotMessage(shot).toString())
        assertEquals("image_url", chatScreenshotMessage(shot)["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        val anthropic = anthropicToolResultContent(shot).jsonArray
        assertEquals("BYTES", anthropic[1].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("plain"), anthropicToolResultContent(shot.copy(resultText = "plain", screenshotJpegBase64 = "")))
        assertEquals("BYTES", geminiScreenshotPart(shot)["inline_data"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("image", interactionsScreenshotStep(shot)["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun onlyTheNewestScreenshotStaysInALongRun() {
        val text = buildJsonObject { put("role", "user"); put("content", "Look at screenshot.png") }
        val entries = mutableListOf(text, responsesScreenshotMessage(shot), chatScreenshotMessage(shot), interactionsScreenshotStep(shot))
        entries.dropOlderScreenshotEntries()
        assertEquals(listOf(text), entries)

        val anthropic = mutableListOf(buildJsonObject {
            put("role", "user")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "tool_result"); put("tool_use_id", "c"); put("content", anthropicToolResultContent(shot)) })))
        })
        anthropic.dropOlderAnthropicScreenshots()
        assertFalse("BYTES" in anthropic.single().toString())
        assertTrue("Done." in anthropic.single().toString(), "The text result stays")

        val gemini = mutableListOf(
            buildJsonObject { put("role", "user"); put("parts", JsonArray(listOf(buildJsonObject { putJsonObject("functionResponse") { put("name", "x") } }, geminiScreenshotPart(shot)))) },
            buildJsonObject { put("role", "user"); put("parts", JsonArray(listOf(geminiScreenshotPart(shot)))) }
        )
        gemini.dropOlderGeminiScreenshots()
        assertFalse("BYTES" in gemini[0].toString())
        assertTrue("BYTES" in gemini[1].toString(), "A person's own attached image is untouched")
    }

    @Test fun aComputerActionCannotBeApprovedForTheWholeRun() = runBlocking<Unit> {
        val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(allowed) })
        val first = async { broker.authorize("a", ComputerUse.TOOL_NAME, click) }
        val request = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
        broker.respond(request.requestId, ToolApprovalDecision.ALLOW_EXACT_ACTION_FOR_RUN)
        assertIs<ToolAuthorization.Allowed>(first.await())
        val second = async { broker.authorize("b", ComputerUse.TOOL_NAME, click) }
        val again = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
        assertEquals("b", again.callId, "The same click must ask again")
        broker.respond(again.requestId, ToolApprovalDecision.DENY)
        assertIs<ToolAuthorization.Denied>(second.await())
        broker.close()
    }

    @Test fun aScreenshotIsNeverSavedWithTheChat() {
        assertFalse("BYTES" in io.parchment.ai.ui.compactStateJson.encodeToString(ToolCallRecord.serializer(), shot))
    }

    private class Provider(val toolArguments: String) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private val round = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests += exchange.requestBody.bufferedReader().readText()
                val first = round.getAndIncrement() == 0
                val body = buildJsonObject {
                    put("id", "r"); put("status", "completed"); put("model", "fixture-model")
                    putJsonArray("output") {
                        add(if (first) buildJsonObject { put("type", "function_call"); put("call_id", "c1"); put("name", ComputerUse.TOOL_NAME); put("arguments", toolArguments) }
                        else buildJsonObject { put("type", "message"); put("role", "assistant"); putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", "Done.") } } })
                    }
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
        fun run(policy: AgentPermissionPolicy) = AiProviderEngine.streamGeneration(profile = profile,
            session = ChatSession("chat", "Fixture", 1L, 1L, profile.id, permissionOverrides = policy.asOverrides()),
            historyNodes = listOf(MessageNode("u", MessageRole.USER, listOf(MessageVariant("v", content = "Go")))))
    }

    @Test fun anApprovedActionRunsAndTheModelSeesTheScreen() = runBlocking<Unit> {
        Provider(click).use { provider ->
            // The app's approval scope is built apart from the tool loop's and can differ in details.
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(allowed, includeLocalWebSearchTool = true) })
            val run = async(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(allowed).toList() } }
            val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
            assertTrue(fake.actions.isEmpty(), "Nothing happens before approval")
            assertTrue(broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE))
            assertIs<StreamUpdate.Completed>(run.await().last())
            assertEquals(1, fake.actions.size)
            assertTrue("base64,SCREENBYTES" in provider.requests[1], provider.requests[1])
            val events = run.await().filterIsInstance<StreamUpdate.ToolCallEvent>()
            assertTrue(events.isNotEmpty() && events.all { it.toolCall.screenshotJpegBase64.isEmpty() }, "Screens stay out of the chat's state")
            broker.close()
        }
    }

    @Test fun aDeniedActionNeverRuns() = runBlocking<Unit> {
        Provider(click).use { provider ->
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(allowed) })
            val run = async(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(allowed).toList() } }
            val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
            broker.respond(request.requestId, ToolApprovalDecision.DENY)
            run.await()
            assertTrue(fake.actions.isEmpty())
            assertEquals(0, fake.captures)
            broker.close()
        }
    }

    @Test fun stoppingAtTheApprovalCardNeverRunsTheAction() = runBlocking<Unit> {
        Provider(click).use { provider ->
            val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(allowed) })
            val run = async(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(allowed).toList() } }
            val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
            broker.respond(request.requestId, ToolApprovalDecision.STOP)
            run.join()
            assertTrue(fake.actions.isEmpty())
            assertEquals(1, provider.requests.size)
        }
    }
}
