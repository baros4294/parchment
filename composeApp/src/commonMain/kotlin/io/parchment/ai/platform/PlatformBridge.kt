package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import io.parchment.ai.model.StoredAppFileItem
import io.parchment.ai.network.MAX_INLINE_ATTACHMENT_BYTES
import io.parchment.ai.network.OtaStagedDownload

data class PickedBinaryFile(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String = "",
    /** The file exceeded its limit and was not read; [bytes] is empty. */
    val oversized: Boolean = false
)

private const val MAX_IMPORTED_DOCUMENT_BYTES = 256L * 1024L * 1024L
internal const val MAX_INLINE_TEXT_ATTACHMENT_BYTES = 8L * 1024L * 1024L

/** Attachments must fit in a request; card and settings files only need to fit in memory. */
fun maxPickedFileBytes(allowedExtensions: List<String>): Long =
    if (allowedExtensions.isEmpty() || "*" in allowedExtensions) MAX_INLINE_ATTACHMENT_BYTES else MAX_IMPORTED_DOCUMENT_BYTES

/** Text to place in the request, or null when the file is binary or too large to inline. */
internal fun inlineAttachmentText(bytes: ByteArray): String? {
    if (bytes.size > MAX_INLINE_TEXT_ATTACHMENT_BYTES) return null
    return runCatching { bytes.decodeToString() }.getOrNull()?.takeUnless { '\u0000' in it }
}

/**
 * Inspects binary magic headers, explicit MIME type hints, and file extensions to accurately
 * classify images (PNG, GIF, WebP, JPEG, BMP), videos (MP4, WebM, MOV), audio, and PDFs—even
 * when Android Gboard Emoji Kitchen, GIF pickers, or clipboard providers supply extensionless URIs.
 */
/** One file chosen for chat import, with what decoding it produced. */
class ChatImportOutcome<T>(val fileName: String, val result: Result<T>)

/** One chat in an export bundle: its JSON entry and the local media files to include with it. */
class ChatBundleEntry(val jsonPath: String, val json: ByteArray, val mediaFiles: List<Pair<String, String>>)

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

expect object PlatformBridge {
    val platformName: String
    val isAndroid: Boolean
    val backgroundExecutionNotice: kotlinx.coroutines.flow.StateFlow<String>

    fun currentTimeMillis(): Long
    fun monotonicTimeMillis(): Long
    fun createRunId(): String

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
    /** Lets the user choose chat exports and imports each one, streaming archives instead of loading them into memory. */
    suspend fun <T> pickAndImportChatFiles(decode: (chatJsons: List<String>) -> T): List<ChatImportOutcome<T>>
    suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String?
    /** Streams a chat bundle to a chosen file, reading [chats] one at a time. Null means the user cancelled. */
    suspend fun exportChatBundle(defaultFileName: String, chats: Sequence<ChatBundleEntry>): String?
    /** Copies a local media file to a chosen location without loading it into memory. Null means cancelled or missing. */
    suspend fun exportLocalFile(defaultFileName: String, filePath: String): String?
    suspend fun pickWorkspaceDirectory(currentPath: String): String?
    suspend fun pickFontFile(dialogTitle: String = "Select .TTF or .OTF Font"): String?
    fun loadCustomFontFamily(fontPath: String): FontFamily?
    fun loadBundledFontFamily(bundleName: String): FontFamily?

    /** Copies raw image bytes (PNG/JPEG/WebP) to the system clipboard as an image. */
    fun copyImageToClipboard(bytes: ByteArray): Boolean

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
    // Process-owned run snapshots include preparation, tools, compaction and approval waits.
    fun synchronizeBackgroundExecution(owners: Int, runnable: Int, status: String)
    fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int)

    // In-App Zero-Browser OTA Update Installer
    fun createOtaStagedDownload(assetName: String): OtaStagedDownload

    // Generated Image & Video Media Storage, Per-Chat Media Bundling, and Native Playback
    fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray, sessionId: String = ""): String
    fun relocateLegacyMediaToChatFolder(filePath: String, sessionId: String): String
    fun createZipArchive(
        entries: List<Pair<String, ByteArray>>,
        fileEntries: List<Pair<String, String>> = emptyList()
    ): ByteArray
    /** Validates a chat bundle and commits its media only if [decode] accepts its chats; failures change nothing. */
    fun <T> extractZipArchiveToChatStorage(zipBytes: ByteArray, decode: (chatJsons: List<String>) -> T): T
    fun readBinaryFileBytes(filePath: String): ByteArray?
    /** Size of a resolvable local media file, or -1 when it does not exist. */
    fun localFileSizeBytes(filePath: String): Long
    fun isReadableLocalFile(filePath: String): Boolean
    fun loadImageBitmapFromFile(filePath: String): ImageBitmap?
    /** An already decoded image, without touching the disk. */
    fun cachedImageBitmap(filePath: String): ImageBitmap?
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
    fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit = {})
    fun stopVoiceInput()
}
