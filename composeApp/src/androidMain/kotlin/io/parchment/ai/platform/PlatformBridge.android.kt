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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
actual object PlatformBridge {
    actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    actual val isAndroid: Boolean = true

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

    private var appContext: Context? = null
    private var mainActivityRef: WeakReference<MainActivity>? = null
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady: Boolean = false
    @Volatile
    private var isCurrentlySpeaking: Boolean = false
    @Volatile
    private var pendingTtsFinishedCallback: (() -> Unit)? = null
    @Volatile
    private var pendingUtteranceToSpeak: String? = null
    private val fontCache = ConcurrentHashMap<String, FontFamily>()
    private val imageCache = LinkedHashMap<String, ImageBitmap>()
    private val pendingSharedOrPastedFiles = mutableListOf<PickedBinaryFile>()
    @Volatile
    private var stateFlushCallback: (() -> Unit)? = null
    @Volatile
    private var backHandlerCallback: (() -> Boolean)? = null
    @Volatile
    private var filesDroppedCallback: ((List<PickedBinaryFile>) -> Unit)? = null
    private val activeShellProcesses: MutableSet<Process> = java.util.Collections.synchronizedSet(HashSet())

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
                            isCurrentlySpeaking = true
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.endsWith("-last") == true) {
                                isCurrentlySpeaking = false
                                val cb = pendingTtsFinishedCallback
                                pendingTtsFinishedCallback = null
                                cb?.invoke()
                            }
                        }
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            isCurrentlySpeaking = false
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
                    isCurrentlySpeaking = false
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

    private fun resolveStorageDir(): File {
        val ctx = appContext
        val dir = ctx?.filesDir ?: ctx?.noBackupFilesDir ?: File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "parchment")
        if (!dir.exists()) dir.mkdirs()
        // Seamlessly migrate existing state from noBackupFilesDir to filesDir if present
        val legacyDir = ctx?.noBackupFilesDir
        if (legacyDir != null && legacyDir.exists() && legacyDir.absolutePath != dir.absolutePath) {
            val legacyState = File(legacyDir, "parchment-state.json")
            val newState = File(dir, "parchment-state.json")
            if (legacyState.exists() && legacyState.length() > 10L && (!newState.exists() || newState.length() <= 10L)) {
                runCatching { legacyState.copyTo(newState, overwrite = true) }
            }
        }
        return dir
    }

    actual fun getAppStorageDirectoryPath(): String = resolveStorageDir().absolutePath

    private fun chatsDir(): File = File(resolveStorageDir(), "chats").also {
        if (!it.exists()) it.mkdirs()
    }

    actual fun getChatsStorageDirectoryPath(): String = chatsDir().absolutePath

    private fun backupsDir(): File = File(resolveStorageDir(), "backups").also {
        if (!it.exists()) it.mkdirs()
    }

    private fun settingsFile(): File = File(resolveStorageDir(), "parchment-settings.json")
    private fun legacyStateFile(): File = File(resolveStorageDir(), "parchment-state.json")

    private val SAFE_SESSION_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
    private val API_KEY_NONEMPTY_REGEX = Regex("\"apiKey\":\"[^\"]+\"")
    private val NON_DEFAULT_ASST_ID_REGEX = Regex("\"id\":\"asst-(?!default\")")
    private val NON_EMPTY_SYS_PROMPT_REGEX = Regex("\"systemPrompt\":\"[^\"]+\"")

    private fun sanitizeSessionFileName(sessionId: String): String =
        sessionId.trim().replace(SAFE_SESSION_NAME_REGEX, "_").ifBlank { "session" }

    private fun chatSessionFolder(sessionId: String): File =
        File(chatsDir(), sanitizeSessionFileName(sessionId))

    private fun isBlankDefaultStateJson(json: String): Boolean {
        val hasNoApiKeys = API_KEY_NONEMPTY_REGEX.find(json) == null
        val hasNoAssistants = json.contains("\"assistants\":[]") || (
            !NON_DEFAULT_ASST_ID_REGEX.containsMatchIn(json) &&
                !NON_EMPTY_SYS_PROMPT_REGEX.containsMatchIn(json)
            )
        val hasNoMessages = !json.contains("\"role\":\"USER\"") && !json.contains("\"role\":\"ASSISTANT\"")
        val hasNoSavedCards = !json.contains("\"savedCards\":[{")
        val hasNoPresets = !json.contains("\"systemPromptPresets\":[{")
        val hasNoGlobalWorldBook = !json.contains("\"globalWorldBook\":[{")
        val isInitialSessionId = json.contains("\"activeSessionId\":\"sess-initial\"") ||
            json.contains("\"activeSessionId\":\"\"")
        return hasNoApiKeys && hasNoAssistants && hasNoMessages &&
            hasNoSavedCards && hasNoPresets && hasNoGlobalWorldBook && isInitialSessionId
    }

    actual fun loadAppStateJson(): String? = runCatching {
        val dir = resolveStorageDir()
        val bakDir = backupsDir()
        val f = settingsFile()
        val tmp = File(dir, "parchment-settings.json.tmp")
        val prev = File(bakDir, "parchment-settings.prev.json")
        val userBak = File(bakDir, "parchment-settings.user-backup.json")
        val legacyRootPrev = File(dir, "parchment-settings.prev.json")
        val legacyRootUserBak = File(dir, "parchment-settings.user-backup.json")
        if (legacyRootPrev.exists() && !prev.exists()) {
            runCatching { legacyRootPrev.copyTo(prev, overwrite = true); legacyRootPrev.delete() }
        }
        if (legacyRootUserBak.exists() && !userBak.exists()) {
            runCatching { legacyRootUserBak.copyTo(userBak, overwrite = true); legacyRootUserBak.delete() }
        }
        val legacy = legacyStateFile()
        val legacyTmp = File(dir, "parchment-state.json.tmp")
        val legacyPrev = File(dir, "parchment-state.prev.json")
        val legacyBak = File(dir, "parchment-state.user-backup.json")
        val legacyNoBackup = appContext?.noBackupFilesDir?.let { File(it, "parchment-state.json") }
        val candidates = listOfNotNull(f, tmp, prev, userBak, legacyRootPrev, legacyRootUserBak, legacy, legacyTmp, legacyPrev, legacyBak, legacyNoBackup)
        for (cand in candidates) {
            if (cand.exists() && cand.isFile && cand.length() > 10L) {
                val content = cand.readText(Charsets.UTF_8)
                val activeBak = when {
                    userBak.exists() && userBak.length() > 50L -> userBak
                    legacyRootUserBak.exists() && legacyRootUserBak.length() > 50L -> legacyRootUserBak
                    else -> legacyBak
                }
                if (cand != activeBak && isBlankDefaultStateJson(content) && activeBak.exists() && activeBak.length() > 50L) {
                    val bakContent = runCatching { activeBak.readText(Charsets.UTF_8) }.getOrNull()
                    if (!bakContent.isNullOrBlank() && !isBlankDefaultStateJson(bakContent)) {
                        return@runCatching bakContent
                    }
                }
                return@runCatching content
            }
        }
        null
    }.getOrNull()

    @Synchronized
    actual fun saveAppStateJson(json: String) {
        runCatching {
            val dir = resolveStorageDir()
            if (!dir.exists()) dir.mkdirs()
            val bakDir = backupsDir()
            val tmp = File(dir, "parchment-settings.json.tmp")
            val prev = File(bakDir, "parchment-settings.prev.json")
            val userBak = File(bakDir, "parchment-settings.user-backup.json")
            val target = settingsFile()

            val incomingIsBlankDefault = isBlankDefaultStateJson(json)
            if (incomingIsBlankDefault && userBak.exists() && userBak.length() > 50L) {
                return
            }

            tmp.writeText(json, Charsets.UTF_8)
            if (target.exists() && target.length() > 50L) {
                runCatching { target.copyTo(prev, overwrite = true) }
            }
            if (!tmp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
            if (!incomingIsBlankDefault && json.length > 50) {
                runCatching { target.copyTo(userBak, overwrite = true) }
            }
            File(dir, "parchment-settings.prev.json").takeIf { it.exists() }?.delete()
            File(dir, "parchment-settings.user-backup.json").takeIf { it.exists() }?.delete()
        }
    }

    private fun migrateLegacyFlatChatFileIfPresent(safeId: String): File? {
        val dir = chatsDir()
        val folder = File(dir, safeId)
        val folderChatJson = File(folder, "chat.json")
        val legacyFlat = File(dir, "$safeId.json")
        val legacyTmp = File(dir, "$safeId.json.tmp")
        if (legacyFlat.exists() && legacyFlat.isFile && legacyFlat.length() > 5L) {
            if (!folder.exists()) folder.mkdirs()
            if (!folderChatJson.exists() || legacyFlat.lastModified() > folderChatJson.lastModified()) {
                runCatching { legacyFlat.copyTo(folderChatJson, overwrite = true) }
            }
            runCatching { legacyFlat.delete() }
            runCatching { legacyTmp.delete() }
            return folderChatJson
        }
        if (legacyTmp.exists() && legacyTmp.isFile && legacyTmp.length() > 10L) {
            if (!folder.exists()) folder.mkdirs()
            if (!folderChatJson.exists()) {
                runCatching { legacyTmp.copyTo(folderChatJson, overwrite = true) }
            }
            runCatching { legacyTmp.delete() }
            return folderChatJson
        }
        return if (folderChatJson.exists()) folderChatJson else null
    }

    actual fun loadChatSessionJsonById(sessionId: String): String? = runCatching {
        if (sessionId.isBlank()) return null
        val safeId = sanitizeSessionFileName(sessionId)
        migrateLegacyFlatChatFileIfPresent(safeId)
        val folder = chatSessionFolder(safeId)
        val primary = File(folder, "chat.json")
        if (primary.exists() && primary.isFile && primary.length() > 5L) {
            return primary.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
        val tmp = File(folder, "chat.json.tmp")
        if (tmp.exists() && tmp.isFile && tmp.length() > 10L) {
            runCatching { tmp.copyTo(primary, overwrite = true) }
            return tmp.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
        null
    }.getOrNull()

    actual fun loadAllChatSessionJsons(): List<String> = runCatching {
        val dir = chatsDir()
        val entries = dir.listFiles()?.toList().orEmpty()
        for (f in entries) {
            if (f.isFile) {
                when {
                    f.name.endsWith(".json.tmp", ignoreCase = true) -> {
                        val safeId = f.name.removeSuffix(".tmp").removeSuffix(".json")
                        migrateLegacyFlatChatFileIfPresent(safeId)
                    }
                    f.name.endsWith(".json", ignoreCase = true) -> {
                        val safeId = f.name.removeSuffix(".json")
                        migrateLegacyFlatChatFileIfPresent(safeId)
                    }
                }
            }
        }
        dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { folder ->
                val primary = File(folder, "chat.json")
                val tmp = File(folder, "chat.json.tmp")
                if ((!primary.exists() || primary.length() <= 5L) && tmp.exists() && tmp.length() > 10L) {
                    runCatching { tmp.copyTo(primary, overwrite = true) }
                }
                primary.takeIf { it.exists() && it.isFile && it.length() > 5L }
            }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() } }
            .orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    actual fun saveChatSessionJson(sessionId: String, json: String) {
        if (sessionId.isBlank() || json.isBlank()) return
        runCatching {
            val safeId = sanitizeSessionFileName(sessionId)
            val folder = chatSessionFolder(safeId)
            if (!folder.exists()) folder.mkdirs()
            val target = File(folder, "chat.json")
            val tmp = File(folder, "chat.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
            val dir = chatsDir()
            File(dir, "$safeId.json").takeIf { it.exists() }?.delete()
            File(dir, "$safeId.json.tmp").takeIf { it.exists() }?.delete()
        }
    }

    @Synchronized
    actual fun deleteChatSessionFile(sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching {
            val dir = chatsDir()
            val safeId = sanitizeSessionFileName(sessionId)
            val folder = File(dir, safeId)
            if (folder.exists()) {
                folder.deleteRecursively()
            }
            File(dir, "$safeId.json").delete()
            File(dir, "$safeId.json.tmp").delete()
        }
    }

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

    actual fun wipeAllLocalData(): Boolean = runCatching {
        val dir = resolveStorageDir()
        dir.listFiles()?.forEach { it.deleteRecursively() }
        true
    }.getOrDefault(false)

    actual fun listAppStorageFiles(): List<io.parchment.ai.model.StoredAppFileItem> = runCatching {
        val rootDir = resolveStorageDir().canonicalFile
        val rootPath = rootDir.toPath().normalize()
        val items = mutableListOf<io.parchment.ai.model.StoredAppFileItem>()
        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile }
                .forEach { f ->
                    val rel = runCatching { rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/') }
                        .getOrDefault(f.name)
                    val lowerRel = rel.lowercase()
                    val lowerName = f.name.lowercase()
                    val cat = when {
                        lowerName.endsWith(".tmp") || lowerName.endsWith(".apk") -> io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP
                        lowerRel.startsWith("chats/") && lowerName.endsWith(".json") -> io.parchment.ai.model.StorageFileCategory.CHAT_SESSION
                        (lowerRel.startsWith("media/") || lowerRel.contains("/media/")) && lowerName.startsWith("att-") ->
                            io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA
                        lowerRel.startsWith("media/") || lowerRel.contains("/media/") ->
                            io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA
                        else -> io.parchment.ai.model.StorageFileCategory.SETTINGS_AND_BACKUPS
                    }
                    items.add(
                        io.parchment.ai.model.StoredAppFileItem(
                            fullPath = f.absolutePath,
                            relativePath = rel,
                            fileName = f.name,
                            category = cat,
                            sizeBytes = f.length(),
                            lastModifiedMs = f.lastModified()
                        )
                    )
                }
        }
        items.sortedWith(
            compareBy<io.parchment.ai.model.StoredAppFileItem> { it.category.ordinal }
                .thenByDescending { it.lastModifiedMs }
        )
    }.getOrDefault(emptyList())

    actual fun deleteAppStorageFile(fullPath: String): Boolean = runCatching {
        if (fullPath.isBlank()) return false
        val target = File(fullPath).canonicalFile
        val storageRoot = resolveStorageDir().canonicalFile
        val insideStorage = target.toPath().normalize().startsWith(storageRoot.toPath().normalize())
        if (!insideStorage || !target.exists() || !target.isFile) return false
        synchronized(imageCache) { imageCache.remove(target.absolutePath) }
        target.delete()
    }.getOrDefault(false)

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestImportFile(arrayOf("image/png", "application/json", "text/plain", "*/*"))
    }

    actual suspend fun pickFilesForImport(allowedExtensions: List<String>): List<PickedBinaryFile> {
        val act = mainActivityRef?.get() ?: return emptyList()
        val hasZip = allowedExtensions.any { it.equals("zip", ignoreCase = true) }
        val hasJson = allowedExtensions.any { it.equals("json", ignoreCase = true) }
        val mimes = when {
            hasZip && hasJson -> arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "*/*")
            hasJson && allowedExtensions.size == 1 -> arrayOf("application/json", "text/plain", "*/*")
            else -> arrayOf("image/png", "application/json", "application/zip", "text/plain", "*/*")
        }
        return act.requestImportMultipleFiles(mimes)
    }

    actual suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestExportFile(defaultFileName, bytes)
    }

    actual suspend fun exportMultipleFilesToDirectory(files: List<Pair<String, ByteArray>>): String? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestExportMultipleFilesToTree(files)
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
        val picked = act.requestImportFile(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
            ?: return null
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

    actual fun loadCustomFontFamily(fontPath: String): FontFamily? {
        val cleaned = fontPath.trim().removeSurrounding("\"")
        if (cleaned.isEmpty()) return null
        fontCache[cleaned]?.let { return it }

        return runCatching {
            val file = File(cleaned)
            if (!file.exists() || !file.isFile) return null
            val family = FontFamily(
                Font(file = file, weight = FontWeight.Normal, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Medium, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Bold, style = FontStyle.Normal),
                Font(file = file, weight = FontWeight.Normal, style = FontStyle.Italic),
                Font(file = file, weight = FontWeight.Bold, style = FontStyle.Italic)
            )
            fontCache[cleaned] = family
            family
        }.getOrNull()
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
                "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> {
                    FontFamily(
                        Font(assetManager = assets, path = "fonts/JetBrainsMonoNerdFont-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMonoNerdFont-Regular.ttf", weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMonoNerdFont-SemiBold.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMonoNerdFont-Bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(assetManager = assets, path = "fonts/JetBrainsMonoNerdFont-Italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic)
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

    private fun resolveSandboxedPath(workspaceRoot: String, relativePath: String): Path {
        val root = File(workspaceRoot).canonicalFile.toPath().normalize()
        val cleanedRel = relativePath.trim().removePrefix("/").removePrefix("\\")
        val resolved = if (cleanedRel.isEmpty() || cleanedRel == ".") {
            root
        } else {
            root.resolve(cleanedRel).normalize()
        }
        require(resolved.startsWith(root)) {
            "Access denied: Path '$relativePath' escapes the mounted workspace root."
        }
        return resolved
    }

    private fun isIgnoredWorkspaceDir(name: String): Boolean {
        val low = name.lowercase()
        return low == ".git" || low == "build" || low == ".gradle" ||
            low == "node_modules" || low == ".idea" || low == ".kotlin"
    }

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        val rootPath = File(workspaceRoot).canonicalFile.toPath().normalize()
        require(target.exists() && target.isDirectory) { "Directory not found: $relativePath" }
        val entries = if (recursive) {
            target.walkTopDown()
                .onEnter { d -> d == target || !isIgnoredWorkspaceDir(d.name) }
                .maxDepth(24)
                .filter { it != target && !isIgnoredWorkspaceDir(it.name) }
                .take(400)
                .map { f ->
                    val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                }
                .toList()
        } else {
            (target.listFiles()?.toList() ?: emptyList())
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .take(300)
                .map { f ->
                    val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                }
        }
        if (entries.isEmpty()) "(Empty workspace directory)" else entries.joinToString("\n")
    }

    actual fun workspaceGlob(workspaceRoot: String, pattern: String, maxResults: Int): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val cleanPat = pattern.trim().replace('\\', '/').removePrefix("./")
        require(cleanPat.isNotEmpty()) { "Glob pattern must not be empty." }
        val regexStr = buildString {
            append("^")
            var i = 0
            while (i < cleanPat.length) {
                val c = cleanPat[i]
                if (c == '*' && i + 1 < cleanPat.length && cleanPat[i + 1] == '*') {
                    if (i + 2 < cleanPat.length && cleanPat[i + 2] == '/') {
                        append("(?:.*/)?")
                        i += 3
                        continue
                    } else {
                        append(".*")
                        i += 2
                        continue
                    }
                } else if (c == '*') {
                    append("[^/]*")
                } else if (c == '?') {
                    append("[^/]")
                } else if (".+()[]{}|^$\\".contains(c)) {
                    append('\\').append(c)
                } else {
                    append(c)
                }
                i++
            }
            append("$")
        }
        val globRegex = Regex(regexStr, RegexOption.IGNORE_CASE)
        val matches = rootFile.walkTopDown()
            .onEnter { d -> d == rootFile || !isIgnoredWorkspaceDir(d.name) }
            .maxDepth(24)
            .filter { f -> f != rootFile && !isIgnoredWorkspaceDir(f.name) }
            .mapNotNull { f ->
                val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                val matchTarget = if (!cleanPat.contains('/')) f.name else rel
                if (globRegex.matches(matchTarget) || rel.contains(cleanPat.replace("*", ""), ignoreCase = true)) {
                    if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
                } else null
            }
            .take(maxResults.coerceIn(1, 500))
            .toList()
        if (matches.isEmpty()) "No files matched glob '$pattern'." else matches.joinToString("\n")
    }

    actual fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int, maxLines: Int): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        val reqStart = (startLine - 1).coerceAtLeast(0)
        val reqMax = maxLines.coerceIn(1, 10_000)
        val sliced = ArrayList<String>(reqMax.coerceAtMost(512))
        var totalLines = 0
        var accumulatedChars = 0
        val maxTotalChars = 200_000
        target.useLines(Charsets.UTF_8) { seq ->
            for (line in seq) {
                if (totalLines >= reqStart && sliced.size < reqMax && accumulatedChars < maxTotalChars) {
                    val safeLine = if (line.length > 4000) line.substring(0, 4000) + " … [line truncated]" else line
                    sliced.add(safeLine)
                    accumulatedChars += safeLine.length
                }
                totalLines++
            }
        }
        if (totalLines == 0) {
            return@runCatching "File: $relativePath (Lines 1..0 of 0)"
        }
        val actualStart = reqStart.coerceAtMost((totalLines - 1).coerceAtLeast(0))
        val actualEnd = (actualStart + sliced.size).coerceAtMost(totalLines)
        buildString(accumulatedChars + 128) {
            appendLine("File: $relativePath (Lines ${actualStart + 1}..$actualEnd of $totalLines)")
            sliced.forEachIndexed { idx, line ->
                appendLine("${actualStart + idx + 1}: $line")
            }
        }.trimEnd()
    }

    actual fun workspaceWrite(workspaceRoot: String, relativePath: String, content: String): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        "Successfully wrote ${content.length} chars to '$relativePath'."
    }

    actual fun workspaceEdit(
        workspaceRoot: String,
        relativePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        val existingRaw = target.readText(Charsets.UTF_8)
        val usesCrlf = existingRaw.contains("\r\n")
        val existingLf = existingRaw.replace("\r\n", "\n")
        val editRes = io.parchment.ai.harness.WorkspaceHarness.applySingleEditWithFuzzySelfHealing(
            existingLf = existingLf,
            rawOldText = oldText,
            rawNewText = newText,
            replaceAll = replaceAll,
            relativePath = relativePath
        )
        val finalContent = if (usesCrlf) editRes.updatedLf.replace("\n", "\r\n") else editRes.updatedLf
        target.writeText(finalContent, Charsets.UTF_8)
        val fuzzyNote = if (editRes.usedFuzzyWhitespace) " (fuzzy whitespace self-healed)" else ""
        "Edited '$relativePath' (+${editRes.addedLines} / -${editRes.removedLines} lines)$fuzzyNote."
    }

    actual fun workspaceMultiEdit(
        workspaceRoot: String,
        relativePath: String,
        edits: List<Triple<String, String, Boolean>>
    ): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        require(edits.isNotEmpty()) { "edits list must not be empty." }
        val existingRaw = target.readText(Charsets.UTF_8)
        val usesCrlf = existingRaw.contains("\r\n")
        var currentLf = existingRaw.replace("\r\n", "\n")
        var totalAdded = 0
        var totalRemoved = 0

        edits.forEachIndexed { idx, (oldText, newText, replaceAll) ->
            require(oldText.isNotEmpty()) { "Edit #${idx + 1}: old_text must not be empty." }
            val stepRes = runCatching {
                io.parchment.ai.harness.WorkspaceHarness.applySingleEditWithFuzzySelfHealing(
                    existingLf = currentLf,
                    rawOldText = oldText,
                    rawNewText = newText,
                    replaceAll = replaceAll,
                    relativePath = relativePath
                )
            }.getOrElse { err ->
                error("Edit #${idx + 1} failed (rolled back all edits): ${err.message}")
            }
            currentLf = stepRes.updatedLf
            totalRemoved += stepRes.removedLines
            totalAdded += stepRes.addedLines
        }

        val finalContent = if (usesCrlf) currentLf.replace("\n", "\r\n") else currentLf
        target.writeText(finalContent, Charsets.UTF_8)
        "Applied ${edits.size} edit(s) atomically to '$relativePath' (+$totalAdded / -$totalRemoved lines)."
    }

    actual fun workspaceMove(workspaceRoot: String, sourceRelativePath: String, destRelativePath: String): Result<String> = runCatching {
        val srcFile = resolveSandboxedPath(workspaceRoot, sourceRelativePath).toFile()
        val dstFile = resolveSandboxedPath(workspaceRoot, destRelativePath).toFile()
        require(srcFile.exists()) { "Source path does not exist: $sourceRelativePath" }
        dstFile.parentFile?.mkdirs()
        java.nio.file.Files.move(
            srcFile.toPath(),
            dstFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        "Moved '$sourceRelativePath' -> '$destRelativePath'."
    }

    actual fun workspaceDelete(workspaceRoot: String, relativePath: String): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        val rootCanon = File(workspaceRoot).canonicalFile
        require(target.canonicalFile != rootCanon) { "Cannot delete workspace root directory." }
        require(target.exists()) { "Path does not exist: $relativePath" }
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        require(deleted) { "Failed to delete '$relativePath'." }
        "Deleted '$relativePath'."
    }
    actual fun workspaceShellExec(
        workspaceRoot: String,
        command: String,
        timeoutSeconds: Int,
        onOutputDelta: ((String) -> Unit)?
    ): Result<String> = runCatching {
        val rootDir = File(workspaceRoot).canonicalFile
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        activeShellProcesses.add(proc)
        runCatching { proc.outputStream.close() }

        val outputBuf = StringBuilder()
        var lastEmitMs = 0L
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val readCount = reader.read(chunk)
                        if (readCount < 0) break
                        var snap: String? = null
                        synchronized(outputBuf) {
                            if (outputBuf.length < 48_000) {
                                val allowed = (48_000 - outputBuf.length).coerceAtMost(readCount)
                                outputBuf.append(chunk, 0, allowed)
                            }
                            val now = System.currentTimeMillis()
                            if (onOutputDelta != null && now - lastEmitMs >= 100L) {
                                lastEmitMs = now
                                snap = outputBuf.toString()
                            }
                        }
                        val deltaSnap = snap
                        if (deltaSnap != null) {
                            runCatching { onOutputDelta?.invoke(deltaSnap) }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "parchment-android-shell-reader"
            start()
        }

        try {
            val maxWaitMs = timeoutSeconds.coerceIn(1, 120) * 1000L
            val startMs = System.currentTimeMillis()
            var finished = false
            while (System.currentTimeMillis() - startMs < maxWaitMs) {
                if (!activeShellProcesses.contains(proc)) {
                    proc.destroyForcibly()
                    error("Shell command terminated by user.")
                }
                if (proc.waitFor(100L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }
            if (!finished) {
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                runCatching { readerThread.join(1000L) }
                val partial = synchronized(outputBuf) { outputBuf.toString().trim() }
                val partialSuffix = if (partial.isNotEmpty()) "\nPartial output before timeout:\n${partial.take(3000)}" else ""
                error("Command timed out after $timeoutSeconds seconds.$partialSuffix")
            }
            runCatching { readerThread.join(1500L) }
            val output = synchronized(outputBuf) { outputBuf.toString().trim() }
            if (onOutputDelta != null && output.isNotEmpty()) {
                runCatching { onOutputDelta.invoke(output) }
            }
            "Exit Code: ${proc.exitValue()}\n${output.take(6000)}"
        } finally {
            activeShellProcesses.remove(proc)
        }
    }

    actual fun killActiveShellProcesses() {
        val snapshot = synchronized(activeShellProcesses) {
            val list = activeShellProcesses.toList()
            activeShellProcesses.clear()
            list
        }
        for (proc in snapshot) {
            runCatching { proc.destroyForcibly() }
            runCatching { proc.inputStream.close() }
        }
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(query.isNotBlank()) { "Search query must not be blank." }
        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val matches = mutableListOf<String>()
        rootFile.walkTopDown()
            .onEnter { d -> d == rootFile || !isIgnoredWorkspaceDir(d.name) }
            .maxDepth(24)
            .filter { f ->
                f.isFile &&
                    !isIgnoredWorkspaceDir(f.name) &&
                    f.length() < 1_500_000L &&
                    (fileGlob.isBlank() || f.name.contains(fileGlob.removePrefix("*"), ignoreCase = true))
            }
            .forEach { f ->
                if (matches.size >= 100) return@forEach
                runCatching {
                    val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                    f.useLines(Charsets.UTF_8) { seq ->
                        seq.forEachIndexed { idx, line ->
                            if (matches.size < 100) {
                                val hit = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                                if (hit) {
                                    matches.add("$rel:${idx + 1}: ${line.trim().take(180)}")
                                }
                            }
                        }
                    }
                }
            }
        if (matches.isEmpty()) "No matches found for '$query'." else matches.joinToString("\n")
    }

    actual fun fetchUrlText(url: String, maxChars: Int): Result<String> = runCatching {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            "URL must start with http:// or https://"
        }
        val conn = (java.net.URI(trimmed).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; ParchmentAI/2.0)")
            setRequestProperty("Accept", "text/markdown, text/html, application/xhtml+xml, application/json, text/plain;q=0.9, */*;q=0.8")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val maxRawChars = (maxChars.coerceIn(500, 100_000) * 4).coerceIn(32_000, 400_000)
            val body = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder(maxRawChars.coerceAtMost(32_768))
                val buf = CharArray(4096)
                while (sb.length < maxRawChars) {
                    val n = reader.read(buf, 0, minOf(buf.size, maxRawChars - sb.length))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            if (code !in 200..299) {
                error("HTTP $code from $trimmed: ${body.take(400)}")
            }
            val contentType = conn.contentType.orEmpty().lowercase()
            val cleaned = io.parchment.ai.harness.WorkspaceHarness.convertHtmlToReadableText(body, contentType)
            cleaned.take(maxChars.coerceIn(500, 100_000))
        } finally {
            conn.disconnect()
        }
    }

    @Volatile
    private var lastReportedDecodeSec: Int = -1

    actual fun notifyLongDecodeStart(modelName: String) {
        lastReportedDecodeSec = -1
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_START)
                putExtra(LongDecodeForegroundService.EXTRA_STATUS, "Decoding response ($modelName)…")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    actual fun notifyLongDecodeProgress(modelName: String, outputTokens: Int, elapsedSeconds: Int) {
        if (elapsedSeconds == lastReportedDecodeSec) return
        lastReportedDecodeSec = elapsedSeconds
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_START)
                putExtra(LongDecodeForegroundService.EXTRA_STATUS, "$modelName • +$outputTokens tok • ${elapsedSeconds}s")
            }
            ctx.startService(intent)
        }
    }

    actual fun notifyLongDecodeComplete() {
        lastReportedDecodeSec = -1
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(ctx, LongDecodeForegroundService::class.java).apply {
                putExtra(LongDecodeForegroundService.EXTRA_ACTION, LongDecodeForegroundService.ACTION_STOP)
            }
            ctx.startService(intent)
        }
    }

    actual fun installOtaUpdate(fileName: String, bytes: ByteArray): Result<String> = runCatching {
        // Flush any pending in-memory state to disk BEFORE starting the Android OTA package installer!
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
            activeSession.openWrite("parchment_ota.apk", 0L, bytes.size.toLong()).use { out ->
                out.write(bytes)
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

        "Android PackageInstaller session committed (${bytes.size / 1024} KB). Confirm the system prompt if shown."
    }

    private fun resolveLocalMediaFile(filePath: String): File? {
        val trimmed = filePath.trim()
        if (trimmed.isEmpty()) return null
        val direct = File(trimmed)
        if (direct.isAbsolute && direct.exists() && direct.isFile) return direct
        val root = resolveStorageDir()
        val relCandidate = File(root, trimmed.replace('\\', '/'))
        if (relCandidate.exists() && relCandidate.isFile) return relCandidate
        val norm = trimmed.replace('\\', '/')
        val chatsIdx = norm.indexOf("chats/")
        if (chatsIdx >= 0) {
            val fromChats = File(root, norm.substring(chatsIdx))
            if (fromChats.exists() && fromChats.isFile) return fromChats
        }
        val mediaIdx = norm.lastIndexOf("/media/")
        if (mediaIdx >= 0) {
            val fileNameOnly = norm.substring(mediaIdx + 7)
            val legacyMedia = File(File(root, "media"), fileNameOnly)
            if (legacyMedia.exists() && legacyMedia.isFile) return legacyMedia
        }
        return null
    }

    actual fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray, sessionId: String): String {
        val mediaDir = if (sessionId.isNotBlank()) {
            File(chatSessionFolder(sessionId), "media")
        } else {
            File(resolveStorageDir(), "media")
        }
        if (!mediaDir.exists()) mediaDir.mkdirs()
        val safeName = fileName.replace(SAFE_SESSION_NAME_REGEX, "_")
        val outFile = File(mediaDir, safeName)
        outFile.writeBytes(bytes)
        synchronized(imageCache) {
            imageCache.remove(outFile.absolutePath)
            imageCache.remove(fileName)
        }
        return outFile.absolutePath
    }

    actual fun relocateLegacyMediaToChatFolder(filePath: String, sessionId: String): String {
        if (filePath.isBlank() || sessionId.isBlank()) return filePath
        val resolved = resolveLocalMediaFile(filePath) ?: return filePath
        val safeSessionId = sanitizeSessionFileName(sessionId)
        val targetMediaDir = File(chatSessionFolder(safeSessionId), "media")
        val resolvedNorm = resolved.absolutePath.replace('\\', '/')
        val targetDirNorm = targetMediaDir.absolutePath.replace('\\', '/')
        if (resolvedNorm.startsWith("$targetDirNorm/")) {
            return resolved.absolutePath
        }
        return runCatching {
            if (!targetMediaDir.exists()) targetMediaDir.mkdirs()
            val targetFile = File(targetMediaDir, resolved.name)
            if (!targetFile.exists() || targetFile.length() != resolved.length()) {
                resolved.copyTo(targetFile, overwrite = true)
            }
            val legacyRootMediaDir = File(resolveStorageDir(), "media").canonicalFile
            if (resolved.canonicalFile.parentFile == legacyRootMediaDir) {
                runCatching { resolved.delete() }
            }
            targetFile.absolutePath
        }.getOrDefault(resolved.absolutePath)
    }

    actual fun createZipArchive(
        entries: List<Pair<String, ByteArray>>,
        fileEntries: List<Pair<String, String>>
    ): ByteArray {
        val baos = ByteArrayOutputStream(maxOf(4096, entries.sumOf { it.second.size }))
        val seenNames = HashSet<String>()
        val copyBuf = ByteArray(8192)
        ZipOutputStream(baos).use { zos ->
            for ((rawEntryPath, data) in entries) {
                val cleanEntry = rawEntryPath.replace('\\', '/').trimStart('/')
                if (cleanEntry.isEmpty() || cleanEntry.contains("..") || !seenNames.add(cleanEntry)) continue
                zos.putNextEntry(ZipEntry(cleanEntry))
                zos.write(data)
                zos.closeEntry()
            }
            for ((rawEntryPath, localPath) in fileEntries) {
                val cleanEntry = rawEntryPath.replace('\\', '/').trimStart('/')
                if (cleanEntry.isEmpty() || cleanEntry.contains("..") || !seenNames.add(cleanEntry)) continue
                val f = resolveLocalMediaFile(localPath) ?: continue
                zos.putNextEntry(ZipEntry(cleanEntry))
                FileInputStream(f).use { fis ->
                    while (true) {
                        val n = fis.read(copyBuf)
                        if (n <= 0) break
                        zos.write(copyBuf, 0, n)
                    }
                }
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    actual fun extractZipArchiveToChatStorage(zipBytes: ByteArray): List<String> = runCatching {
        val extractedChatJsons = mutableListOf<String>()
        val copyBuf = ByteArray(8192)
        val chatsRoot = chatsDir().canonicalFile
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val normName = entry.name.replace('\\', '/').trimStart('/')
                if (normName.isEmpty() || normName.split('/').any { it == ".." }) {
                    zis.closeEntry()
                    continue
                }
                val lowerName = normName.lowercase()
                if (lowerName.endsWith(".json")) {
                    val out = ByteArrayOutputStream()
                    var total = 0
                    while (total < 20_000_000) {
                        val n = zis.read(copyBuf)
                        if (n <= 0) break
                        out.write(copyBuf, 0, n)
                        total += n
                    }
                    val jsonStr = out.toByteArray().decodeToString()
                    if (jsonStr.isNotBlank()) extractedChatJsons.add(jsonStr)
                } else if (lowerName.contains("/media/") || lowerName.startsWith("media/")) {
                    val relUnderChats = when {
                        normName.startsWith("chats/", ignoreCase = true) -> normName.substring(6)
                        else -> normName
                    }
                    val parts = relUnderChats.split('/').filter { it.isNotEmpty() }
                    val targetFile = if (parts.size >= 3 && parts[1].equals("media", ignoreCase = true)) {
                        val safeSess = sanitizeSessionFileName(parts[0])
                        val safeMediaName = parts.last().replace(SAFE_SESSION_NAME_REGEX, "_")
                        File(File(chatsRoot, "$safeSess/media"), safeMediaName)
                    } else {
                        val safeMediaName = parts.last().replace(SAFE_SESSION_NAME_REGEX, "_")
                        File(File(resolveStorageDir(), "media"), safeMediaName)
                    }
                    val canonicalTarget = targetFile.canonicalFile
                    if (canonicalTarget.toPath().normalize().startsWith(resolveStorageDir().canonicalFile.toPath().normalize())) {
                        canonicalTarget.parentFile?.mkdirs()
                        FileOutputStream(canonicalTarget).use { fos ->
                            var total = 0L
                            while (total < 150_000_000L) {
                                val n = zis.read(copyBuf)
                                if (n <= 0) break
                                fos.write(copyBuf, 0, n)
                                total += n
                            }
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        extractedChatJsons
    }.getOrDefault(emptyList())

    actual fun readBinaryFileBytes(filePath: String): ByteArray? = runCatching {
        val f = resolveLocalMediaFile(filePath) ?: return null
        if (f.exists() && f.isFile) f.readBytes() else null
    }.getOrNull()

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? {
        if (filePath.isBlank()) return null
        synchronized(imageCache) { imageCache[filePath]?.let { return it } }
        return runCatching {
            val f = resolveLocalMediaFile(filePath) ?: return null
            synchronized(imageCache) { imageCache[f.absolutePath]?.let { return it } }
            // Decode bounds first and downsample large 2K/4K images for UI display to minimize RAM
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, boundsOpts)
            val maxDim = 1600
            var sampleSize = 1
            while ((boundsOpts.outWidth / sampleSize) > maxDim || (boundsOpts.outHeight / sampleSize) > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, decodeOpts)?.asImageBitmap() ?: return null
            synchronized(imageCache) {
                if (imageCache.size >= 16) {
                    imageCache.keys.firstOrNull()?.let { imageCache.remove(it) }
                }
                imageCache[filePath] = bmp
            }
            bmp
        }.getOrNull()
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

    fun readUriAsPickedFile(uri: Uri, hintMimeType: String = ""): PickedBinaryFile? = runCatching {
        val ctx = appContext ?: return@runCatching null
        val bytes = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { File(it).takeIf { f -> f.exists() && f.isFile }?.readBytes() }
            else -> ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null

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
        if (displayName.isBlank()) {
            displayName = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        }
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
            isCurrentlySpeaking = true
            val ctx = appContext
            if (ttsEngine == null || !ttsReady) {
                pendingUtteranceToSpeak = cleaned
                if (ctx != null) ensureTtsInitialized(ctx)
            } else {
                dispatchTtsChunks(cleaned)
            }
        }.onFailure {
            isCurrentlySpeaking = false
            onFinished()
        }
    }

    actual fun stopSpeakingAloud() {
        runCatching {
            isCurrentlySpeaking = false
            pendingUtteranceToSpeak = null
            val cb = pendingTtsFinishedCallback
            pendingTtsFinishedCallback = null
            ttsEngine?.stop()
            cb?.invoke()
        }
    }

    actual fun isSpeakingAloud(): Boolean = isCurrentlySpeaking || (runCatching { ttsEngine?.isSpeaking == true }.getOrDefault(false))

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
