package io.parchment.ai.network

import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.McpServerConfig
import io.parchment.ai.platform.PlatformWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class McpServerState { CONNECTING, READY, FAILED }
data class McpServerStatus(val state: McpServerState, val toolCount: Int = 0, val message: String = "")

/** Live connections to the enabled MCP servers and the tools they offer. */
object McpRegistry {
    private const val PREFIX = WorkspaceHarness.MCP_TOOL_PREFIX
    private const val MAX_NAME_CHARS = 64 // The tightest limit among the provider APIs.
    private const val CONNECT_TIMEOUT_MS = 30_000L

    private class Connection(val config: McpServerConfig, val client: McpClient)
    private data class RemoteTool(val definition: WorkspaceHarness.ToolDefinition, val serverId: String, val remoteName: String)

    private val lock = Mutex()
    private val connectionsState = MutableStateFlow<Map<String, Connection>>(emptyMap())
    private var connections: Map<String, Connection>
        get() = connectionsState.value
        set(value) { connectionsState.value = value }
    private val toolsState = MutableStateFlow<List<RemoteTool>>(emptyList())
    private val statusState = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, McpServerStatus>> = statusState.asStateFlow()
    private val definitionsState = MutableStateFlow<List<WorkspaceHarness.ToolDefinition>>(emptyList())
    val definitions: StateFlow<List<WorkspaceHarness.ToolDefinition>> = definitionsState.asStateFlow()

    init {
        WorkspaceHarness.registerExternalToolsProvider { definitionsState.value }
    }

    fun isMcpTool(name: String): Boolean = WorkspaceHarness.isMcpToolName(name)

    private fun slug(raw: String) = raw.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun qualifiedName(server: McpServerConfig, tool: String, taken: Set<String>): String {
        val base = "$PREFIX${slug(server.name.ifBlank { server.id }).take(20)}__${slug(tool)}".take(MAX_NAME_CHARS)
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "${base.take(MAX_NAME_CHARS - 4)}_$it" }.first { it !in taken }
    }

    private fun setStatus(id: String, status: McpServerStatus?) {
        statusState.value = if (status == null) statusState.value - id else statusState.value + (id to status)
    }

    private suspend fun open(config: McpServerConfig): McpClient {
        val transport = if (config.command.isNotBlank()) {
            ProcessMcpTransport(PlatformWorkspace.startMcpProcess(config.command) ?: error("Local MCP servers need the desktop app."))
        } else {
            HttpMcpTransport(config.url.trim(), config.bearerToken.trim())
        }
        return McpClient(transport)
    }

    /** Makes the live connections match [configs]; a changed server is reconnected. */
    suspend fun sync(configs: List<McpServerConfig>) = lock.withLock {
        val wanted = configs.filter { it.enabled && (it.url.isNotBlank() || it.command.isNotBlank()) }.associateBy { it.id }
        connections.values.filter { wanted[it.config.id] != it.config }.forEach { connection ->
            connections = connections - connection.config.id
            runCatching { connection.client.close() }
            setStatus(connection.config.id, null)
        }
        statusState.value.keys.filter { it !in wanted }.forEach { setStatus(it, null) }
        toolsState.value = toolsState.value.filter { it.serverId in connections }
        publish()
        for (config in wanted.values) {
            if (config.id in connections) continue
            setStatus(config.id, McpServerStatus(McpServerState.CONNECTING))
            var client: McpClient? = null
            try {
                val tools = withTimeout(CONNECT_TIMEOUT_MS) {
                    open(config).also { client = it }.let { opened -> opened.connect(); opened.listTools() }
                }
                val taken = toolsState.value.map { it.definition.name }.toMutableSet()
                val added = tools.map { tool ->
                    val name = qualifiedName(config, tool.name, taken).also { taken += it }
                    RemoteTool(WorkspaceHarness.ToolDefinition(
                        name = name,
                        // A server's own words are untrusted, so the model is told where they came from.
                        description = "[From MCP server \"${config.name.ifBlank { config.id }}\"] ${tool.description}".take(1024),
                        parametersSchema = tool.inputSchema,
                        requiresWritePermission = false,
                        requiresWorkspaceFolder = false
                    ), config.id, tool.name)
                }
                connections = connections + (config.id to Connection(config, checkNotNull(client)))
                toolsState.value = (toolsState.value + added).sortedBy { it.definition.name }
                publish()
                setStatus(config.id, McpServerStatus(McpServerState.READY, added.size))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { client?.close() } }
                setStatus(config.id, null)
                // A timeout is this server's failure; a real cancellation ends the whole sync.
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                setStatus(config.id, McpServerStatus(McpServerState.FAILED, message = "The server did not respond."))
            } catch (error: Exception) {
                runCatching { client?.close() }
                setStatus(config.id, McpServerStatus(McpServerState.FAILED, message = error.message?.take(200) ?: "Could not connect."))
            }
        }
    }

    private fun publish() { definitionsState.value = toolsState.value.map { it.definition } }

    internal suspend fun call(name: String, argumentsJson: String): McpToolResult {
        val tool = toolsState.value.find { it.definition.name == name } ?: return McpToolResult("This MCP tool is no longer available.", true)
        val client = connections[tool.serverId]?.client ?: return McpToolResult("This MCP server is no longer connected.", true)
        val arguments = parseMcpArguments(argumentsJson) ?: return McpToolResult("Tool arguments must be a valid JSON object.", true)
        return try {
            client.callTool(tool.remoteName, arguments)
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) McpToolResult("The MCP server did not respond in time.", true) else throw cancelled
        } catch (error: Exception) {
            if (!client.isAlive) markStopped(tool.serverId, client)
            McpToolResult(error.message?.take(500) ?: "The MCP call failed.", true)
        }
    }

    /** A stopped server must not keep offering tools that can only fail. */
    private suspend fun markStopped(serverId: String, client: McpClient) = lock.withLock {
        if (connections[serverId]?.client !== client) return@withLock
        connections = connections - serverId
        toolsState.value = toolsState.value.filter { it.serverId != serverId }
        publish()
        runCatching { client.close() }
        setStatus(serverId, McpServerStatus(McpServerState.FAILED, message = "The server stopped. Switch it off and on to restart it."))
    }
}
