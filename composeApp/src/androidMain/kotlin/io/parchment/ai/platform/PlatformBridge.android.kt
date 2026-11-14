package io.parchment.ai.platform

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ContentInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.parchment.ai.LongDecodeForegroundService
import io.parchment.ai.MainActivity
import io.parchment.ai.OtaInstallReceiver
import io.parchment.ai.ParchmentFileProvider
import io.parchment.ai.network.MAX_INLINE_ATTACHMENT_BYTES
import io.parchment.ai.network.OtaStagedDownload
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalEncodingApi::class)
actual object PlatformBridge {
    actual fun createRunId(): String = java.util.UUID.randomUUID().toString()
    actual val backgroundExecutionNotice: kotlinx.coroutines.flow.StateFlow<String> = LongDecodeForegroundService.executionNotice
    actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    actual val isAndroid: Boolean = true
    actual val isLowMemoryDevice: Boolean by lazy {
        val manager = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        manager?.isLowRamDevice == true || (info.totalMem in 1 until LOW_MEMORY_BYTES)
    }

    val RICH_CONTENT_MIME_TYPES: Array<String> = arrayOf(
        "image/*",
        "image/png",
        "image/gif",
        "image/webp",
        "image/jpeg",
        "image/bmp",
        "video/*",
        "video/mp4",
        "video/webm",
        "application/pdf",
        "audio/*"
    )

    @Volatile private var appContext: Context? = null
    @Volatile private var mainActivityRef: WeakReference<MainActivity>? = null
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady: Boolean = false
    @Volatile
    private var pendingTtsFinishedCallback: (() -> Unit)? = null
    @Volatile
    private var pendingUtteranceToSpeak: String? = null
    private val fontCache = ConcurrentHashMap<String, FontFamily>()
    private val imageCache = BoundedImageCache {
        if (isLowMemoryDevice) 4 to 24L * 1024L * 1024L else 16 to 96L * 1024L * 1024L
    }
    private val workspaceScanner = WorkspaceTreeScanner(
        WorkspaceScanConfig(
            missingDirMessage = { "Directory not found: $it" },
            notDirMessage = { "Directory not found: $it" },
            emptyDirectoryMessage = "(Empty workspace directory)",
            useTreeCache = false,
            canonicalizeRelativize = true,
            allowEmptyPlainGlobFallback = true,
            requirePositiveSearchFileBytes = false,
            maxSearchFileBytes = 1_499_999L,
            maxSearchHits = 100,
            maxSearchLineChars = 180,
            parallelSearch = false,
            filterSearchByRelPath = false
        )
    )
    private val pendingSharedOrPastedFiles = mutableListOf<PickedBinaryFile>()
    @Volatile
    private var stateFlushCallback: (() -> Unit)? = null
    @Volatile
    private var backHandlerCallback: (() -> Boolean)? = null
    @Volatile
    private var filesDroppedCallback: ((List<PickedBinaryFile>) -> Unit)? = null

    fun initAndroid(context: Context, activity: MainActivity) {
        appContext = context.applicationContext
        mainActivityRef = WeakReference(activity)
        // Ensure default local workspace folder exists
        val wsDir = File(resolveStorageDir(), "workspace")
        if (!wsDir.exists()) wsDir.mkdirs()
        ensureTtsInitialized(context.applicationContext)
    }

    private fun ensureTtsInitialized(ctx: Context) {
        if (ttsEngine != null && ttsReady) return
        runCatching {
            ttsEngine = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    runCatching {
                        ttsEngine?.language = java.util.Locale.getDefault()
                    }
                    ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.endsWith("-last") == true) {
                                val cb = pendingTtsFinishedCallback
                                pendingTtsFinishedCallback = null
                                cb?.invoke()
                            }
                        }
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            val cb = pendingTtsFinishedCallback
                            pendingTtsFinishedCallback = null
                            cb?.invoke()
                        }
                    })
                    val queued = pendingUtteranceToSpeak
                    if (!queued.isNullOrBlank()) {
                        pendingUtteranceToSpeak = null
                        dispatchTtsChunks(queued)
                    }
                } else {
                    ttsReady = false
                    val cb = pendingTtsFinishedCallback
                    pendingTtsFinishedCallback = null
                    cb?.invoke()
                }
            }
        }
    }

    private fun dispatchTtsChunks(text: String) {
        val engine = ttsEngine ?: return
        val chunks = text.chunked(3200)
        val baseId = "utt-${currentTimeMillis()}"
        chunks.forEachIndexed { idx, chunk ->
            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uttId = if (idx == chunks.lastIndex) "$baseId-last" else "$baseId-$idx"
            engine.speak(chunk, mode, null, uttId)
        }
    }
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun monotonicTimeMillis(): Long = android.os.SystemClock.uptimeMillis()

    private fun resolveStorageDir(): File {
        val ctx = appContext
        val dir = ctx?.filesDir ?: ctx?.noBackupFilesDir ?: File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "parchment")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    actual fun getAppStorageDirectoryPath(): String = resolveStorageDir().absolutePath

    actual fun isReadableLocalFile(filePath: String): Boolean =
        File(filePath).let { it.isFile && it.canRead() && it.length() > 0L }

    private fun chatsDir(): File = File(resolveStorageDir(), "chats").also {
        if (!it.exists()) it.mkdirs()
    }

    actual fun getChatsStorageDirectoryPath(): String = chatsDir().absolutePath


    private fun chatSessionFolder(sessionId: String): File =
        snapshotStorage.chatDirectory(sessionId)

    private val snapshotStorage = SnapshotFileStorage(::resolveStorageDir) {
        appContext?.noBackupFilesDir?.let { listOf(File(it, "parchment-state.json")) }.orEmpty()
    }

    actual fun loadAppStateJson(): String? = snapshotStorage.loadSettings()
    actual fun saveAppStateJson(json: String) = snapshotStorage.saveSettings(json)
    actual fun loadChatSessionJsonById(sessionId: String): String? = snapshotStorage.loadChat(sessionId)
    actual fun loadAllChatSessionJsons(): List<String> = snapshotStorage.loadAllChats()
    actual fun chatSessionJsons(): Sequence<String> = snapshotStorage.chats()
    actual fun saveChatSessionJson(sessionId: String, json: String) = snapshotStorage.saveChat(sessionId, json)
    actual fun deleteChatSessionFile(sessionId: String) = snapshotStorage.deleteChat(sessionId)

    actual fun registerStateFlushCallback(callback: () -> Unit) {
        stateFlushCallback = callback
    }

    actual fun flushPendingStateNow() {
        runCatching { stateFlushCallback?.invoke() }
    }

    actual fun registerBackHandler(handler: (() -> Boolean)?) {
        backHandlerCallback = handler
    }

    actual fun handleSystemBackPressed(): Boolean {
        return runCatching { backHandlerCallback?.invoke() == true }.getOrDefault(false)
    }

    actual fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val ctx = appContext ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        } else true
    }.getOrDefault(true)

    actual fun requestUnrestrictedBackgroundBattery() {
        val ctx = appContext ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        }
    }

    actual fun wipeAllLocalData(): Boolean =
        AppStorageInventory.wipeDirectory(resolveStorageDir(), preserveRoot = true)

    actual fun listAppStorageFiles(): List<io.parchment.ai.model.StoredAppFileItem> =
        AppStorageInventory.listFiles(resolveStorageDir(), otaTempExtensions = setOf(".tmp", ".apk"))

    actual fun deleteAppStorageFile(fullPath: String): Boolean =
        AppStorageInventory.deleteFile(fullPath, resolveStorageDir()) { imageCache.remove(it) }

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestImportFile(arrayOf("image/png", "application/json", "text/plain", "*/*"), maxPickedFileBytes(allowedExtensions))
    }

    actual suspend fun <T> pickAndImportChatFiles(decode: (chatJsons: List<String>) -> T): List<ChatImportOutcome<T>> {
        val act = mainActivityRef?.get() ?: return emptyList()
        val ctx = appContext ?: return emptyList()
        val mimes = arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "*/*")
        val uris = act.requestImportDocumentUris(mimes)
        return withContext(Dispatchers.IO) {
            uris.map { uri ->
                val name = uriDisplayName(ctx, uri)
                ChatImportOutcome(name, runCatching {
                    val source = checkNotNull(ctx.contentResolver.openInputStream(uri)) { "Unable to open the selected file." }
                    ChatArchiveExtractor.importChatFile(name, source, resolveStorageDir(), decode = decode)
                })
            }
        }
    }

    private fun uriDisplayName(ctx: Context, uri: Uri): String {
        var displayName = ""
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        displayName = cursor.getString(idx) ?: ""
                    }
                }
            }
        }
        return displayName.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "" }
    }

    actual suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestExportFile(defaultFileName, bytes)
    }

    actual suspend fun exportChatBundle(defaultFileName: String, chats: Sequence<ChatBundleEntry>): String? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestExportStream(defaultFileName) { ChatArchiveWriter.writeBundle(it, chats, ::resolveLocalMediaFile) }
    }

    actual suspend fun exportLocalFile(defaultFileName: String, filePath: String): String? {
        val act = mainActivityRef?.get() ?: return null
        val source = resolveLocalMediaFile(filePath) ?: return null
        return act.requestExportStream(defaultFileName) { output -> FileInputStream(source).use { it.copyTo(output, 64 * 1024) } }
    }

    actual suspend fun pickWorkspaceDirectory(currentPath: String): String? {
        val act = mainActivityRef?.get()
        if (act != null) {
            return act.requestPickWorkspaceDirectory(currentPath)
        }
        val wsDir = File(resolveStorageDir(), "workspace")
        if (!wsDir.exists()) wsDir.mkdirs()
        return wsDir.absolutePath
    }

    fun buildInitialDocumentTreeUri(currentPath: String): Uri? = runCatching {
        val extRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val cleaned = currentPath.trim().trimEnd('/')
        val rel = when {
            cleaned.isNotEmpty() && cleaned.startsWith(extRoot) -> cleaned.removePrefix(extRoot).trim('/')
            else -> ""
        }
        val docId = if (rel.isEmpty()) "primary:" else "primary:$rel"
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    }.getOrNull()

    fun resolveTreeUriToFilePath(treeUri: Uri): String {
        val extRoot = runCatching { Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') }
            .getOrDefault("/storage/emulated/0")
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        if (docId.isBlank()) return extRoot
        if (docId.startsWith("raw:")) {
            val rawPath = docId.removePrefix("raw:").trim()
            if (rawPath.isNotEmpty()) return rawPath
        }
        if (docId.startsWith("primary:")) {
            val rel = docId.substringAfter("primary:").trim('/')
            return if (rel.isEmpty()) extRoot else "$extRoot/$rel"
        }
        if (docId.startsWith("home:")) {
            val rel = docId.substringAfter("home:").trim('/')
            val docsRoot = runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath.trimEnd('/')
            }.getOrDefault("$extRoot/Documents")
            return if (rel.isEmpty()) docsRoot else "$docsRoot/$rel"
        }
        if (docId.contains(':')) {
            val volumeId = docId.substringBefore(':').trim()
            val rel = docId.substringAfter(':').trim('/')
            val volumeCandidate = File("/storage/$volumeId")
            if (volumeCandidate.exists()) {
                return if (rel.isEmpty()) volumeCandidate.absolutePath else "${volumeCandidate.absolutePath}/$rel"
            }
            if (rel.isNotEmpty()) {
                val fallbackCandidate = File("$extRoot/$rel")
                if (fallbackCandidate.exists()) return fallbackCandidate.absolutePath
            }
        }
        return extRoot
    }

    actual suspend fun pickFontFile(dialogTitle: String): String? {
        val act = mainActivityRef?.get() ?: return null
        val picked = act.requestImportFile(
            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"),
            maxPickedFileBytes(listOf("ttf", "otf"))
        )?.takeUnless { it.oversized } ?: return null
        return runCatching {
            val fontsDir = File(resolveStorageDir(), "fonts")
            if (!fontsDir.exists()) fontsDir.mkdirs()
            val safeName = picked.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "custom-font.ttf" }
            val outFile = File(fontsDir, safeName)
            outFile.writeBytes(picked.bytes)
            fontCache.remove(outFile.absolutePath)
            outFile.absolutePath
        }.getOrNull()
    }

    actual fun loadCustomFontFamily(fontPath: String): FontFamily? =
        AppStorageInventory.loadCachedFontFamily(fontCache, fontPath) { file ->
            FontFamily(
                Font(file = file, weight = FontWeight.Normal, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Medium, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Bold, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Normal, style = FontStyle.Italic),
                Font(file = file, weight = FontWeight.Bold, style = FontStyle.Italic)
            )
        }

    actual fun copyImageToClipboard(bytes: ByteArray): Boolean = runCatching {
        val ctx = appContext ?: return false
        if (bytes.isEmpty()) return false
        // Android's clipboard carries images as content:// URIs, not raw bitmaps.
        // Stage the bytes in cacheDir (an allowed ParchmentFileProvider root) and hand
        // the resolver a URI so no FileUriExposedException can ever be triggered.
        val dir = File(ctx.cacheDir, "clipboard").apply { mkdirs() }
        val file = File(dir, "parchment-clip-${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        val uri = ParchmentFileProvider.getUriForFile(ctx, file)
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Parchment Image", uri))
        true
    }.getOrDefault(false)

    actual fun loadBundledFontFamily(bundleName: String): FontFamily? {
        val key = "bundled:$bundleName"
        fontCache[key]?.let { return it }
        val assets = appContext?.assets ?: return null

        return runCatching {
            val family = when (bundleName.lowercase()) {
                "inter" -> {
                    FontFamily(
                        Font(assetManager = assets, path = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/Inter-Regular.ttf", weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/Inter-Regular.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/Inter-Regular.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/Inter-Italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic),
                        Font(assetManager = assets, path = "fonts/Inter-Italic.ttf", weight = FontWeight.Bold, style = FontStyle.Italic)
                    )
                }
                "jetbrains mono", "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> {
                    FontFamily(
                        Font(assetManager = assets, path = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMono-SemiBold.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMono-Bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMono-Italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic)
                    )
                }
                "latex", "katex", "math" -> {
                    FontFamily(
                        Font(assetManager = assets, path = "fonts/KaTeX_Main-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/KaTeX_Main-Bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/KaTeX_Main-Bold.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/KaTeX_Math-Italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic),
                        Font(assetManager = assets, path = "fonts/NotoSansMath-Regular.ttf", weight = FontWeight.Medium, style = FontStyle.Normal)
                    )
                }
                else -> return null
            }
            fontCache[key] = family
            family
        }.getOrNull()
    }

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> =
        workspaceScanner.list(workspaceRoot, relativePath, recursive)

    actual fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int): Result<String> =
        workspaceScanner.glob(workspaceRoot, pattern, maxResults)

    actual fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int, maxLines: Int): Result<String> = runCatching {
        val target = WorkspacePathGuard.resolve(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        WorkspaceTextReader.read(target, relativePath, startLine, maxLines, maxTotalChars = 200_000)
    }

    actual fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        WorkspaceFileEditor.write(workspaceRoot, relativePath, content)
    }

    actual fun workspaceAppend(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        WorkspaceFileEditor.append(workspaceRoot, relativePath, content)
    }

    actual fun workspaceEdit(
        workspaceRoot: String,
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): Result<String> = runCatching {
        WorkspaceFileEditor.edit(workspaceRoot, relativePath, oldText, newText, replaceAll)
    }

    actual fun workspaceMultiEdit(
        workspaceRoot: String,
        relativePath: String,
        edits: List<Triple<String, String, Boolean>>
    ): Result<String> = runCatching {
        WorkspaceFileEditor.multiEdit(workspaceRoot, relativePath, edits)
    }

    actual fun workspaceMove(workspaceRoot: String, sourceRelativePath: String, destRelativePath: String): Result<String> = runCatching {
        WorkspaceFileEditor.move(workspaceRoot, sourceRelativePath, destRelativePath)
    }

    actual fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String> = runCatching {
        WorkspaceFileEditor.delete(workspaceRoot, relativePath)
    }
    actual fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int,
        onOutputDelta: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): Result<String> {
        val root = runCatching { WorkspacePathGuard.root(workspaceRoot).toFile() }.getOrElse { return Result.failure(it) }
        if (!root.isDirectory) return Result.failure(IllegalArgumentException("Workspace root does not exist: $workspaceRoot"))
        return AndroidOwnedShell.execute(root, command, timeoutSeconds, onOutputDelta, isCancelled)
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> =
        workspaceScanner.search(workspaceRoot, query, fileGlob)

    actual fun fetchUrlText(url: String, maxChars: Int, isCancelled: (() -> Boolean)?): Result<String> =
        PublicWebTransport.fetch(url, maxChars, isCancelled)

    actual fun fetchPublicBytes(url: String, maxBytes: Int, isCancelled: (() -> Boolean)?): Result<ByteArray> =
        PublicWebTransport.fetchBytes(url, maxBytes, isCancelled)

    actual val computerControl: ComputerControl get() = AndroidComputerControl
    actual val supportsLocalMcpServers: Boolean = false
    actual fun startMcpProcess(command: String): McpProcess? = null


    @Volatile
    private var lastReportedDecodeSec: Int = -1

    actual fun synchronizeBackgroundExecution(owners: Int, runnable: Int, status: String) {
        val ctx = appContext ?: return
        if (owners == 0) {
            lastReportedDecodeSec = -1
            ctx.stopService(Intent(ctx, LongDecodeForegroundService::class.java))
            return
        }
        ensureNotificationPermission(ctx)
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_SYNC)
                putExtra(LongDecodeForegroundService.EXTRA_OWNERS, owners)
                putExtra(LongDecodeForegroundService.EXTRA_RUNNABLE, runnable)
                putExtra(LongDecodeForegroundService.EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }.onFailure { LongDecodeForegroundService.reportFailure(it) }
    }

    actual fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int) {
        if (elapsedSeconds == lastReportedDecodeSec) return
        lastReportedDecodeSec = elapsedSeconds
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_UPDATE)
                putExtra(LongDecodeForegroundService.EXTRA_STATUS, "$modelName • +$outputTokens tok • ${elapsedSeconds}s")
            }
            ctx.startService(intent)
        }.onFailure { LongDecodeForegroundService.reportFailure(it) }
    }

    private fun ensureNotificationPermission(ctx: Context) {
        LongDecodeForegroundService.refreshNotificationNotice(ctx)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        mainActivityRef?.get()?.requestNotificationPermission()
    }

    actual fun createOtaStagedDownload(assetName: String): OtaStagedDownload {
        val ctx = appContext ?: error("Android application context is not initialized.")
        // Android verifies the APK signature against the installed app before updating it.
        return OtaStagedFile(File(File(ctx.cacheDir, "ota"), "update.apk"), requireDigest = false, installer = ::installOtaApk)
    }

    private fun installOtaApk(apk: File): Result<String> = runCatching {
        // Save everything pending before the package installer starts.
        flushPendingStateNow()

        val ctx = appContext ?: error("Android application context is not initialized.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(permIntent)
            error("Please allow 'Install unknown apps' for Parchment in the Android settings screen that just opened, then tap Update again.")
        }

        val packageInstaller = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        session.use { activeSession ->
            activeSession.openWrite("parchment_ota.apk", 0L, apk.length()).use { out ->
                FileInputStream(apk).use { input -> input.copyTo(out, 64 * 1024) }
                activeSession.fsync(out)
            }

            val callbackIntent = Intent(ctx, OtaInstallReceiver::class.java).apply {
                setPackage(ctx.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(ctx, sessionId, callbackIntent, flags)
            activeSession.commit(pendingIntent.intentSender)
        }

        val committedKb = apk.length() / 1024
        apk.delete() // The installer session now holds its own copy.
        "Android PackageInstaller session committed ($committedKb KB). Confirm the system prompt if shown."
    }

    private val mediaStore by lazy {
        LocalMediaStore(::resolveStorageDir, ::chatSessionFolder, imageCache::remove)
    }

    private fun resolveLocalMediaFile(filePath: String): File? = mediaStore.resolve(filePath)

    actual fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray, sessionId: String): String =
        mediaStore.save(fileName, bytes, sessionId)

    actual fun relocateLegacyMediaToChatFolder(filePath: String, sessionId: String): String =
        mediaStore.relocateToChatFolder(filePath, sessionId)

    actual fun createZipArchive(
        entries: List<Pair<String, ByteArray>>,
        fileEntries: List<Pair<String, String>>
    ): ByteArray = ChatArchiveWriter.toByteArray(entries, fileEntries, ::resolveLocalMediaFile)

    actual fun <T> extractZipArchiveToChatStorage(zipBytes: ByteArray, decode: (chatJsons: List<String>) -> T): T =
        ChatArchiveExtractor.extract(zipBytes, resolveStorageDir(), decode = decode)

    actual fun localFileSizeBytes(filePath: String): Long = mediaStore.sizeOf(filePath)

    actual fun readBinaryFileBytes(filePath: String): ByteArray? = mediaStore.read(filePath)

    actual fun cachedImageBitmap(filePath: String): ImageBitmap? = imageCache.get(filePath)

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? =
        imageCache.getOrLoad(filePath, ::resolveLocalMediaFile, ::decodeSampledBitmap)

    private fun decodeSampledBitmap(file: File): ImageBitmap? {
        // Decode bounds first and downsample large 2K/4K images for UI display to minimize RAM.
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val maxDim = 1600
        var sampleSize = 1
        while ((boundsOpts.outWidth / sampleSize) > maxDim || (boundsOpts.outHeight / sampleSize) > maxDim) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)?.asImageBitmap()
    }

    actual fun openOrPlayMedia(localFilePathOrUrl: String) {
        val ctx = appContext ?: return
        val act = mainActivityRef?.get()
        runCatching {
            val target = localFilePathOrUrl.trim()
            if (target.isEmpty()) return@runCatching
            if (target.startsWith("http://") || target.startsWith("https://")) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                (act ?: ctx).startActivity(browserIntent)
            } else {
                val f = resolveLocalMediaFile(target) ?: File(target)
                if (!f.exists() || !f.isFile) return@runCatching
                val contentUri = ParchmentFileProvider.getUriForFile(ctx, f)
                val mimeType = ParchmentFileProvider.resolveMimeType(f)
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                (act ?: ctx).startActivity(viewIntent)
            }
        }
    }

    fun readUriAsPickedFile(
        uri: Uri,
        hintMimeType: String = "",
        maxBytes: Long = MAX_INLINE_ATTACHMENT_BYTES
    ): PickedBinaryFile? = runCatching {
        val ctx = appContext ?: return@runCatching null
        val displayName = uriDisplayName(ctx, uri)
        val stream = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { File(it).takeIf { f -> f.exists() && f.isFile }?.inputStream() }
            else -> ctx.contentResolver.openInputStream(uri)
        } ?: return@runCatching null
        val bytes = stream.use { readAtMost(it, maxBytes) }
            ?: return@runCatching PickedBinaryFile(displayName, ByteArray(0), oversized = true)
        if (bytes.isEmpty()) return@runCatching null

        val resolverMime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val effectiveMime = resolverMime.ifBlank { hintMimeType }

        normalizePickedBinaryFile(
            rawFileName = displayName,
            bytes = bytes,
            explicitMimeType = effectiveMime,
            fallbackTimestamp = currentTimeMillis()
        )
    }.getOrNull()

    fun deliverRichContentFiles(files: List<PickedBinaryFile>) {
        if (files.isEmpty()) return
        val cb = filesDroppedCallback
        if (cb != null) {
            cb.invoke(files)
        } else {
            synchronized(pendingSharedOrPastedFiles) {
                pendingSharedOrPastedFiles.addAll(files)
            }
        }
    }

    fun wrapInputConnection(baseIc: InputConnection, outAttrs: EditorInfo): InputConnection {
        outAttrs.contentMimeTypes = RICH_CONTENT_MIME_TYPES
        return object : InputConnectionWrapper(baseIc, true) {
            override fun commitContent(
                inputContentInfo: InputContentInfo,
                flags: Int,
                opts: Bundle?
            ): Boolean {
                val hasPermFlag = (flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
                if (hasPermFlag) {
                    runCatching { inputContentInfo.requestPermission() }
                }
                val mime = if (inputContentInfo.description.mimeTypeCount > 0) {
                    inputContentInfo.description.getMimeType(0) ?: ""
                } else ""
                val picked = readUriAsPickedFile(inputContentInfo.contentUri, mime)
                if (hasPermFlag) {
                    runCatching { inputContentInfo.releasePermission() }
                }
                if (picked != null) {
                    deliverRichContentFiles(listOf(picked))
                    return true
                }
                return super.commitContent(inputContentInfo, flags, opts)
            }

            override fun performContextMenuAction(id: Int): Boolean {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    val clipFiles = readClipboardFiles()
                    if (clipFiles.isNotEmpty()) {
                        deliverRichContentFiles(clipFiles)
                        return true
                    }
                }
                return super.performContextMenuAction(id)
            }
        }
    }

    fun handleReceiveContentPayload(payload: ContentInfo): ContentInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return payload
        val clip = payload.clip
        val pickedFiles = mutableListOf<PickedBinaryFile>()
        val remainingItems = mutableListOf<ClipData.Item>()
        val defaultMime = if (clip.description.mimeTypeCount > 0) {
            clip.description.getMimeType(0) ?: ""
        } else ""

        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item?.uri
            if (uri != null) {
                val picked = readUriAsPickedFile(uri, defaultMime)
                if (picked != null) {
                    pickedFiles.add(picked)
                    continue
                }
            }
            if (item != null) {
                remainingItems.add(item)
            }
        }

        if (pickedFiles.isEmpty()) {
            return payload
        }
        deliverRichContentFiles(pickedFiles)

        if (remainingItems.isEmpty()) {
            return null
        }
        val newClip = ClipData(clip.description, remainingItems.first())
        for (i in 1 until remainingItems.size) {
            newClip.addItem(remainingItems[i])
        }
        return ContentInfo.Builder(newClip, payload.source)
            .setFlags(payload.flags)
            .setLinkUri(payload.linkUri)
            .setExtras(payload.extras)
            .build()
    }

    actual fun readClipboardFiles(): List<PickedBinaryFile> = runCatching {
        val ctx = appContext ?: return@runCatching emptyList()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching emptyList()
        val clip = cm.primaryClip ?: return@runCatching emptyList()
        val defaultMime = if (clip.description.mimeTypeCount > 0) {
            clip.description.getMimeType(0) ?: ""
        } else ""
        val results = mutableListOf<PickedBinaryFile>()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i) ?: continue
            val uri = item.uri
            if (uri != null) {
                readUriAsPickedFile(uri, defaultMime)?.let { results.add(it) }
                continue
            }
            // Also check for inline data:image/...;base64,... in text or HTML clipboard items
            val candidateText = item.text?.toString()?.trim().orEmpty()
            val htmlText = item.htmlText?.trim().orEmpty()
            val dataUrl = when {
                candidateText.startsWith("data:image/") && candidateText.contains(";base64,") -> candidateText
                htmlText.contains("data:image/") && htmlText.contains(";base64,") -> {
                    Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+""").find(htmlText)?.value.orEmpty()
                }
                else -> ""
            }
            if (dataUrl.isNotEmpty()) {
                val mime = dataUrl.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
                val b64 = dataUrl.substringAfter(";base64,").trim()
                val bytes = runCatching { Base64.decode(b64) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    results.add(
                        normalizePickedBinaryFile(
                            rawFileName = "",
                            bytes = bytes,
                            explicitMimeType = mime,
                            fallbackTimestamp = currentTimeMillis() + i
                        )
                    )
                }
            }
        }
        results
    }.getOrDefault(emptyList())

    actual fun registerFileDropCallbacks(
        onDragHover: (Boolean) -> Unit,
        onFilesDropped: (List<PickedBinaryFile>) -> Unit
    ) {
        filesDroppedCallback = onFilesDropped
        val drained = synchronized(pendingSharedOrPastedFiles) {
            if (pendingSharedOrPastedFiles.isNotEmpty()) {
                val copy = pendingSharedOrPastedFiles.toList()
                pendingSharedOrPastedFiles.clear()
                copy
            } else emptyList()
        }
        if (drained.isNotEmpty()) {
            onFilesDropped(drained)
        }
    }

    actual fun speakTextAloud(text: String, onFinished: () -> Unit) {
        runCatching {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) {
                onFinished()
                return@runCatching
            }
            // Reset any prior speaking turn's callback before starting the new utterance
            pendingTtsFinishedCallback?.invoke()
            pendingTtsFinishedCallback = onFinished
            val ctx = appContext
            if (ttsEngine == null || !ttsReady) {
                pendingUtteranceToSpeak = cleaned
                if (ctx != null) ensureTtsInitialized(ctx)
            } else {
                dispatchTtsChunks(cleaned)
            }
        }.onFailure {
            onFinished()
        }
    }

    actual fun stopSpeakingAloud() {
        runCatching {
            pendingUtteranceToSpeak = null
            val cb = pendingTtsFinishedCallback
            pendingTtsFinishedCallback = null
            ttsEngine?.stop()
            cb?.invoke()
        }
    }


    actual fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit) {
        val act = mainActivityRef?.get()
        if (act == null) {
            onError("Activity context unavailable.")
            return
        }
        act.launchVoiceRecognizer(onRecognizedText, onError)
    }

    actual fun stopVoiceInput() {
        runCatching {
            mainActivityRef?.get()?.stopVoiceRecognizer()
        }
    }
}
