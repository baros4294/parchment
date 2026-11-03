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

    fun activeToolsForMode(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false
    ): List<ToolDefinition> {
        val hasMountedWorkspace = mode != WorkspaceMode.OFF && workspacePath.isNotBlank()
        if (!hasMountedWorkspace && !goalModeActive) return emptyList()
        return ALL_TOOLS.filter { tool ->
            if (!hasMountedWorkspace) {
                !tool.requiresWorkspaceFolder
            } else {
                val writeOk = mode == WorkspaceMode.READ_WRITE || !tool.requiresWritePermission
                val shellOk = !tool.requiresShellPermission || (mode == WorkspaceMode.READ_WRITE && allowShell)
                writeOk && shellOk
            }
        }
    }

    /**
     * Auto-discovers AGENTS.md, CLAUDE.md, PARCHMENT.md, and PARCHMENT_MEMORY.md in the workspace root.
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
    fun buildGeminiInteractionsTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive)) {
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
    fun buildOpenAiResponsesTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive)) {
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
    fun buildOpenAiChatCompletionsTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive)) {
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
    fun buildAnthropicTools(
        mode: WorkspaceMode,
        workspacePath: String,
        allowShell: Boolean = false,
        goalModeActive: Boolean = false
    ): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath, allowShell, goalModeActive)) {
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
            "fs_write" -> "Writing ${strArg("path").ifBlank { "file" }}"
            "fs_list" -> "Listing workspace ${strArg("path").ifBlank { "root" }}"
            "fs_search" -> "Searching workspace for '${strArg("query").take(36)}'"
            "shell_exec" -> "Running shell: ${strArg("command").lineSequence().firstOrNull()?.take(52).orEmpty()}"
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
        argumentsJson: String
    ): ToolCallRecord {
        val isFolderFreeTool = toolName == "todo_write" || toolName == "goal_complete" || toolName == "dice_roll"
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
