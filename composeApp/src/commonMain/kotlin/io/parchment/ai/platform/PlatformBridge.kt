package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily

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

    // Native File Pickers for PNG / JSON Card & System Prompt Import/Export & Custom TTF Fonts
    suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile?
    suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String?
    suspend fun pickWorkspaceDirectory(currentPath: String): String?
    suspend fun pickFontFile(dialogTitle: String = "Select .TTF or .OTF Font"): String?
    fun loadCustomFontFamily(fontPath: String): FontFamily?
    fun loadBundledFontFamily(bundleName: String): FontFamily?

    // Built-in Local File System Agent Harness (Sandboxed to workspaceRoot)
    fun workspaceList(workspaceRoot: String, relativePath: String = "", recursive: Boolean = false): Result<String>
    fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int = 1, maxLines: Int = 250): Result<String>
    fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String>
    fun workspaceEdit(workspaceRoot: String, relativePath: String, oldText: String, newText: String, replaceAll: Boolean = false): Result<String>
    fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String = ""): Result<String>
    fun workspaceShellExec(workspaceRoot: String, command: String, timeoutSeconds: Int = 30): Result<String>

    // Long Decode Lifecycle Hook (Android 16 Foreground Service / Desktop status)
    fun notifyLongDecodeStart(modelName: String)
    fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int)
    fun notifyLongDecodeComplete()

    // In-App Zero-Browser OTA Update Installer
    fun installOtaUpdate(fileName: String, bytes: ByteArray): Result<String>

    // Generated Image & Video Media Storage and Native Playback
    fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray): String
    fun readBinaryFileBytes(filePath: String): ByteArray?
    fun loadImageBitmapFromFile(filePath: String): ImageBitmap?
    fun openOrPlayMedia(localFilePathOrUrl: String)
}
