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
        val requiresWritePermission: Boolean
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
        )
    )

    fun activeToolsForMode(mode: WorkspaceMode, workspacePath: String): List<ToolDefinition> {
        if (mode == WorkspaceMode.OFF || workspacePath.isBlank()) return emptyList()
        return if (mode == WorkspaceMode.READ_ONLY) {
            ALL_TOOLS.filter { !it.requiresWritePermission }
        } else {
            ALL_TOOLS
        }
    }

    /**
     * Formats active workspace tools for Google Gemini Interactions API (`tools` array).
     */
    fun buildGeminiInteractionsTools(mode: WorkspaceMode, workspacePath: String): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath)) {
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
    fun buildOpenAiResponsesTools(mode: WorkspaceMode, workspacePath: String): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath)) {
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
    fun buildOpenAiChatCompletionsTools(mode: WorkspaceMode, workspacePath: String): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath)) {
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
    fun buildAnthropicTools(mode: WorkspaceMode, workspacePath: String): JsonArray = buildJsonArray {
        for (tool in activeToolsForMode(mode, workspacePath)) {
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
}
