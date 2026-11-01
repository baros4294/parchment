package io.parchment.ai.harness

import io.parchment.ai.model.ToolCallRecord
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.platform.PlatformBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Self-contained, pure-API Local File System Agent Harness (Hermes / Oh-My-Pi style).
 * Zero external CLI bridge required. Works directly with Gemini Interactions, OpenAI Responses,
 * and Anthropic Messages tool loops.
 */
object WorkspaceHarness {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ToolDefinition(
        val name: String,
        val description: String,
        val parametersSchema: JsonObject,
        val requiresWritePermission: Boolean,
        val requiresShellPermission: Boolean = false
    )

    private val ALL_TOOLS: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "fs_list",
            description = "List files and directories inside the mounted local workspace folder.",
            requiresWritePermission = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative directory path inside workspace (empty string for root).")
                    }
                    putJsonObject("recursive") {
                        put("type", "boolean")
                        put("description", "Whether to list subdirectories recursively.")
                    }
                }
            }
        ),
        ToolDefinition(
            name = "fs_read",
            description = "Read the UTF-8 text contents of a file inside the mounted local workspace with line numbers.",
            requiresWritePermission = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative path to the file inside the workspace.")
                    }
                    putJsonObject("start_line") {
                        put("type", "integer")
                        put("description", "1-based starting line number (default 1).")
                    }
                    putJsonObject("max_lines") {
                        put("type", "integer")
                        put("description", "Maximum number of lines to read (default 250).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            }
        ),
        ToolDefinition(
            name = "fs_search",
            description = "Search for literal text or patterns across files in the mounted workspace (manuscript chapters, notes, or source code).",
            requiresWritePermission = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Text or regex pattern to search for.")
                    }
                    putJsonObject("file_glob") {
                        put("type", "string")
                        put("description", "Optional file extension or substring filter (e.g. '.md', '.kt').")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            }
        ),
        ToolDefinition(
            name = "fs_write",
            description = "Create a new file or overwrite an existing file inside the mounted workspace.",
            requiresWritePermission = true,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative file path inside the workspace.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Full text content to write to the file.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                }
            }
        ),
        ToolDefinition(
            name = "fs_edit",
            description = "Perform a surgical search-and-replace edit on an existing file in the mounted workspace.",
            requiresWritePermission = true,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative file path inside the workspace.")
                    }
                    putJsonObject("old_text") {
                        put("type", "string")
                        put("description", "Exact existing text block to find.")
                    }
                    putJsonObject("new_text") {
                        put("type", "string")
                        put("description", "Replacement text block.")
                    }
                    putJsonObject("replace_all") {
                        put("type", "boolean")
                        put("description", "Replace every occurrence if true (default false).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("old_text"))
                    add(JsonPrimitive("new_text"))
                }
            }
        ),
        ToolDefinition(
            name = "dice_roll",
            description = "Roll unbiased random dice using standard notation (e.g. '1d20', '2d6+3', '4d6', '1d100') for roleplay checks or story branching outcomes.",
            requiresWritePermission = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("expression") {
                        put("type", "string")
                        put("description", "Dice notation such as '1d20', '2d6+3', or '1d100'.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "Optional label or reason for the roll.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("expression"))
                }
            }
        ),
        ToolDefinition(
            name = "agent_memory",
            description = "Read or update persistent project/story memory (PARCHMENT_MEMORY.md in workspace) across sessions (Hermes Agent style).",
            requiresWritePermission = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "'read', 'append', or 'overwrite'.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Memory notes to append or overwrite when action is 'append' or 'overwrite'.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("action"))
                }
            }
        ),
        ToolDefinition(
            name = "shell_exec",
            description = "Execute a shell command inside the mounted workspace directory and return stdout/stderr (OpenCode / Oh-My-Pi style).",
            requiresWritePermission = true,
            requiresShellPermission = true,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Shell command to run in the workspace directory.")
                    }
                    putJsonObject("timeout_seconds") {
                        put("type", "integer")
                        put("description", "Timeout in seconds (default 30).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("command"))
                }
            }
        )
    )

    fun activeToolsForMode(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false
    ): List<ToolDefinition> {
        if (mode == WorkspaceMode.OFF || workspacePath.isBlank()) return emptyList()
        return ALL_TOOLS.filter { tool ->
            val writeOk = mode == WorkspaceMode.READ_WRITE || !tool.requiresWritePermission
            val shellOk = !tool.requiresShellPermission || (mode == WorkspaceMode.READ_WRITE && allowShell)
            writeOk && shellOk
        }
    }

    /**
     * Auto-discovers AGENTS.md, CLAUDE.md, PARCHMENT.md, and PARCHMENT_MEMORY.md in the workspace root
     * (inspired by OpenCode, Hermes Agent, and Oh My Pi).
     */
    fun discoverWorkspaceContextFiles(workspaceRoot: String): String {
        if (workspaceRoot.isBlank()) return ""
        val candidates = listOf("AGENTS.md", "CLAUDE.md", "PARCHMENT.md", "PARCHMENT_MEMORY.md")
        val sb = StringBuilder()
        for (fileName in candidates) {
            PlatformBridge.workspaceRead(workspaceRoot, fileName, startLine = 1, maxLines = 150).onSuccess { content ->
                if (content.isNotBlank()) {
                    sb.appendLine("#### Auto-Loaded Workspace File: `$fileName`")
                    sb.appendLine(content)
                    sb.appendLine()
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Formats active workspace tools for Google Gemini Interactions API (`tools` array).
     */
    fun buildGeminiInteractionsTools(mode: WorkspaceMode, workspacePath: String, allowShell: Boolean = false): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell)) {
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersSchema)
            })
        }
    }

    /**
     * Formats active workspace tools for OpenAI Responses API (`/v1/responses`).
     */
    fun buildOpenAiResponsesTools(mode: WorkspaceMode, workspacePath: String, allowShell: Boolean = false): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell)) {
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersSchema)
            })
        }
    }

    /**
     * Formats active workspace tools for legacy OpenAI `/v1/chat/completions`.
     */
    fun buildOpenAiChatCompletionsTools(mode: WorkspaceMode, workspacePath: String, allowShell: Boolean = false): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell)) {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema)
                }
            })
        }
    }

    /**
     * Formats active workspace tools for Anthropic Messages API (`/v1/messages`).
     */
    fun buildAnthropicTools(mode: WorkspaceMode, workspacePath: String, allowShell: Boolean = false): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell)) {
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parametersSchema)
            })
        }
    }

    /**
     * Executes a single tool call requested by the model inside the sandboxed workspace directory.
     */
    fun executeToolCall(
        workspaceRoot: String,
        mode: WorkspaceMode,
        callId: String,
        toolName: String,
        argumentsJson: String
    ): ToolCallRecord {
        if (mode == WorkspaceMode.OFF || workspaceRoot.isBlank()) {
            return ToolCallRecord(
                id = callId,
                name = toolName,
                argumentsJson = argumentsJson,
                resultText = "Error: Workspace harness is disabled or no workspace directory is mounted.",
                isError = true
            )
        }

        val args = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

        fun strArg(key: String, default: String = ""): String =
            args[key]?.jsonPrimitive?.content ?: default

        val result: Result<String> = when (toolName) {
            "fs_list" -> {
                val rel = strArg("path", "")
                val rec = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
                PlatformBridge.workspaceList(workspaceRoot, rel, rec)
            }
            "fs_read" -> {
                val rel = strArg("path")
                val startLine = args["start_line"]?.jsonPrimitive?.intOrNull ?: 1
                val maxLines = args["max_lines"]?.jsonPrimitive?.intOrNull ?: 250
                PlatformBridge.workspaceRead(workspaceRoot, rel, startLine, maxLines)
            }
            "fs_search" -> {
                val query = strArg("query")
                val glob = strArg("file_glob", "")
                PlatformBridge.workspaceSearch(workspaceRoot, query, glob)
            }
            "fs_write" -> {
                if (mode != WorkspaceMode.READ_WRITE) {
                    Result.failure(IllegalStateException("Workspace is in Read-Only mode. Switch to Agent R/W mode to allow writing files."))
                } else {
                    val rel = strArg("path")
                    val content = strArg("content")
                    PlatformBridge.workspaceWrite(workspaceRoot, rel, content)
                }
            }
            "fs_edit" -> {
                if (mode != WorkspaceMode.READ_WRITE) {
                    Result.failure(IllegalStateException("Workspace is in Read-Only mode. Switch to Agent R/W mode to allow editing files."))
                } else {
                    val rel = strArg("path")
                    val oldText = strArg("old_text")
                    val newText = strArg("new_text")
                    val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
                    PlatformBridge.workspaceEdit(workspaceRoot, rel, oldText, newText, replaceAll)
                }
            }
            "dice_roll" -> {
                val expr = strArg("expression", "1d20")
                val reason = strArg("reason", "")
                runCatching { evaluateDiceExpression(expr, reason) }
            }
            "agent_memory" -> {
                val action = strArg("action", "read").lowercase()
                val content = strArg("content", "")
                when (action) {
                    "read" -> PlatformBridge.workspaceRead(workspaceRoot, "PARCHMENT_MEMORY.md", 1, 500)
                        .recover { "(PARCHMENT_MEMORY.md does not exist yet.)" }
                    "append" -> {
                        if (mode != WorkspaceMode.READ_WRITE) {
                            Result.failure(IllegalStateException("Switch to Agent R/W mode to update PARCHMENT_MEMORY.md."))
                        } else {
                            val existing = PlatformBridge.workspaceRead(workspaceRoot, "PARCHMENT_MEMORY.md", 1, 2000)
                                .getOrDefault("")
                                .lines()
                                .drop(1) // drop header line from workspaceRead
                                .joinToString("\n") { it.substringAfter(": ") }
                            val combined = if (existing.isBlank()) content else "$existing\n\n$content"
                            PlatformBridge.workspaceWrite(workspaceRoot, "PARCHMENT_MEMORY.md", combined)
                        }
                    }
                    "overwrite" -> {
                        if (mode != WorkspaceMode.READ_WRITE) {
                            Result.failure(IllegalStateException("Switch to Agent R/W mode to update PARCHMENT_MEMORY.md."))
                        } else {
                            PlatformBridge.workspaceWrite(workspaceRoot, "PARCHMENT_MEMORY.md", content)
                        }
                    }
                    else -> Result.failure(IllegalArgumentException("Unknown agent_memory action '$action'."))
                }
            }
            "shell_exec" -> {
                if (mode != WorkspaceMode.READ_WRITE) {
                    Result.failure(IllegalStateException("Workspace is in Read-Only mode. Switch to Agent R/W mode to execute shell commands."))
                } else {
                    val cmd = strArg("command")
                    val timeout = args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30
                    PlatformBridge.workspaceShellExec(workspaceRoot, cmd, timeout)
                }
            }
            else -> Result.failure(IllegalArgumentException("Unknown workspace tool '$toolName'."))
        }

        return result.fold(
            onSuccess = { output ->
                ToolCallRecord(
                    id = callId,
                    name = toolName,
                    argumentsJson = argumentsJson,
                    resultText = output,
                    isError = false
                )
            },
            onFailure = { err ->
                ToolCallRecord(
                    id = callId,
                    name = toolName,
                    argumentsJson = argumentsJson,
                    resultText = "Tool Error (${err::class.simpleName}): ${err.message}",
                    isError = true
                )
            }
        )
    }

    private fun evaluateDiceExpression(expression: String, reason: String): String {
        val cleaned = expression.trim().lowercase().replace(" ", "")
        val match = Regex("""^(\d*)d(\d+)([+-]\d+)?$""").matchEntire(cleaned)
            ?: error("Invalid dice notation '$expression'. Expected format like '1d20' or '2d6+3'.")
        val count = (match.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 100)
        val sides = (match.groupValues[2].toIntOrNull() ?: 20).coerceIn(2, 10000)
        val modifier = match.groupValues[3].toIntOrNull() ?: 0
        val rolls = (1..count).map { kotlin.random.Random.nextInt(1, sides + 1) }
        val sum = rolls.sum() + modifier
        val modStr = when {
            modifier > 0 -> " + $modifier"
            modifier < 0 -> " - ${-modifier}"
            else -> ""
        }
        val label = if (reason.isNotBlank()) " ($reason)" else ""
        return "Rolled $expression$label: [${rolls.joinToString(", ")}]$modStr = $sum"
    }
}
