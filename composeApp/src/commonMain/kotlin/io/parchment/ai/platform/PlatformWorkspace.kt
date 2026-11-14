package io.parchment.ai.platform

data class ComputerAction(val action: String, val x: Int?, val y: Int?, val text: String, val keys: String, val amount: Int)
data class ComputerScreenshot(val jpegBase64: String, val width: Int, val height: Int)

/** Blocking calls; [act] takes coordinates from the most recent screenshot. */
interface ComputerControl {
    val isAvailable: Boolean
    fun screenshot(): Result<ComputerScreenshot>
    fun act(action: ComputerAction): Result<String>
}

interface McpProcess {
    fun writeLine(line: String)
    /** Blocks for the next line; null means the process has ended. */
    fun readLine(): String?
    fun close()
}

expect object PlatformWorkspace {
    // Built-in Local File System Agent Harness (Sandboxed to workspaceRoot)
    fun workspaceList(workspaceRoot: String, relativePath: String = "", recursive: Boolean = false): Result<String>
    fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int = 200): Result<String>
    fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int = 1, maxLines: Int = 250): Result<String>
    fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String>
    /** Appends [content] to a file without reading it back, so arbitrarily large files are never truncated. */
    fun workspaceAppend(workspaceRoot: String, relativePath: String, content: String): Result<String>
    fun workspaceEdit(workspaceRoot: String, relativePath: String, oldText: String, newText: String, replaceAll: Boolean = false): Result<String>
    fun workspaceMultiEdit(workspaceRoot: String, relativePath: String, edits: List<Triple<String, String, Boolean>>): Result<String>
    fun workspaceMove(workspaceRoot: String, sourceRelativePath: String, destRelativePath: String): Result<String>
    fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String>
    fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String = ""): Result<String>
    fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int = 30,
        onOutputDelta: ((String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Result<String>
    fun fetchUrlText(url: String, maxChars: Int = 24000, isCancelled: (() -> Boolean)? = null): Result<String>
    /** Downloads from public internet addresses only, checking every redirect. */
    fun fetchPublicBytes(url: String, maxBytes: Int, isCancelled: (() -> Boolean)? = null): Result<ByteArray>
    val computerControl: ComputerControl
    val supportsLocalMcpServers: Boolean
    /** Starts a local MCP server, or returns null on platforms that cannot run one. */
    fun startMcpProcess(command: String): McpProcess?
}
