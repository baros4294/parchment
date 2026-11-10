package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.harness.*
import io.parchment.ai.model.*
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Runs the client against real servers: an HTTP one and a separate operating-system process. */
class McpEndToEndTest {
    private class HttpMcp(val stateless: Boolean, val streamed: Boolean = false) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Pair<JsonObject, Map<String, String>>>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                requests += message to headers
                val method = message.getValue("method").jsonPrimitive.content
                val id = message["id"]
                val result: JsonElement? = when {
                    method == "initialize" && stateless -> null
                    method == "initialize" -> buildJsonObject { put("protocolVersion", "2025-06-18"); put("capabilities", JsonObject(emptyMap())); put("serverInfo", buildJsonObject { put("name", "fixture") }) }
                    method == "tools/list" && message["params"]?.jsonObject?.get("cursor") == null -> buildJsonObject {
                        putJsonArray("tools") { add(tool("first one")) }
                        put("nextCursor", "page-2")
                    }
                    method == "tools/list" -> buildJsonObject { putJsonArray("tools") { add(tool("second")) } }
                    method == "tools/call" -> buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "http says hi") }) } }
                    else -> null
                }
                val isNotification = id == null
                val reply = when {
                    isNotification -> ""
                    result != null -> buildJsonObject { put("jsonrpc", "2.0"); put("id", id!!); put("result", result) }.toString()
                    else -> buildJsonObject { put("jsonrpc", "2.0"); put("id", id!!); put("error", buildJsonObject { put("code", -32601); put("message", "Method not found") }) }.toString()
                }
                if (!stateless) exchange.responseHeaders.set("Mcp-Session-Id", "session-1")
                val sse = streamed && !isNotification
                exchange.responseHeaders.set("Content-Type", if (sse) "text/event-stream" else "application/json")
                val body = (if (sse) "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: $reply\n\n" else reply).toByteArray()
                exchange.sendResponseHeaders(if (isNotification) 202 else 200, if (isNotification) -1 else body.size.toLong())
                if (!isNotification) exchange.responseBody.write(body)
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/mcp"
        override fun close() = server.stop(0)
        private fun tool(name: String) = buildJsonObject { put("name", name); put("description", "A tool"); put("inputSchema", buildJsonObject { put("type", "object") }) }
    }

    private val pythonServer = """
import json, sys
calls = 0
for line in sys.stdin:
    msg = json.loads(line)
    print("log line that is not protocol", file=sys.stderr, flush=True)
    method, mid = msg.get("method"), msg.get("id")
    if mid is None:
        continue
    if method == "initialize":
        result = {"protocolVersion": msg["params"]["protocolVersion"], "capabilities": {"tools": {}}, "serverInfo": {"name": "py"}}
    elif method == "tools/list":
        result = {"tools": [{"name": "echo", "description": "Echo", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}}]}
    elif method == "tools/call":
        calls += 1
        result = {"content": [{"type": "text", "text": "echo:%s:%d" % (msg["params"]["arguments"].get("text"), calls)}]}
    else:
        print(json.dumps({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "nope"}}), flush=True)
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": mid, "result": result}), flush=True)
""".trimIndent()

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private suspend fun <T> connected(servers: List<McpServerConfig>, block: suspend () -> T): T {
        try {
            McpRegistry.sync(servers)
            return block()
        } finally {
            withContext(NonCancellable) { McpRegistry.sync(emptyList()) }
        }
    }

    @Test fun handshakeServersGetSessionsPagingAndEventStreams() = runBlocking<Unit> {
        HttpMcp(stateless = false, streamed = true).use { server ->
            connected(listOf(McpServerConfig("a", "Web App", url = server.url, bearerToken = "secret", enabled = true))) {
                assertEquals(McpServerState.READY, McpRegistry.statuses.value.getValue("a").state, McpRegistry.statuses.value.toString())
                val names = McpRegistry.definitions.value.map { it.name }
                assertEquals(listOf("mcp__Web_App__first_one", "mcp__Web_App__second"), names)
                val result = McpRegistry.call(names[0], "{}")
                assertEquals(McpToolResult("http says hi", false), result)
            }
            val initialized = server.requests.first { it.first["method"]?.jsonPrimitive?.content == "notifications/initialized" }.second
            assertEquals("session-1", initialized["mcp-session-id"])
            assertEquals("2025-06-18", initialized["mcp-protocol-version"])
            assertEquals("Bearer secret", initialized["authorization"])
            val call = server.requests.first { it.first["method"]?.jsonPrimitive?.content == "tools/call" }
            assertEquals("first one", call.second["mcp-name"], "The server's own tool name is what it receives")
            assertEquals("first one", call.first["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun serversWithoutTheHandshakeGetTheVersionOnEveryRequest() = runBlocking<Unit> {
        HttpMcp(stateless = true).use { server ->
            connected(listOf(McpServerConfig("a", "New", url = server.url, enabled = true))) {
                assertEquals(McpServerState.READY, McpRegistry.statuses.value.getValue("a").state, McpRegistry.statuses.value.toString())
                assertFalse(McpRegistry.call("mcp__New__second", "{}").isError)
            }
            assertTrue(server.requests.none { it.first["method"]?.jsonPrimitive?.content == "notifications/initialized" })
            val call = server.requests.first { it.first["method"]?.jsonPrimitive?.content == "tools/call" }
            assertEquals(MCP_STATELESS_VERSION, call.second["mcp-protocol-version"])
            assertEquals(MCP_STATELESS_VERSION, call.first["params"]!!.jsonObject["_meta"]!!.jsonObject["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test fun aTokenIsNeverSentOverPlainHttpToAnotherMachine() = runBlocking<Unit> {
        connected(listOf(McpServerConfig("a", "Far", url = "http://example.invalid/mcp", bearerToken = "secret", enabled = true))) {
            val status = McpRegistry.statuses.value.getValue("a")
            assertEquals(McpServerState.FAILED, status.state)
            assertTrue("https" in status.message, status.message)
            assertTrue(McpRegistry.definitions.value.isEmpty())
        }
    }

    @Test fun aRealLocalProcessServesToolsUntilItIsRemoved() = runBlocking<Unit> {
        if (!python()) return@runBlocking
        val script = Files.createTempFile("parchment-mcp-", ".py")
        try {
            Files.writeString(script, pythonServer)
            val config = McpServerConfig("py", "Local", command = "python $script", enabled = true)
            connected(listOf(config)) {
                assertEquals(McpServerState.READY, McpRegistry.statuses.value.getValue("py").state, McpRegistry.statuses.value.toString())
                assertEquals(McpToolResult("echo:hi:1", false), McpRegistry.call("mcp__Local__echo", """{"text":"hi"}"""))
                assertEquals(McpToolResult("echo:yo:2", false), McpRegistry.call("mcp__Local__echo", """{"text":"yo"}"""), "One process keeps serving")
                assertTrue(McpRegistry.call("mcp__Local__echo", "[1]").isError)
                McpRegistry.sync(emptyList())
                assertTrue(McpRegistry.definitions.value.isEmpty())
                assertTrue(McpRegistry.call("mcp__Local__echo", "{}").isError, "A removed server cannot be called")
            }
        } finally { Files.deleteIfExists(script) }
    }

    @Test fun aServerThatExitsFailsItsCallsInsteadOfHanging() = runBlocking<Unit> {
        if (!python()) return@runBlocking
        val script = Files.createTempFile("parchment-mcp-exit-", ".py")
        try {
            Files.writeString(script, pythonServer.replace("""        calls += 1""", """        sys.exit(3)"""))
            connected(listOf(McpServerConfig("py", "Dying", command = "python $script", enabled = true))) {
                val result = withTimeout(15_000) { McpRegistry.call("mcp__Dying__echo", "{}") }
                assertTrue(result.isError)
                assertTrue(McpRegistry.definitions.value.isEmpty(), "A stopped server's tools are no longer offered")
                assertEquals(McpServerState.FAILED, McpRegistry.statuses.value.getValue("py").state)
            }
        } finally { Files.deleteIfExists(script) }
    }

    private class Provider(val toolName: String, val arguments: String = """{"text":"from model"}""") : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        private val count = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { exchange ->
                requests += Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                val first = count.getAndIncrement() == 0
                val body = buildJsonObject {
                    put("id", "r"); put("status", "completed"); put("model", "fixture-model")
                    putJsonArray("output") {
                        add(if (first) buildJsonObject { put("type", "function_call"); put("call_id", "c1"); put("name", toolName); put("arguments", arguments) }
                        else buildJsonObject { put("type", "message"); put("role", "assistant"); putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", "Done.") }) } })
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
            session = ChatSession("chat", "Fixture", 1L, 1L, profile.id, workspacePath = policy.workspacePath, workspaceMode = policy.workspaceMode, permissionOverrides = policy.asOverrides()),
            historyNodes = listOf(MessageNode("user", MessageRole.USER, listOf(MessageVariant("v", content = "Go")))))
        fun advertised(index: Int) = requests[index].getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
    }

    private val noWorkspace = AgentPermissionPolicy(workspacePath = "", workspaceMode = WorkspaceMode.OFF)

    @Test fun aModelCallRunsOnlyAfterApprovalAndItsResultReturns() = runBlocking<Unit> {
        HttpMcp(stateless = false).use { mcp ->
            connected(listOf(McpServerConfig("a", "App", url = mcp.url, enabled = true))) {
                Provider("mcp__App__second").use { provider ->
                    val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(noWorkspace) })
                    val result = async(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(noWorkspace).toList() } }
                    val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
                    assertEquals("mcp__App__second", request.toolName)
                    assertTrue(mcp.requests.none { it.first["method"]?.jsonPrimitive?.content == "tools/call" }, "Nothing runs before approval")
                    assertTrue(broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE))
                    assertIs<StreamUpdate.Completed>(result.await().last())
                    assertTrue("http says hi" in provider.requests[1].toString())
                    broker.close()
                }
            }
        }
    }

    @Test fun aDeniedCallNeverReachesTheServer() = runBlocking<Unit> {
        HttpMcp(stateless = false).use { mcp ->
            connected(listOf(McpServerConfig("a", "App", url = mcp.url, enabled = true))) {
                Provider("mcp__App__second").use { provider ->
                    val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(noWorkspace) })
                    val result = async(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(noWorkspace).toList() } }
                    val request = withTimeout(8000) { broker.requests.first { it.isNotEmpty() }.single() }
                    assertTrue(broker.respond(request.requestId, ToolApprovalDecision.DENY))
                    result.await()
                    assertTrue(mcp.requests.none { it.first["method"]?.jsonPrimitive?.content == "tools/call" })
                    assertTrue("denied" in provider.requests[1].toString())
                    broker.close()
                }
            }
        }
    }

    @Test fun aDisabledToolIsNotOfferedAndCannotBeCalled() = runBlocking<Unit> {
        HttpMcp(stateless = false).use { mcp ->
            connected(listOf(McpServerConfig("a", "App", url = mcp.url, enabled = true))) {
                val blocked = noWorkspace.copy(toolOverrides = mapOf("mcp__App__second" to false))
                Provider("mcp__App__second").use { provider ->
                    val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(blocked) })
                    withContext(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(blocked).toList() } }
                    assertTrue("mcp__App__first_one" in provider.advertised(0))
                    assertFalse("mcp__App__second" in provider.advertised(0))
                    assertTrue(broker.requests.value.isEmpty())
                    assertTrue(mcp.requests.none { it.first["method"]?.jsonPrimitive?.content == "tools/call" })
                    assertTrue("disabled" in provider.requests[1].toString())
                    broker.close()
                }
            }
        }
    }

    @Test fun anImportCannotBringLocalCommandsOrLiveConnections() {
        val imported = AppStateSnapshot(preferences = PreferencesConfig(mcpServers = listOf(
            McpServerConfig("local", "Evil", command = "rm -rf /", enabled = true),
            McpServerConfig("web", "Remote", url = "https://example.com/mcp", enabled = true)
        ))).withImportedPermissions()
        assertEquals(listOf("web"), imported.preferences.mcpServers.map { it.id })
        assertFalse(imported.preferences.mcpServers.single().enabled)
    }

    @Test fun workspaceSkillsAreListedThenLoadedOnDemand() = runBlocking<Unit> {
        val root = Files.createTempDirectory("parchment-skill-")
        val folder = root.resolve(".agents/skills/release-notes")
        Files.createDirectories(folder)
        val body = "---\nname: release-notes\ndescription: >\n  Write release notes\n  from the changelog.\n---\nFull instructions live here."
        Files.writeString(folder.resolve("SKILL.md"), body)
        Files.createDirectories(root.resolve(".agents/skills/no-description"))
        Files.writeString(root.resolve(".agents/skills/no-description/SKILL.md"), "---\nname: x\n---\nBody")
        try {
            val policy = AgentPermissionPolicy(workspacePath = root.toString(), workspaceMode = WorkspaceMode.READ_ONLY)
            val skills = WorkspaceHarness.discoverSkills(root.toString())
            assertEquals(listOf("release-notes"), skills.map { it.name })
            assertEquals("Write release notes from the changelog.", skills.single().description)
            Provider("skill_load", """{"name":"release-notes"}""").use { provider ->
                val broker = ToolApprovalBroker("run", "chat", { ToolAccessScope(policy) })
                withContext(AgentToolRunContext(broker)) { withTimeout(12_000) { provider.run(policy).toList() } }
                val first = provider.requests[0].toString()
                assertTrue("Write release notes from the changelog." in first, "The list is offered up front")
                assertFalse("Full instructions live here." in first, "The body loads only on demand")
                assertTrue("Full instructions live here." in provider.requests[1].toString())
                broker.close()
            }
            val unknown = WorkspaceHarness.executeToolCall(root.toString(), WorkspaceMode.READ_ONLY, "c", "skill_load", """{"name":"../../etc"}""")
            assertTrue(unknown.isError, unknown.resultText)
        } finally { root.toFile().deleteRecursively() }
        assertNotNull(PlatformBridge.startMcpProcess("exit 0")?.also { it.close() })
    }
}
