package io.parchment.ai.platform

data class PickedBinaryFile(
    val fileName: String,
    val bytes: ByteArray
)

expect object PlatformBridge {
    val platformName: String
    val isAndroid: Boolean

    fun currentTimeMillis(): Long

    // Local persistent storage (zero-trace on uninstall)
    fun getAppStorageDirectoryPath(): String
    fun loadAppStateJson(): String?
    fun saveAppStateJson(json: String)
    fun wipeAllLocalData(): Boolean

    // Native File Pickers for PNG / JSON Card & System Prompt Import/Export
    suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile?
    suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String?
    suspend fun pickWorkspaceDirectory(currentPath: String): String?

    // Built-in Local File System Agent Harness (Sandboxed to workspaceRoot)
    fun workspaceList(workspaceRoot: String, relativePath: String = "", recursive: Boolean = false): Result<String>
    fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int = 1, maxLines: Int = 250): Result<String>
    fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String>
    fun workspaceEdit(workspaceRoot: String, relativePath: String, oldText: String, newText: String, replaceAll: Boolean = false): Result<String>
    fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String = ""): Result<String>

    // Long Decode Lifecycle Hook (Android 16 Foreground Service / Desktop status)
    fun notifyLongDecodeStart(modelName: String)
    fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int)
    fun notifyLongDecodeComplete()
}
