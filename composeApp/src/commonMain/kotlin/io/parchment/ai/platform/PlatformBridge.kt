package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import io.parchment.ai.model.StoredAppFileItem

data class PickedBinaryFile(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String = ""
)

/**
 * Inspects binary magic headers, explicit MIME type hints, and file extensions to accurately
 * classify images (PNG, GIF, WebP, JPEG, BMP), videos (MP4, WebM, MOV), audio, and PDFs—even
 * when Android Gboard Emoji Kitchen, GIF pickers, or clipboard providers supply extensionless URIs.
 */
fun normalizePickedBinaryFile(
    rawFileName: String,
    bytes: ByteArray,
    explicitMimeType: String = "",
    fallbackTimestamp: Long = 0L
): PickedBinaryFile {
    val cleanHint = explicitMimeType.trim().lowercase().substringBefore(';')
    val magicMime: String = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() -> "image/gif"
        bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 2 &&
            bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "image/bmp"
        bytes.size >= 5 &&
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[4] == 0x2D.toByte() -> "application/pdf"
        bytes.size >= 12 &&
            bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() -> "video/mp4"
        bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "video/webm"
        bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x41.toByte() &&
            bytes[10] == 0x56.toByte() && bytes[11] == 0x45.toByte() -> "audio/wav"
        bytes.size >= 4 &&
            bytes[0] == 0x4F.toByte() && bytes[1] == 0x67.toByte() &&
            bytes[2] == 0x67.toByte() && bytes[3] == 0x53.toByte() -> "audio/ogg"
        bytes.size >= 4 &&
            bytes[0] == 0x66.toByte() && bytes[1] == 0x4C.toByte() &&
            bytes[2] == 0x61.toByte() && bytes[3] == 0x43.toByte() -> "audio/flac"
        bytes.size >= 3 &&
            bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() &&
            bytes[2] == 0x33.toByte() -> "audio/mp3"
        else -> ""
    }

    val lowerName = rawFileName.trim().lowercase()
    val extMime: String = when {
        lowerName.endsWith(".png") -> "image/png"
        lowerName.endsWith(".gif") -> "image/gif"
        lowerName.endsWith(".webp") -> "image/webp"
        lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
        lowerName.endsWith(".bmp") -> "image/bmp"
        lowerName.endsWith(".pdf") -> "application/pdf"
        lowerName.endsWith(".mp4") -> "video/mp4"
        lowerName.endsWith(".webm") -> "video/webm"
        lowerName.endsWith(".mov") -> "video/quicktime"
        lowerName.endsWith(".mkv") -> "video/x-matroska"
        lowerName.endsWith(".avi") -> "video/x-msvideo"
        lowerName.endsWith(".mp3") -> "audio/mp3"
        lowerName.endsWith(".wav") -> "audio/wav"
        lowerName.endsWith(".ogg") -> "audio/ogg"
        lowerName.endsWith(".flac") -> "audio/flac"
        lowerName.endsWith(".m4a") -> "audio/mp4"
        else -> ""
    }

    val resolvedMime = when {
        magicMime.isNotEmpty() -> magicMime
        cleanHint.isNotEmpty() && cleanHint != "*/*" && cleanHint != "application/octet-stream" -> cleanHint
        extMime.isNotEmpty() -> extMime
        else -> cleanHint
    }

    val canonicalExt = when (resolvedMime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/bmp" -> "bmp"
        "application/pdf" -> "pdf"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/flac" -> "flac"
        "audio/mp3", "audio/mpeg" -> "mp3"
        "text/plain" -> "txt"
        "text/markdown" -> "md"
        "application/json" -> "json"
        else -> ""
    }

    val baseName = rawFileName.trim().ifBlank {
        val prefix = when {
            resolvedMime.startsWith("image/") -> "pasted-image"
            resolvedMime.startsWith("video/") -> "pasted-video"
            resolvedMime == "application/pdf" -> "document"
            else -> "attachment"
        }
        val ts = if (fallbackTimestamp > 0L) fallbackTimestamp else 1L
        if (canonicalExt.isNotEmpty()) "$prefix-$ts.$canonicalExt" else "$prefix-$ts"
    }

    val finalFileName = if (canonicalExt.isNotEmpty()) {
        val hasKnownExt = lowerName.endsWith(".$canonicalExt") ||
            (canonicalExt == "jpg" && lowerName.endsWith(".jpeg"))
        if (hasKnownExt) {
            baseName
        } else {
            val stripped = baseName.substringBeforeLast('.', baseName).ifBlank { baseName }
            "$stripped.$canonicalExt"
        }
    } else {
        baseName
    }

    return PickedBinaryFile(
        fileName = finalFileName,
        bytes = bytes,
        mimeType = resolvedMime
    )
}

expect object PlatformBridge {
    val platformName: String
    val isAndroid: Boolean

    fun currentTimeMillis(): Long

    // Local persistent storage (split into parchment-settings.json + per-chat files in chats/)
    fun getAppStorageDirectoryPath(): String
    fun getChatsStorageDirectoryPath(): String
    fun loadAppStateJson(): String?
    fun saveAppStateJson(json: String)
    fun loadChatSessionJsonById(sessionId: String): String?
    fun loadAllChatSessionJsons(): List<String>
    fun saveChatSessionJson(sessionId: String, json: String)
    fun deleteChatSessionFile(sessionId: String)
    fun registerStateFlushCallback(callback: () -> Unit)
    fun flushPendingStateNow()
    fun registerBackHandler(handler: (() -> Boolean)?)
    fun handleSystemBackPressed(): Boolean
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestUnrestrictedBackgroundBattery()
    fun wipeAllLocalData(): Boolean
    fun listAppStorageFiles(): List<StoredAppFileItem>
    fun deleteAppStorageFile(fullPath: String): Boolean

    // Native File Pickers for PNG / JSON Card & System Prompt Import/Export & Custom TTF Fonts
    suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile?
    suspend fun pickFilesForImport(allowedExtensions: List<String>): List<PickedBinaryFile>
    suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String?
    suspend fun exportMultipleFilesToDirectory(files: List<Pair<String, ByteArray>>): String?
    suspend fun pickWorkspaceDirectory(currentPath: String): String?
    suspend fun pickFontFile(dialogTitle: String = "Select .TTF or .OTF Font"): String?
    fun loadCustomFontFamily(fontPath: String): FontFamily?
    fun loadBundledFontFamily(bundleName: String): FontFamily?

    // Built-in Local File System Agent Harness (Sandboxed to workspaceRoot)
    fun workspaceList(workspaceRoot: String, relativePath: String = "", recursive: Boolean = false): Result<String>
    fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int = 200): Result<String>
    fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int = 1, maxLines: Int = 250): Result<String>
    fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String>
    fun workspaceEdit(workspaceRoot: String, relativePath: String, oldText: String, newText: String, replaceAll: Boolean = false): Result<String>
    fun workspaceMultiEdit(workspaceRoot: String, relativePath: String, edits: List<Triple<String, String, Boolean>>): Result<String>
    fun workspaceMove(workspaceRoot: String, sourceRelativePath: String, destRelativePath: String): Result<String>
    fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String>
    fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String = ""): Result<String>
    fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int = 30,
        onOutputDelta: ((String) -> Unit)? = null
    ): Result<String>
    fun killActiveShellProcesses()
    fun fetchUrlText(url: String, maxChars: Int = 24000): Result<String>
    // Long Decode Lifecycle Hook (Android Foreground Service / Desktop status)
    fun notifyLongDecodeStart(modelName: String)
    fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int)
    fun notifyLongDecodeComplete()

    // In-App Zero-Browser OTA Update Installer
    fun installOtaUpdate(fileName: String, bytes: ByteArray): Result<String>

    // Generated Image & Video Media Storage, Per-Chat Media Bundling, and Native Playback
    fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray, sessionId: String = ""): String
    fun relocateLegacyMediaToChatFolder(filePath: String, sessionId: String): String
    fun createZipArchive(
        entries: List<Pair<String, ByteArray>>,
        fileEntries: List<Pair<String, String>> = emptyList()
    ): ByteArray
    fun extractZipArchiveToChatStorage(zipBytes: ByteArray): List<String>
    fun readBinaryFileBytes(filePath: String): ByteArray?
    fun loadImageBitmapFromFile(filePath: String): ImageBitmap?
    fun openOrPlayMedia(localFilePathOrUrl: String)

    // Clipboard Image/Video/File Paste & Native Drag-and-Drop Support
    fun readClipboardFiles(): List<PickedBinaryFile>
    fun registerFileDropCallbacks(
        onDragHover: (Boolean) -> Unit,
        onFilesDropped: (List<PickedBinaryFile>) -> Unit
    )

    // Native Text-to-Speech (TTS) & Live Voice Input (STT)
    fun speakTextAloud(text: String, onFinished: () -> Unit = {})
    fun stopSpeakingAloud()
    fun isSpeakingAloud(): Boolean
    fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit = {})
    fun stopVoiceInput()
}
