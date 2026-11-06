package io.parchment.ai.harness

import io.parchment.ai.model.GoalTrackerState
import io.parchment.ai.model.TodoItem
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.pow
/**
 * Self-contained, pure-API Local File System & Long-Horizon Goal Agent Harness.
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
        val requiresShellPermission: Boolean = false,
        val requiresWorkspaceFolder: Boolean = true
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
            name = "fs_glob",
            description = "Find files and directories matching a glob pattern (e.g. '*.kt', '**/*.md', 'src/**/Test*') inside the mounted workspace.",
            requiresWritePermission = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("pattern") {
                        put("type", "string")
                        put("description", "Glob pattern to match (e.g. '*.kt', '**/*.md', 'docs/*').")
                    }
                    putJsonObject("max_results") {
                        put("type", "integer")
                        put("description", "Maximum number of matched paths to return (default 200).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("pattern")) }
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
            name = "fs_multi_edit",
            description = "Apply multiple search-and-replace edits to a single file atomically in one call. If any edit fails to match, all edits are rolled back.",
            requiresWritePermission = true,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative file path inside the workspace.")
                    }
                    putJsonObject("edits") {
                        put("type", "array")
                        put("description", "Ordered list of search-and-replace hunks to apply atomically.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
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
                                add(JsonPrimitive("old_text"))
                                add(JsonPrimitive("new_text"))
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("edits"))
                }
            }
        ),
        ToolDefinition(
            name = "fs_move",
            description = "Move or rename a file or directory inside the mounted workspace.",
            requiresWritePermission = true,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source_path") {
                        put("type", "string")
                        put("description", "Existing relative path inside the workspace.")
                    }
                    putJsonObject("dest_path") {
                        put("type", "string")
                        put("description", "Destination relative path inside the workspace.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source_path"))
                    add(JsonPrimitive("dest_path"))
                }
            }
        ),
        ToolDefinition(
            name = "fs_delete",
            description = "Delete a file or directory inside the mounted workspace.",
            requiresWritePermission = true,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative path to delete inside the workspace.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                }
            }
        ),
        ToolDefinition(
            name = "web_search",
            description = "Search the live internet for up-to-date information, documentation, news, and facts using a free multi-engine search cascade (DuckDuckGo, Wikipedia, HackerNews), returning titles, URLs, and snippets.",
            requiresWritePermission = false,
            requiresWorkspaceFolder = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Search query keywords or question.")
                    }
                    putJsonObject("max_results") {
                        put("type", "integer")
                        put("description", "Maximum number of search results to return (default 6).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("query"))
                }
            }
        ),
        ToolDefinition(
            name = "web_fetch",
            description = "Fetch the contents of any HTTP/HTTPS URL and extract clean readable Markdown/text or JSON.",
            requiresWritePermission = false,
            requiresWorkspaceFolder = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Full http:// or https:// URL to fetch.")
                    }
                    putJsonObject("max_chars") {
                        put("type", "integer")
                        put("description", "Maximum characters to return (default 24000).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("url"))
                }
            }
        ),
        ToolDefinition(
            name = "calc_eval",
            description = "Deterministically evaluate a mathematical or scientific expression (supports +, -, *, /, %, ^, parentheses, sqrt, sin, cos, tan, ln, log, abs, round, pi, e).",
            requiresWritePermission = false,
            requiresWorkspaceFolder = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("expression") {
                        put("type", "string")
                        put("description", "Mathematical expression to evaluate, e.g. '(1450 * 1.08^5) / sqrt(16)'.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("expression"))
                }
            }
        ),
        ToolDefinition(
            name = "dice_roll",
            description = "Roll unbiased random dice using standard notation (e.g. '1d20', '2d6+3', '4d6', '1d100') for roleplay checks or story branching outcomes.",
            requiresWritePermission = false,
            requiresWorkspaceFolder = false,
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
            name = "todo_write",
            description = "Create or update the structured task checklist for multi-step or long-horizon goals. Always keep one current step 'in_progress' and mark steps 'completed' as you verify them.",
            requiresWritePermission = false,
            requiresWorkspaceFolder = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("goal") {
                        put("type", "string")
                        put("description", "Overarching goal objective and definition of done.")
                    }
                    putJsonObject("todos") {
                        put("type", "array")
                        put("description", "Ordered list of verifiable task steps.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("id") {
                                    put("type", "string")
                                    put("description", "Step ID such as '1', '2', '3'.")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "Specific actionable step and verification check.")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "'pending', 'in_progress', 'completed', or 'cancelled'.")
                                }
                            }
                            putJsonArray("required") {
                                add(JsonPrimitive("id"))
                                add(JsonPrimitive("content"))
                                add(JsonPrimitive("status"))
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("todos"))
                }
            }
        ),
        ToolDefinition(
            name = "goal_complete",
            description = "Signal that all steps and acceptance criteria of the active goal have been verified and completed.",
            requiresWritePermission = false,
            requiresWorkspaceFolder = false,
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("summary") {
                        put("type", "string")
                        put("description", "Concise summary of all completed work.")
                    }
                    putJsonObject("verification") {
                        put("type", "string")
                        put("description", "Verification checks performed to confirm the goal is 100% done.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("summary"))
                }
            }
        ),
        ToolDefinition(
            name = "agent_memory",
            description = "Read or update persistent project/story memory (PARCHMENT_MEMORY.md in workspace) across sessions.",
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
            description = "Execute a shell command inside the mounted workspace directory and return stdout/stderr.",
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

    fun allTools(): List<ToolDefinition> = ALL_TOOLS

    fun extractParameterSignature(tool: ToolDefinition): String {
        val props = tool.parametersSchema["properties"] as? JsonObject ?: return "${tool.name}()"
        val reqSet = (tool.parametersSchema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            .orEmpty()
        val params = props.keys.joinToString(", ") { k -> if (k in reqSet) k else "$k?" }
        return "${tool.name}($params)"
    }

    private data class CachedWorkspaceContext(
        val timestampMs: Long,
        val fileNames: List<String>,
        val combinedContent: String
    )

    private val workspaceContextCache = mutableMapOf<String, CachedWorkspaceContext>()
    private const val WORKSPACE_CONTEXT_TTL_MS = 10_000L

    fun invalidateWorkspaceCache(workspaceRoot: String = "") {
        synchronized(workspaceContextCache) {
            if (workspaceRoot.isBlank()) {
                workspaceContextCache.clear()
            } else {
                workspaceContextCache.remove(workspaceRoot.trim())
            }
        }
    }

    private fun getOrLoadWorkspaceContext(workspaceRoot: String): CachedWorkspaceContext {
        val cleanRoot = workspaceRoot.trim()
        if (cleanRoot.isEmpty()) return CachedWorkspaceContext(0L, emptyList(), "")
        val now = PlatformBridge.currentTimeMillis()
        synchronized(workspaceContextCache) {
            val cached = workspaceContextCache[cleanRoot]
            if (cached != null && now - cached.timestampMs <= WORKSPACE_CONTEXT_TTL_MS) {
                return cached
            }
        }
        val candidates = listOf("AGENTS.md", "CLAUDE.md", "PARCHMENT.md", "PARCHMENT_MEMORY.md")
        val foundNames = mutableListOf<String>()
        val sb = StringBuilder()
        for (fileName in candidates) {
            PlatformBridge.workspaceRead(cleanRoot, fileName, startLine = 1, maxLines = 150).onSuccess { content ->
                if (content.isNotBlank()) {
                    foundNames.add(fileName)
                    sb.appendLine("#### Auto-Loaded Workspace File: `$fileName`")
                    sb.appendLine(content)
                    sb.appendLine()
                }
            }
        }
        val fresh = CachedWorkspaceContext(
            timestampMs = now,
            fileNames = foundNames,
            combinedContent = sb.toString().trim()
        )
        synchronized(workspaceContextCache) {
            workspaceContextCache[cleanRoot] = fresh
        }
        return fresh
    }

    fun listDiscoveredContextFileNames(workspaceRoot: String): List<String> {
        if (workspaceRoot.isBlank()) return emptyList()
        return getOrLoadWorkspaceContext(workspaceRoot).fileNames
    }

    fun isParallelSafeReadOnlyTool(toolName: String): Boolean {
        return when (toolName.lowercase()) {
            "fs_read", "fs_list", "fs_glob", "fs_search",
            "web_search", "web_fetch", "calc_eval", "dice_roll" -> true
            else -> false
        }
    }

    fun activeToolsForMode(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false,
        enableWebTools: Boolean = false,
        includeLocalWebSearchTool: Boolean = enableWebTools
    ): List<ToolDefinition> {
        val hasMountedWorkspace = mode != WorkspaceMode.OFF && workspacePath.isNotBlank()
        if (!hasMountedWorkspace && !goalModeActive && !enableWebTools && !includeLocalWebSearchTool) return emptyList()
        return ALL_TOOLS.filter { tool ->
            if (tool.name == "web_search" && !includeLocalWebSearchTool) {
                return@filter false
            }
            if (!hasMountedWorkspace) {
                if (!goalModeActive && (enableWebTools || includeLocalWebSearchTool)) {
                    tool.name == "web_search" || tool.name == "web_fetch" || tool.name == "calc_eval" || tool.name == "dice_roll"
                } else {
                    !tool.requiresWorkspaceFolder
                }
            } else {
                val writeOk = mode == WorkspaceMode.READ_WRITE || !tool.requiresWritePermission
                val shellOk = !tool.requiresShellPermission || (mode == WorkspaceMode.READ_WRITE && allowShell)
                writeOk && shellOk
            }
        }
    }

    /**
     * Auto-discovers AGENTS.md, CLAUDE.md, PARCHMENT.md, and PARCHMENT_MEMORY.md in the workspace root.
     * Cached in memory with a 10s TTL (and invalidated immediately on workspace writes) so UI frame rendering
     * and `o200k_base` context breakdown never block on synchronous disk I/O.
     */
    fun discoverWorkspaceContextFiles(workspaceRoot: String): String {
        if (workspaceRoot.isBlank()) return ""
        return getOrLoadWorkspaceContext(workspaceRoot).combinedContent
    }

    /**
     * Formats active workspace & utility tools for Google Gemini Interactions API (`/v1beta/interactions`)
     * and OpenAI Responses API (`/v1/responses`), which share the flat `{"type":"function","name":...,"parameters":...}` schema.
     */
    fun buildFlatFunctionTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false,
        enableWebTools: Boolean = false,
        includeLocalWebSearchTool: Boolean = enableWebTools
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive, enableWebTools, includeLocalWebSearchTool)) {
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersSchema)
            })
        }
    }

    fun buildGeminiInteractionsTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false,
        enableWebTools: Boolean = false,
        includeLocalWebSearchTool: Boolean = enableWebTools
    ): JsonArray = buildFlatFunctionTools(mode, workspacePath, allowShell, goalModeActive, enableWebTools, includeLocalWebSearchTool)

    fun buildOpenAiResponsesTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false,
        enableWebTools: Boolean = false,
        includeLocalWebSearchTool: Boolean = enableWebTools
    ): JsonArray = buildFlatFunctionTools(mode, workspacePath, allowShell, goalModeActive, enableWebTools, includeLocalWebSearchTool)

    /**
     * Formats active workspace & utility tools for OpenAI `/v1/chat/completions`.
     */
    fun buildOpenAiChatCompletionsTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false,
        enableWebTools: Boolean = false,
        includeLocalWebSearchTool: Boolean = enableWebTools
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive, enableWebTools, includeLocalWebSearchTool)) {
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
     * Formats active workspace & utility tools for Anthropic Messages API (`/v1/messages`).
     */
    fun buildAnthropicTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false,
        enableWebTools: Boolean = false,
        includeLocalWebSearchTool: Boolean = enableWebTools
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive, enableWebTools, includeLocalWebSearchTool)) {
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parametersSchema)
            })
        }
    }

    /**
     * Formats a concise, present-participle intent description (inspired by `omp`) for live UI status telemetry
     * while a workspace or goal tool is executing.
     */
    fun describeToolCallIntent(toolName: String, argumentsJson: String): String {
        val args = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        fun strArg(key: String): String = args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        return when (toolName) {
            "fs_read" -> {
                val path = strArg("path").ifBlank { "file" }
                val start = args["start_line"]?.jsonPrimitive?.intOrNull ?: 1
                val max = args["max_lines"]?.jsonPrimitive?.intOrNull ?: 250
                "Reading $path (lines $start..${start + max - 1})"
            }
            "fs_edit" -> "Editing ${strArg("path").ifBlank { "file" }}"
            "fs_multi_edit" -> {
                val count = (args["edits"] as? JsonArray)?.size ?: 0
                "Multi-editing ${strArg("path").ifBlank { "file" }} ($count hunks)"
            }
            "fs_write" -> "Writing ${strArg("path").ifBlank { "file" }}"
            "fs_move" -> "Moving ${strArg("source_path")} -> ${strArg("dest_path")}"
            "fs_delete" -> "Deleting ${strArg("path")}"
            "fs_list" -> "Listing workspace ${strArg("path").ifBlank { "root" }}"
            "fs_glob" -> "Matching files with glob '${strArg("pattern")}'"
            "fs_search" -> "Searching workspace for '${strArg("query")}'"
            "web_search" -> "Searching the web for '${strArg("query")}'"
            "web_fetch" -> "Fetching URL ${strArg("url")}"
            "calc_eval" -> "Calculating ${strArg("expression")}"
            "shell_exec" -> {
                val cmd = strArg("command").trim()
                if (cmd.isNotEmpty()) "Running shell_exec\n$ $cmd" else "Running shell_exec"
            }
            "todo_write" -> "Updating task checklist"
            "goal_complete" -> "Verifying & completing goal"
            "agent_memory" -> "Updating PARCHMENT_MEMORY.md (${strArg("action").ifBlank { "read" }})"
            "dice_roll" -> "Rolling ${strArg("expression").ifBlank { "1d20" }}"
            else -> "Executing $toolName"
        }
    }
    /**
     * Executes a single tool call requested by the model inside the sandboxed workspace directory
     * (or executes workspace-independent goal/dice tools even when no folder is mounted).
     */
    fun executeToolCall(
        workspaceRoot: String,
        mode: WorkspaceMode,
        callId: String,
        toolName: String,
        argumentsJson: String,
        onOutputDelta: ((String) -> Unit)? = null
    ): ToolCallRecord {
        val toolStartMs = PlatformBridge.currentTimeMillis()
        val isFolderFreeTool = toolName == "todo_write" ||
            toolName == "goal_complete" ||
            toolName == "dice_roll" ||
            toolName == "calc_eval" ||
            toolName == "web_search" ||
            toolName == "web_fetch"
        if (!isFolderFreeTool && (mode == WorkspaceMode.OFF || workspaceRoot.isBlank())) {
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
            args[key]?.jsonPrimitive?.contentOrNull ?: default

        val result: Result<String> = when (toolName) {
            "todo_write" -> runCatching {
                val goal = strArg("goal", "")
                val todosArr = args["todos"] as? JsonArray ?: JsonArray(emptyList())
                val parsed = todosArr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { "•" } ?: "•"
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val status = obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "pending"
                    TodoItem(id = id, content = content, status = status)
                }
                val done = parsed.count { it.status == "completed" }
                val active = parsed.count { it.status == "in_progress" }
                val pending = parsed.count { it.status == "pending" }
                val mdSummary = buildString {
                    if (goal.isNotBlank()) appendLine("# Goal: $goal")
                    parsed.forEach { t ->
                        val mark = when (t.status) {
                            "completed" -> "[x]"
                            "in_progress" -> "[/]"
                            "cancelled" -> "[-]"
                            else -> "[ ]"
                        }
                        appendLine("- $mark (${t.id}) ${t.content}")
                    }
                }.trim()
                if (mode == WorkspaceMode.READ_WRITE && workspaceRoot.isNotBlank()) {
                    PlatformBridge.workspaceWrite(workspaceRoot, "PARCHMENT_TODO.md", mdSummary)
                }
                "Updated Todo Checklist ($done/${parsed.size} completed, $active in_progress, $pending pending).\n$mdSummary"
            }
            "goal_complete" -> runCatching {
                val summary = strArg("summary", "Goal completed.")
                val verification = strArg("verification", "Verified all acceptance criteria.")
                "Goal marked 100% COMPLETE.\nSummary: $summary\nVerification: $verification"
            }
            "fs_list" -> {
                val rel = strArg("path", "")
                val rec = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
                PlatformBridge.workspaceList(workspaceRoot, rel, rec)
            }
            "fs_glob" -> {
                val pat = strArg("pattern")
                val maxRes = args["max_results"]?.jsonPrimitive?.intOrNull ?: 200
                PlatformBridge.workspaceGlob(workspaceRoot, pat, maxRes)
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
            "fs_multi_edit" -> {
                if (mode != WorkspaceMode.READ_WRITE) {
                    Result.failure(IllegalStateException("Workspace is in Read-Only mode. Switch to Agent R/W mode to allow editing files."))
                } else {
                    val rel = strArg("path")
                    val editsArr = args["edits"] as? JsonArray ?: JsonArray(emptyList())
                    val parsedEdits = editsArr.mapNotNull { el ->
                        val obj = el as? JsonObject ?: return@mapNotNull null
                        val oldTxt = obj["old_text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val newTxt = obj["new_text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val repAll = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
                        Triple(oldTxt, newTxt, repAll)
                    }
                    PlatformBridge.workspaceMultiEdit(workspaceRoot, rel, parsedEdits)
                }
            }
            "fs_move" -> {
                if (mode != WorkspaceMode.READ_WRITE) {
                    Result.failure(IllegalStateException("Workspace is in Read-Only mode. Switch to Agent R/W mode to allow moving files."))
                } else {
                    val src = strArg("source_path")
                    val dst = strArg("dest_path")
                    PlatformBridge.workspaceMove(workspaceRoot, src, dst)
                }
            }
            "fs_delete" -> {
                if (mode != WorkspaceMode.READ_WRITE) {
                    Result.failure(IllegalStateException("Workspace is in Read-Only mode. Switch to Agent R/W mode to allow deleting files."))
                } else {
                    val rel = strArg("path")
                    PlatformBridge.workspaceDelete(workspaceRoot, rel)
                }
            }
            "web_search" -> {
                val query = strArg("query")
                val maxRes = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 6).coerceIn(1, 15)
                executeFreeWebSearch(query, maxRes)
            }
            "web_fetch" -> {
                val url = strArg("url")
                val maxChars = args["max_chars"]?.jsonPrimitive?.intOrNull ?: 24000
                PlatformBridge.fetchUrlText(url, maxChars)
            }
            "calc_eval" -> {
                val expr = strArg("expression")
                runCatching { evaluateMathExpression(expr) }
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
                                .joinToString("\n") { it.replaceFirst(Regex("^\\d+:\\s?"), "") }
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
                    PlatformBridge.workspaceShellExec(workspaceRoot, cmd, timeout, onOutputDelta)
                }
            }
            else -> Result.failure(IllegalArgumentException("Unknown workspace tool '$toolName'."))
        }

        if (toolName in listOf("fs_write", "fs_edit", "fs_multi_edit", "fs_move", "fs_delete", "agent_memory", "shell_exec", "todo_write")) {
            invalidateWorkspaceCache(workspaceRoot)
        }

        val elapsedToolMs = (PlatformBridge.currentTimeMillis() - toolStartMs).coerceAtLeast(1L)
        return result.fold(
            onSuccess = { output ->
                val shapedOutput = compressToolOutputWithSizeValve(toolName, output)
                ToolCallRecord(
                    id = callId,
                    name = toolName,
                    argumentsJson = argumentsJson,
                    resultText = shapedOutput,
                    isError = false,
                    durationMs = elapsedToolMs
                )
            },
            onFailure = { err ->
                ToolCallRecord(
                    id = callId,
                    name = toolName,
                    argumentsJson = argumentsJson,
                    resultText = "Tool Error (${err::class.simpleName}): ${err.message}",
                    isError = true,
                    durationMs = elapsedToolMs
                )
            }
        )
    }

    private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
    private val PS_CLIXML_BLOCK_REGEX = Regex("""(?s)#< CLIXML\s*<Objs\b.*?</Objs>""")
    private val EXCESS_BLANK_LINES_REGEX = Regex("""\n{4,}""")
    private val LINE_NUMBER_PREFIX_REGEX = Regex("""^\s*(\d+):\s?(.*)$""")

    /**
     * `omp`-inspired Pre-Context Output Compression & Head+Tail Bookend Size Valve.
     * - Strips noisy ANSI escape codes, PowerShell `#< CLIXML ... </Objs>` progress XML, and excessive blank lines.
     * - Applies a Head + Tail Bookend Size Valve to `shell_exec`, `fs_search`, `fs_list`, and `fs_glob`
     *   so a single noisy command or directory dump never floods the context window, while NEVER
     *   truncating requested `fs_read` file line ranges.
     */
    fun compressToolOutputWithSizeValve(toolName: String, rawOutput: String): String {
        if (rawOutput.isEmpty()) return rawOutput
        val cleaned = rawOutput
            .replace(ANSI_ESCAPE_REGEX, "")
            .replace(PS_CLIXML_BLOCK_REGEX, "")
            .replace("#< CLIXML", "")
            .replace("\r\n", "\n")
            .replace(EXCESS_BLANK_LINES_REGEX, "\n\n\n")
            .trimEnd()
        if (toolName.equals("fs_read", ignoreCase = true)) {
            return cleaned
        }
        val lines = cleaned.lines()
        val (headKeep, tailKeep) = when (toolName.lowercase()) {
            "shell_exec" -> 90 to 90
            "fs_search" -> 110 to 50
            "fs_list", "fs_glob" -> 120 to 60
            else -> 160 to 80
        }
        val maxTotalLines = headKeep + tailKeep + 15
        if (lines.size <= maxTotalLines) {
            return cleaned
        }
        val omittedCount = lines.size - headKeep - tailKeep
        val headPart = lines.take(headKeep).joinToString("\n")
        val tailPart = lines.takeLast(tailKeep).joinToString("\n")
        return "$headPart\n… [$omittedCount middle lines omitted by size valve — refine query or use fs_read with start_line/max_lines] …\n$tailPart"
    }

    /**
     * Strips accidental `123: ` line-number prefixes from `old_text` / `new_text` when a model copies
     * lines directly from `fs_read` output (`File: ... \n 1: ... \n 2: ...`), unless the target file itself
     * actually contains those literal `123: ` prefixes.
     */
    fun stripAccidentalReadLinePrefixes(
        candidate: String,
        existingFileLf: String,
        requireStrippedInFile: Boolean = true
    ): String {
        val lines = candidate.lines()
        if (lines.size < 2) return candidate
        var matchedSeq = 0
        var prevNum = -1
        val strippedLines = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                strippedLines.add(line)
                continue
            }
            val m = LINE_NUMBER_PREFIX_REGEX.matchEntire(line)
            if (m != null) {
                val num = m.groupValues[1].toIntOrNull() ?: -1
                if (prevNum == -1 || num == prevNum + 1) {
                    prevNum = num
                    matchedSeq++
                    strippedLines.add(m.groupValues[2])
                } else {
                    return candidate
                }
            } else {
                return candidate
            }
        }
        if (matchedSeq < 2) return candidate
        val stripped = strippedLines.joinToString("\n")
        if (existingFileLf.contains(candidate)) return candidate
        return if (!requireStrippedInFile || existingFileLf.contains(stripped)) {
            stripped
        } else {
            candidate
        }
    }

    data class FuzzyEditResult(
        val updatedLf: String,
        val addedLines: Int,
        val removedLines: Int,
        val usedFuzzyWhitespace: Boolean
    )

    /**
     * `omp`-inspired 2-Tier Exact + Whitespace/Indentation-Normalized Self-Healing Edit Engine:
     * 1. Tier 1: Exact substring match (with automatic stripping of accidental `fs_read` line numbers).
     * 2. Tier 2: Line-by-line trimmed/whitespace-normalized match when exact match fails due to minor
     *    indentation or trailing-space drift, automatically preserving/re-aligning relative indentation!
     */
    fun applySingleEditWithFuzzySelfHealing(
        existingLf: String,
        rawOldText: String,
        rawNewText: String,
        replaceAll: Boolean,
        relativePath: String
    ): FuzzyEditResult {
        require(rawOldText.isNotEmpty()) { "old_text must not be empty." }
        val normOldRaw = rawOldText.replace("\r\n", "\n")
        val normNewRaw = rawNewText.replace("\r\n", "\n")
        val normOld = stripAccidentalReadLinePrefixes(normOldRaw, existingLf, requireStrippedInFile = false)
        val normNew = stripAccidentalReadLinePrefixes(normNewRaw, existingLf, requireStrippedInFile = false)

        // Tier 1: Exact Match
        if (existingLf.contains(normOld)) {
            val updated = if (replaceAll) {
                existingLf.replace(normOld, normNew)
            } else {
                val firstIdx = existingLf.indexOf(normOld)
                val secondIdx = existingLf.indexOf(normOld, startIndex = firstIdx + normOld.length)
                if (secondIdx != -1) {
                    val matchLines = mutableListOf<Int>()
                    var searchIdx = 0
                    while (true) {
                        val hit = existingLf.indexOf(normOld, startIndex = searchIdx)
                        if (hit == -1) break
                        val lineNum = existingLf.substring(0, hit).count { it == '\n' } + 1
                        matchLines.add(lineNum)
                        searchIdx = hit + normOld.length.coerceAtLeast(1)
                    }
                    error("old_text matched ${matchLines.size} locations in '$relativePath' (at lines ${matchLines.joinToString(", ")}). Include more surrounding context or set replace_all=true.")
                }
                existingLf.replaceFirst(normOld, normNew)
            }
            return FuzzyEditResult(
                updatedLf = updated,
                addedLines = normNew.lines().size,
                removedLines = normOld.lines().size,
                usedFuzzyWhitespace = false
            )
        }

        // Tier 2: Line-by-line Whitespace / Indentation Fuzzy Match
        val fileLines = existingLf.split('\n')
        val oldLines = normOld.split('\n')
        if (oldLines.isNotEmpty() && oldLines.size <= fileLines.size) {
            val oldTrimmed = oldLines.map { it.trim() }
            if (oldTrimmed.any { it.isNotEmpty() }) {
                val matchStartIndices = mutableListOf<Int>()
                val maxStart = fileLines.size - oldLines.size
                for (startIdx in 0..maxStart) {
                    var allMatch = true
                    for (j in oldLines.indices) {
                        if (fileLines[startIdx + j].trim() != oldTrimmed[j]) {
                            allMatch = false
                            break
                        }
                    }
                    if (allMatch) {
                        matchStartIndices.add(startIdx)
                    }
                }

                if (matchStartIndices.size == 1 || (replaceAll && matchStartIndices.isNotEmpty())) {
                    val newLinesRaw = normNew.split('\n')
                    val firstNonBlankOldIdx = oldLines.indexOfFirst { it.isNotBlank() }
                    val oldBaseIndent = if (firstNonBlankOldIdx >= 0) {
                        oldLines[firstNonBlankOldIdx].takeWhile { it == ' ' || it == '\t' }.length
                    } else 0

                    val resultLines = mutableListOf<String>()
                    var cursor = 0
                    for (matchStart in matchStartIndices) {
                        if (matchStart < cursor) continue
                        resultLines.addAll(fileLines.subList(cursor, matchStart))
                        val fileBaseIndentStr = if (firstNonBlankOldIdx >= 0) {
                            fileLines[matchStart + firstNonBlankOldIdx].takeWhile { it == ' ' || it == '\t' }
                        } else ""
                        val adjustedNewLines = newLinesRaw.map { nl ->
                            if (nl.isBlank()) ""
                            else {
                                val nlIndent = nl.takeWhile { it == ' ' || it == '\t' }.length
                                val relIndent = (nlIndent - oldBaseIndent).coerceAtLeast(0)
                                fileBaseIndentStr + " ".repeat(relIndent) + nl.trimStart()
                            }
                        }
                        resultLines.addAll(adjustedNewLines)
                        cursor = matchStart + oldLines.size
                    }
                    resultLines.addAll(fileLines.subList(cursor, fileLines.size))
                    return FuzzyEditResult(
                        updatedLf = resultLines.joinToString("\n"),
                        addedLines = newLinesRaw.size,
                        removedLines = oldLines.size,
                        usedFuzzyWhitespace = true
                    )
                } else if (matchStartIndices.size > 1) {
                    val lineNums = matchStartIndices.map { it + 1 }
                    error("old_text fuzzy-matched ${lineNums.size} locations in '$relativePath' (at lines ${lineNums.joinToString(", ")}). Include more surrounding lines or set replace_all=true.")
                }
            }
        }

        error("old_text was not found in '$relativePath' (${fileLines.size} lines). Use fs_read to inspect the exact lines before editing.")
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

    /**
     * Converts raw HTML into clean readable Markdown/text while leaving JSON, Markdown, and plain text intact.
     */
    fun convertHtmlToReadableText(raw: String, contentType: String = ""): String {
        val trimmed = raw.trim()
        val looksLikeHtml = contentType.contains("html", ignoreCase = true) ||
            trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            (trimmed.contains("<body", ignoreCase = true) && trimmed.contains("</body>", ignoreCase = true))
        if (!looksLikeHtml) return trimmed

        return trimmed
            .replace(Regex("(?is)<(script|style|noscript|svg|head)\\b[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?is)<!--.*?-->"), "")
            .replace(Regex("(?is)<h1\\b[^>]*>(.*?)</h1>"), "\n# $1\n")
            .replace(Regex("(?is)<h2\\b[^>]*>(.*?)</h2>"), "\n## $1\n")
            .replace(Regex("(?is)<h3\\b[^>]*>(.*?)</h3>"), "\n### $1\n")
            .replace(Regex("(?is)<h[4-6]\\b[^>]*>(.*?)</h[4-6]>"), "\n#### $1\n")
            .replace(Regex("(?is)<li\\b[^>]*>(.*?)</li>"), "\n- $1")
            .replace(Regex("(?is)<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")) { m ->
                val rawHref = m.groupValues[1].trim()
                val cleanHref = unwrapDuckDuckGoRedirectUrl(rawHref)
                val label = m.groupValues[2].replace(Regex("(?is)<[^>]+>"), "").trim()
                if (label.isNotEmpty() && cleanHref.startsWith("http")) "[$label]($cleanHref)" else label
            }
            .replace(Regex("(?is)<(br|hr)\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|section|article|tr|blockquote|pre|ul|ol|table)>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    internal data class FreeSearchResultItem(
        val title: String,
        val url: String,
        val snippet: String
    )

    internal fun urlEncodeQuery(raw: String): String {
        val sb = StringBuilder()
        for (b in raw.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code
            ) {
                sb.append(c.toChar())
            } else if (c == ' '.code) {
                sb.append('+')
            } else {
                sb.append('%')
                sb.append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    internal fun urlDecodeComponent(encoded: String): String {
        val clean = encoded.replace('+', ' ')
        val out = ByteArray(clean.length)
        var outIdx = 0
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            if (ch == '%' && i + 2 < clean.length) {
                val hex = clean.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out[outIdx++] = hex.toByte()
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().encodeToByteArray()
            for (b in bytes) {
                if (outIdx < out.size) out[outIdx++] = b
            }
            i++
        }
        return out.decodeToString(0, outIdx)
    }

    internal fun unwrapDuckDuckGoRedirectUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().let {
            if (it.startsWith("//")) "https:$it" else it
        }
        if (trimmed.contains("uddg=")) {
            val uddgParam = trimmed.substringAfter("uddg=").substringBefore("&")
            val decoded = urlDecodeComponent(uddgParam).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        }
        return trimmed
    }

    internal fun parseDuckDuckGoMarkdownResults(markdownText: String, maxResults: Int): List<FreeSearchResultItem> {
        val results = mutableListOf<FreeSearchResultItem>()
        val seenUrls = HashSet<String>()
        val lines = markdownText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val linkRegex = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")

        for ((idx, line) in lines.withIndex()) {
            for (match in linkRegex.findAll(line)) {
                val title = match.groupValues[1].trim()
                val rawUrl = match.groupValues[2].trim()
                val url = unwrapDuckDuckGoRedirectUrl(rawUrl)
                val lowerUrl = url.lowercase()
                if (!url.startsWith("http") ||
                    lowerUrl.contains("duckduckgo.com") ||
                    lowerUrl.contains("bing.com/aclick") ||
                    title.length < 3 ||
                    !seenUrls.add(lowerUrl)
                ) {
                    continue
                }
                val nextSnippet = lines.drop(idx + 1).take(3)
                    .firstOrNull { cand ->
                        !cand.startsWith("[") && cand.length > 24 && !cand.startsWith("http")
                    }.orEmpty()
                results.add(
                    FreeSearchResultItem(
                        title = title,
                        url = url,
                        snippet = nextSnippet.take(240)
                    )
                )
                if (results.size >= maxResults) return results
            }
        }
        return results
    }

    internal fun parseWikipediaSearchJson(rawJson: String, maxResults: Int): List<FreeSearchResultItem> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val searchArr = (root["query"] as? JsonObject)?.get("search") as? JsonArray ?: return emptyList()
        val results = mutableListOf<FreeSearchResultItem>()
        for (el in searchArr) {
            val obj = el as? JsonObject ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) continue
            val rawSnippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .trim()
            val wikiSlug = title.replace(' ', '_')
            val url = "https://en.wikipedia.org/wiki/$wikiSlug"
            results.add(FreeSearchResultItem(title = "$title — Wikipedia", url = url, snippet = rawSnippet))
            if (results.size >= maxResults) break
        }
        return results
    }

    internal fun parseHackerNewsAlgoliaJson(rawJson: String, maxResults: Int): List<FreeSearchResultItem> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val hitsArr = root["hits"] as? JsonArray ?: return emptyList()
        val results = mutableListOf<FreeSearchResultItem>()
        for (el in hitsArr) {
            val obj = el as? JsonObject ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) continue
            val objId = obj["objectID"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                ?: if (objId.isNotEmpty()) "https://news.ycombinator.com/item?id=$objId" else continue
            val points = obj["points"]?.jsonPrimitive?.intOrNull ?: 0
            val comments = obj["num_comments"]?.jsonPrimitive?.intOrNull ?: 0
            results.add(
                FreeSearchResultItem(
                    title = title,
                    url = url,
                    snippet = "Hacker News discussion ($points points, $comments comments)."
                )
            )
            if (results.size >= maxResults) break
        }
        return results
    }

    /**
     * Executes a 100% free, zero-API-key multi-engine web search cascade:
     * 1. DuckDuckGo HTML (`https://html.duckduckgo.com/html/?q=...`)
     * 2. Wikipedia OpenSearch / Query API (`https://en.wikipedia.org/w/api.php?...`)
     * 3. HackerNews Algolia Search API (`https://hn.algolia.com/api/v1/search?query=...`)
     */
    fun executeFreeWebSearch(query: String, maxResults: Int = 6): Result<String> = runCatching {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Search query must not be blank." }
        val encoded = urlEncodeQuery(cleanQuery)
        val collected = mutableListOf<FreeSearchResultItem>()
        val seenUrls = HashSet<String>()

        fun addUnique(items: List<FreeSearchResultItem>) {
            for (item in items) {
                val key = item.url.lowercase()
                if (seenUrls.add(key)) {
                    collected.add(item)
                    if (collected.size >= maxResults) return
                }
            }
        }

        // 1. Primary: DuckDuckGo HTML search
        val ddgUrl = "https://html.duckduckgo.com/html/?q=$encoded"
        PlatformBridge.fetchUrlText(ddgUrl, maxChars = 48_000).onSuccess { ddgMd ->
            addUnique(parseDuckDuckGoMarkdownResults(ddgMd, maxResults))
        }

        // 2. Fallback / Supplement: Wikipedia Search API
        if (collected.size < maxResults) {
            val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=1&format=json&srlimit=${maxResults.coerceAtMost(5)}"
            PlatformBridge.fetchUrlText(wikiUrl, maxChars = 32_000).onSuccess { wikiJson ->
                addUnique(parseWikipediaSearchJson(wikiJson, maxResults - collected.size))
            }
        }

        // 3. Fallback / Supplement: HackerNews Algolia Search API
        if (collected.size < maxResults) {
            val hnUrl = "https://hn.algolia.com/api/v1/search?query=$encoded&tags=story&hitsPerPage=${maxResults.coerceAtMost(5)}"
            PlatformBridge.fetchUrlText(hnUrl, maxChars = 32_000).onSuccess { hnJson ->
                addUnique(parseHackerNewsAlgoliaJson(hnJson, maxResults - collected.size))
            }
        }

        if (collected.isEmpty()) {
            "No web search results found for '$cleanQuery'."
        } else {
            buildString {
                appendLine("### Web Search Results for \"$cleanQuery\"")
                collected.forEachIndexed { idx, item ->
                    appendLine("${idx + 1}. **[${item.title}](${item.url})**")
                    appendLine("   URL: ${item.url}")
                    if (item.snippet.isNotBlank()) {
                        appendLine("   Snippet: ${item.snippet}")
                    }
                }
            }.trim()
        }
    }

    /**
     * Pure-Kotlin deterministic recursive-descent mathematical evaluator for `calc_eval`.
     * Supports +, -, *, /, %, ^, parentheses, pi, e, sqrt, cbrt, abs, round, floor, ceil, sin, cos, tan, asin, acos, atan, ln, log, log10, log2, exp.
     */
    fun evaluateMathExpression(expression: String): String {
        val src = expression.trim()
        require(src.isNotEmpty()) { "Expression must not be empty." }
        val parser = MathExprParser(src)
        val value = parser.parseAll()
        val formatted = if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        return "$src = $formatted"
    }

    private class MathExprParser(private val src: String) {
        private var pos = 0

        fun parseAll(): Double {
            val v = parseExpr()
            skipWs()
            require(pos == src.length) { "Unexpected trailing text '${src.substring(pos)}' in expression." }
            return v
        }

        private fun skipWs() {
            while (pos < src.length && (src[pos].isWhitespace() || src[pos] == '_')) pos++
        }

        private fun eat(ch: Char): Boolean {
            skipWs()
            if (pos < src.length && src[pos] == ch) {
                pos++
                return true
            }
            return false
        }

        private fun parseExpr(): Double {
            var x = parseTerm()
            while (true) {
                skipWs()
                when {
                    eat('+') -> x += parseTerm()
                    eat('-') -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parsePower()
            while (true) {
                skipWs()
                when {
                    eat('*') -> x *= parsePower()
                    eat('/') -> {
                        val d = parsePower()
                        require(d != 0.0) { "Division by zero." }
                        x /= d
                    }
                    eat('%') -> {
                        val d = parsePower()
                        require(d != 0.0) { "Modulo by zero." }
                        x %= d
                    }
                    else -> return x
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipWs()
            return if (eat('^')) {
                base.pow(parsePower())
            } else {
                base
            }
        }

        private fun parseUnary(): Double {
            skipWs()
            if (eat('+')) return parseUnary()
            if (eat('-')) return -parseUnary()
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (eat('(')) {
                val v = parseExpr()
                require(eat(')')) { "Missing closing parenthesis ')'." }
                return v
            }
            val start = pos
            if (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) {
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == '_')) pos++
                if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                    pos++
                    if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                    while (pos < src.length && src[pos].isDigit()) pos++
                }
                val numStr = src.substring(start, pos).replace("_", "")
                return numStr.toDoubleOrNull() ?: error("Invalid number '$numStr'.")
            }
            if (pos < src.length && src[pos].isLetter()) {
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                val ident = src.substring(start, pos).lowercase()
                skipWs()
                if (eat('(')) {
                    val args = mutableListOf<Double>()
                    if (!eat(')')) {
                        do {
                            args.add(parseExpr())
                        } while (eat(','))
                        require(eat(')')) { "Missing ')' after function '$ident' arguments." }
                    }
                    require(args.isNotEmpty()) { "Function '$ident' requires at least 1 argument." }
                    val a0 = args[0]
                    return when (ident) {
                        "sqrt" -> kotlin.math.sqrt(a0)
                        "cbrt" -> kotlin.math.cbrt(a0)
                        "abs" -> kotlin.math.abs(a0)
                        "round" -> kotlin.math.round(a0)
                        "floor" -> kotlin.math.floor(a0)
                        "ceil" -> kotlin.math.ceil(a0)
                        "sin" -> kotlin.math.sin(a0)
                        "cos" -> kotlin.math.cos(a0)
                        "tan" -> kotlin.math.tan(a0)
                        "asin" -> kotlin.math.asin(a0)
                        "acos" -> kotlin.math.acos(a0)
                        "atan" -> kotlin.math.atan(a0)
                        "ln" -> kotlin.math.ln(a0)
                        "log", "log10" -> kotlin.math.log10(a0)
                        "log2" -> kotlin.math.log2(a0)
                        "exp" -> kotlin.math.exp(a0)
                        "pow" -> {
                            require(args.size >= 2) { "pow(x, y) requires 2 arguments." }
                            a0.pow(args[1])
                        }
                        "min" -> args.minOrNull() ?: a0
                        "max" -> args.maxOrNull() ?: a0
                        else -> error("Unknown math function '$ident'.")
                    }
                }
                return when (ident) {
                    "pi" -> kotlin.math.PI
                    "e" -> kotlin.math.E
                    else -> error("Unknown constant '$ident'.")
                }
            }
            error("Unexpected character at position $pos in '$src'.")
        }
    }
    /**
     * Parses `todo_write` or `goal_complete` tool calls into an updated `GoalTrackerState`.
     */
    fun applyGoalToolUpdate(current: GoalTrackerState?, tc: ToolCallRecord): GoalTrackerState? {
        if (tc.isError) return current
        val args = runCatching {
            json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse { return current }

        return when (tc.name) {
            "todo_write" -> {
                val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: current?.goalObjective.orEmpty()
                val todosArr = args["todos"] as? JsonArray ?: JsonArray(emptyList())
                val parsed = todosArr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { "•" } ?: "•"
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val status = obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "pending"
                    TodoItem(id = id, content = content, status = status)
                }
                val allDone = parsed.isNotEmpty() && parsed.all {
                    it.status == "completed" || it.status == "cancelled"
                }
                GoalTrackerState(
                    goalObjective = goal,
                    todos = parsed,
                    isCompleted = allDone,
                    verificationSummary = current?.verificationSummary.orEmpty()
                )
            }
            "goal_complete" -> {
                val summary = args["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val verification = args["verification"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val base = current ?: GoalTrackerState()
                base.copy(
                    todos = base.todos.map {
                        if (it.status == "in_progress" || it.status == "pending") it.copy(status = "completed") else it
                    },
                    isCompleted = true,
                    verificationSummary = listOf(summary, verification).filter { it.isNotBlank() }.joinToString(" — ")
                )
            }
            else -> current
        }
    }

    /**
     * 3-Layer Rolling Context Compactor.
     * Keeps the most recent `keepRecentCount` tool outputs verbatim, while compacting older verbose
     * tool outputs (>850 chars) into concise 1-line receipts so multi-day runs never overflow context.
     */
    fun compactStaleToolOutputText(
        stepIndex: Int,
        totalSteps: Int,
        toolName: String,
        rawOutput: String,
        keepRecentCount: Int = 8
    ): String {
        if (totalSteps - stepIndex <= keepRecentCount || rawOutput.length <= 850) {
            return rawOutput
        }
        val firstLine = rawOutput.lineSequence().firstOrNull()?.take(140).orEmpty()
        return "[Compacted step #$stepIndex ($toolName): ${rawOutput.length} chars (${rawOutput.lines().size} lines) — \"$firstLine…\". Re-run tool if exact lines are needed.]"
    }

    /**
     * Repeated-Failure Circuit Breaker & Stop-Hook Work-Check Gate.
     * - Replaces the arbitrary 64-step ceiling with intelligent progress vs. stagnation tracking.
     * - Allows unlimited productive steps over hours/days/weeks while halting repeated identical error loops.
     */
    class LoopCircuitBreaker(initialGoal: GoalTrackerState? = null) {
        var goalState: GoalTrackerState? = initialGoal
            private set

        private var lastFingerprint: String = ""
        private var lastToolName: String = ""
        var consecutiveIdenticalCalls: Int = 0
            private set
        var consecutiveTotalErrors: Int = 0
            private set
        var consecutiveUnproductiveStopHookNudges: Int = 0
            private set

        fun onToolExecuted(tc: ToolCallRecord): ToolCallRecord {
            val fp = "${tc.name}:${tc.argumentsJson.trim().hashCode()}"
            if (fp == lastFingerprint) {
                consecutiveIdenticalCalls++
            } else {
                lastFingerprint = fp
                lastToolName = tc.name
                consecutiveIdenticalCalls = 1
            }

            if (tc.isError) {
                consecutiveTotalErrors++
            } else {
                consecutiveTotalErrors = 0
                consecutiveUnproductiveStopHookNudges = 0
            }

            val updatedGoal = applyGoalToolUpdate(goalState, tc)
            if (updatedGoal != goalState) {
                goalState = updatedGoal
                consecutiveUnproductiveStopHookNudges = 0
            }

            // Inject diagnostic pivot warning if the model repeats the same failing call twice
            val shouldWarn = (consecutiveIdenticalCalls == 2 && tc.isError) ||
                (consecutiveIdenticalCalls == 3 && tc.name != "fs_read" && tc.name != "todo_write")
            return if (shouldWarn) {
                tc.copy(
                    resultText = tc.resultText +
                        "\n\n[HARNESS CIRCUIT BREAKER WARNING]: You have called '${tc.name}' with identical arguments $consecutiveIdenticalCalls times in a row" +
                        (if (tc.isError) " and it failed each time." else ".") +
                        " Do NOT repeat this exact call again. Diagnose the root cause, inspect the target file/state, or try a different strategy."
                )
            } else {
                tc
            }
        }

        fun checkTripped(): String? {
            if (consecutiveIdenticalCalls >= 3 && consecutiveTotalErrors >= 2) {
                return "Circuit Breaker halted loop: '$lastToolName' failed $consecutiveIdenticalCalls times in a row with identical arguments."
            }
            if (consecutiveTotalErrors >= 6) {
                return "Circuit Breaker halted loop: 6 consecutive tool errors occurred without a single successful step."
            }
            if (consecutiveUnproductiveStopHookNudges >= 3) {
                return "Circuit Breaker halted loop: Work-check nudged 3 times without further tool or todo progress."
            }
            return null
        }

        /**
         * Stop-Hook Work-Check Gate:
         * Called when the model finishes a turn with zero tool calls (`toolCalls.isEmpty()`).
         * - If no `/goal` or `todo_write` checklist is active (normal chat, roleplay, short writing), returns `null` -> immediate exit!
         * - If `goalState` has incomplete (`pending` / `in_progress`) items and the circuit breaker hasn't tripped,
         *   returns the Work-Check continuation prompt so the model continues until 100% complete!
         */
        fun evaluateStopHookPrompt(): String? {
            val gs = goalState ?: return null
            if (!gs.hasPendingOrInProgress) return null
            if (consecutiveUnproductiveStopHookNudges >= 3) return null

            consecutiveUnproductiveStopHookNudges++
            val incompleteItems = gs.todos.filter {
                val s = it.status.lowercase()
                s == "pending" || s == "in_progress"
            }
            return if (incompleteItems.isNotEmpty()) {
                val remainingList = incompleteItems.joinToString("\n") {
                    "  - [${it.status.uppercase()}] (${it.id}) ${it.content}"
                }
                "[Harness Work-Check — Goal Incomplete (${gs.completedCount}/${gs.todos.size} done)]:\n" +
                    "You paused before completing all items in your checklist:\n$remainingList\n\n" +
                    "Continue executing the next incomplete step now. Verify your work (e.g. inspect modified files or run checks) before marking each step `completed` with `todo_write`, and call `goal_complete` only once 100% of acceptance criteria are verified."
            } else {
                "[Harness Work-Check — Goal Active]:\n" +
                    "Objective: \"${gs.goalObjective}\"\n" +
                    "Break this goal into verifiable steps using `todo_write` (mark step 1 `in_progress`), execute and verify each step thoroughly, and call `goal_complete` when 100% finished."
            }
        }
    }
}
