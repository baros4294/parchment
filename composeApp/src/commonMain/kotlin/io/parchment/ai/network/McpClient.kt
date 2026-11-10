package io.parchment.ai.network

import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.parchment.ai.platform.McpProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class McpException(message: String) : Exception(message)

internal data class McpToolInfo(val name: String, val description: String, val inputSchema: JsonObject)
internal data class McpToolResult(val text: String, val isError: Boolean)

internal interface McpTransport {
    /** The revision sent on HTTP requests; the client sets it after negotiation. */
    var protocolVersion: String
    val isAlive: Boolean get() = true
    suspend fun request(message: JsonObject): JsonObject
    suspend fun notify(message: JsonObject)
    suspend fun close()
}

/** Streamable HTTP: one POST per message, answered with JSON or an event stream. */
internal class HttpMcpTransport(private val url: String, private val bearerToken: String) : McpTransport {
    override var protocolVersion = MCP_HANDSHAKE_VERSION
    private var sessionId = ""

    init {
        val target = Url(url)
        val loopback = target.host == "localhost" || target.host == "127.0.0.1" || target.host == "[::1]" || target.host == "::1"
        if (target.protocol.name != "https" && !loopback && bearerToken.isNotBlank()) {
            throw McpException("A token can be sent only over https or to this device.")
        }
    }

    private suspend fun post(message: JsonObject, onResponse: (suspend (JsonObject) -> Unit)?) {
        var finished = false
        AiProviderEngine.httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("MCP-Protocol-Version", protocolVersion)
            (message["method"] as? JsonPrimitive)?.contentOrNull?.let { method ->
                header("Mcp-Method", method)
                if (method == "tools/call") {
                    ((message["params"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.let { header("Mcp-Name", it) }
                }
            }
            if (bearerToken.isNotBlank()) header("Authorization", "Bearer $bearerToken")
            if (sessionId.isNotEmpty()) header("Mcp-Session-Id", sessionId)
            setBody(message.toString())
        }.execute { response ->
            response.headers["Mcp-Session-Id"]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val status = response.status.value
            if (onResponse == null) {
                if (status !in 200..299) throw McpException("The server rejected a notification (HTTP $status).")
                return@execute
            }
            var answered = false
            suspend fun deliver(text: String) {
                val parsed = runCatching { AiProviderEngine.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                if (parsed["id"] == message["id"] && ("result" in parsed || "error" in parsed)) {
                    answered = true
                    finished = true
                    onResponse(parsed)
                }
            }
            AiProviderEngine.readSseOrJsonFallback(response.bodyAsChannel(), { finished }, { _, data -> deliver(data) }, { deliver(it) })
            if (!answered) throw McpException("The server returned no answer (HTTP $status).")
        }
    }

    override suspend fun request(message: JsonObject): JsonObject {
        var answer: JsonObject? = null
        post(message) { answer = it }
        return answer ?: throw McpException("The server returned no answer.")
    }

    override suspend fun notify(message: JsonObject) = post(message, null)

    override suspend fun close() {}
}

/** Newline-delimited JSON-RPC to a local child process. */
internal class ProcessMcpTransport(private val process: McpProcess) : McpTransport {
    override var protocolVersion = MCP_HANDSHAKE_VERSION
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val writeLock = Mutex()
    private val waiting = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var ended = false
    override val isAlive: Boolean get() = !ended

    init {
        scope.launch {
            try {
                while (true) {
                    val line = process.readLine() ?: break
                    val message = runCatching { AiProviderEngine.json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                    val id = message["id"] ?: continue
                    if ("method" in message) {
                        // Parchment offers no client features, so a server's own requests are refused.
                        write(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id)
                            put("error", buildJsonObject { put("code", -32601); put("message", "Method not found") })
                        })
                    } else {
                        lock.withLock { waiting.remove(id.toString()) }?.complete(message)
                    }
                }
            } catch (_: Exception) {
                // An oversized line or broken pipe ends this server only; the waiters are failed below.
            } finally {
                withContext(NonCancellable) {
                    val abandoned = lock.withLock { ended = true; waiting.values.toList().also { waiting.clear() } }
                    abandoned.forEach { it.completeExceptionally(McpException("The server process exited.")) }
                }
            }
        }
    }

    private suspend fun write(message: JsonObject) = writeLock.withLock {
        withContext(Dispatchers.IO) { process.writeLine(message.toString()) }
    }

    override suspend fun request(message: JsonObject): JsonObject {
        val key = checkNotNull(message["id"]).toString()
        val answer = CompletableDeferred<JsonObject>()
        lock.withLock {
            if (ended) throw McpException("The server process exited.")
            waiting[key] = answer
        }
        try {
            write(message)
            return answer.await()
        } finally {
            withContext(NonCancellable) { lock.withLock { waiting.remove(key) } }
        }
    }

    override suspend fun notify(message: JsonObject) = write(message)

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) { process.close() }
        scope.cancel()
    }
}

internal const val MCP_HANDSHAKE_VERSION = "2025-11-25"
internal const val MCP_STATELESS_VERSION = "2026-07-28"
private val MCP_HANDSHAKE_VERSIONS = setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
private const val MCP_VERSION_META = "io.modelcontextprotocol/protocolVersion"
private const val MCP_REQUEST_TIMEOUT_MS = 120_000L
private const val MCP_MAX_RESULT_CHARS = 64_000
private const val MCP_MAX_PAGES = 20
private const val MCP_MAX_TOOLS = 256

/** Speaks the handshake most servers use, and falls back to the 2026 revision that removed it. */
internal class McpClient(private val transport: McpTransport) {
    private var nextId = 0L
    private var stateless = false

    private fun message(method: String, params: JsonObject, id: Long?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        put("params", if (!stateless) params else JsonObject(params + ("_meta" to buildJsonObject { put(MCP_VERSION_META, MCP_STATELESS_VERSION) })))
    }

    /** Returns the JSON-RPC response object, which may carry an error. */
    private suspend fun exchange(method: String, params: JsonObject): JsonObject {
        val id = synchronized(this) { ++nextId }
        try {
            return withTimeout(MCP_REQUEST_TIMEOUT_MS) { transport.request(message(method, params, id)) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    withTimeout(2_000) {
                        transport.notify(message("notifications/cancelled", buildJsonObject { put("requestId", id) }, null))
                    }
                }
            }
            throw cancelled
        }
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val response = exchange(method, params)
        (response["error"] as? JsonObject)?.let { error ->
            throw McpException((error["message"] as? JsonPrimitive)?.contentOrNull ?: "The server reported an error.")
        }
        return response["result"] as? JsonObject ?: throw McpException("The server sent an invalid result.")
    }

    suspend fun connect() {
        val response = exchange("initialize", buildJsonObject {
            put("protocolVersion", MCP_HANDSHAKE_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "Parchment"); put("version", "2") })
        })
        val result = response["result"] as? JsonObject
        if (result == null) {
            // A 2026 server has no handshake, so listing tools is what proves it is usable.
            stateless = true
            transport.protocolVersion = MCP_STATELESS_VERSION
            return
        }
        val version = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (version !in MCP_HANDSHAKE_VERSIONS) throw McpException("This server uses an unsupported protocol version ($version).")
        transport.protocolVersion = version
        transport.notify(message("notifications/initialized", JsonObject(emptyMap()), null))
    }

    suspend fun listTools(): List<McpToolInfo> {
        val tools = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        repeat(MCP_MAX_PAGES) {
            val page = call("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (page["tools"] as? JsonArray).orEmpty().forEach { element ->
                val tool = element as? JsonObject ?: return@forEach
                val name = (tool["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
                val schema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap())
                tools += McpToolInfo(name, (tool["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    if ("type" in schema) schema else JsonObject(schema + ("type" to JsonPrimitive("object"))))
            }
            cursor = (page["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (cursor == null || tools.size >= MCP_MAX_TOOLS) return tools.take(MCP_MAX_TOOLS)
        }
        return tools.take(MCP_MAX_TOOLS)
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val result = call("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        return McpToolResult(renderToolContent(result).take(MCP_MAX_RESULT_CHARS), (result["isError"] as? JsonPrimitive)?.contentOrNull == "true")
    }

    val isAlive: Boolean get() = transport.isAlive

    suspend fun close() = transport.close()
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun renderToolContent(result: JsonObject): String {
    val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        when (item.text("type")) {
            "text" -> item.text("text")
            "image", "audio" -> "[${item.text("type")}: ${item.text("mimeType")}, not shown]"
            "resource_link" -> "[resource: ${item.text("uri")}]"
            "resource" -> (item["resource"] as? JsonObject)?.let { it.text("text").ifEmpty { "[resource: ${it.text("uri")}]" } }
            else -> null
        }
    }
    if (parts.isNotEmpty()) return parts.joinToString("\n")
    val structured: JsonElement = result["structuredContent"] ?: JsonNull
    return if (structured is JsonNull) "(The tool returned no content.)" else structured.toString()
}

/** Accepts only a JSON object for a tool's arguments. */
internal fun parseMcpArguments(argumentsJson: String): JsonObject? =
    runCatching { AiProviderEngine.json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
