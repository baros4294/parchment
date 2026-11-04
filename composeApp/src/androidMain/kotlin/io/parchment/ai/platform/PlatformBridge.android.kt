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
import android.os.PowerManager
import android.provider.OpenableColumns
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
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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

    private fun stateFile(): File = File(resolveStorageDir(), "parchment-state.json")

    private fun isBlankDefaultStateJson(json: String): Boolean {
        val hasNoApiKeys = Regex("\"apiKey\":\"[^\"]+\"").find(json) == null
        val hasNoAssistants = json.contains("\"assistants\":[]") || (
            !Regex("\"id\":\"asst-(?!default\")").containsMatchIn(json) &&
                !Regex("\"systemPrompt\":\"[^\"]+\"").containsMatchIn(json)
            )
        val hasNoMessages = !json.contains("\"role\":\"USER\"") && !json.contains("\"role\":\"ASSISTANT\"")
        val hasNoSavedCards = !json.contains("\"savedCards\":[{")
        val hasNoPresets = !json.contains("\"systemPromptPresets\":[{")
        return hasNoApiKeys && hasNoAssistants && hasNoMessages && hasNoSavedCards && hasNoPresets
    }

    actual fun loadAppStateJson(): String? = runCatching {
        val dir = resolveStorageDir()
        val f = stateFile()
        val tmp = File(dir, "parchment-state.json.tmp")
        val prev = File(dir, "parchment-state.prev.json")
        val userBak = File(dir, "parchment-state.user-backup.json")
        val legacy = appContext?.noBackupFilesDir?.let { File(it, "parchment-state.json") }
        val candidates = listOfNotNull(f, tmp, prev, userBak, legacy)
        for (cand in candidates) {
            if (cand.exists() && cand.isFile && cand.length() > 10L) {
                val content = cand.readText(Charsets.UTF_8)
                if (cand != userBak && isBlankDefaultStateJson(content) && userBak.exists() && userBak.length() > 50L) {
                    val bakContent = runCatching { userBak.readText(Charsets.UTF_8) }.getOrNull()
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
            val tmp = File(dir, "parchment-state.json.tmp")
            val prev = File(dir, "parchment-state.prev.json")
            val userBak = File(dir, "parchment-state.user-backup.json")
            val target = stateFile()

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
        }
    }

    actual fun registerStateFlushCallback(callback: () -> Unit) {
        stateFlushCallback = callback
    }

    actual fun flushPendingStateNow() {
        runCatching { stateFlushCallback?.invoke() }
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

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestImportFile(arrayOf("image/png", "application/json", "text/plain", "*/*"))
    }

    actual suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String? {
        val act = mainActivityRef?.get() ?: return null
        return act.requestExportFile(defaultFileName, bytes)
    }

    actual suspend fun pickWorkspaceDirectory(currentPath: String): String? {
        val wsDir = File(resolveStorageDir(), "workspace")
        if (!wsDir.exists()) wsDir.mkdirs()
        return wsDir.absolutePath
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

    actual fun workspaceList(workspaceRoot: String, relativePath: String, recursive: Boolean): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        val rootPath = File(workspaceRoot).canonicalFile.toPath().normalize()
        require(target.exists() && target.isDirectory) { "Directory not found: $relativePath" }
        val entries = if (recursive) {
            target.walkTopDown()
                .maxDepth(5)
                .filter { it != target && !it.name.startsWith(".git") && !it.name.equals("build", ignoreCase = true) }
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

    actual fun workspaceRead(workspaceRoot: String, relativePath: String, startLine: Int, maxLines: Int): Result<String> = runCatching {
        val target = resolveSandboxedPath(workspaceRoot, relativePath).toFile()
        require(target.exists() && target.isFile) { "File not found: $relativePath" }
        val lines = target.readLines(Charsets.UTF_8)
        val startIdx = (startLine - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val endIdx = (startIdx + maxLines.coerceIn(1, 2000)).coerceAtMost(lines.size)
        val sliced = lines.subList(startIdx, endIdx)
        buildString {
            appendLine("File: $relativePath (Lines ${startIdx + 1}..$endIdx of ${lines.size})")
            sliced.forEachIndexed { idx, line ->
                appendLine("${startIdx + idx + 1}: $line")
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
        require(oldText.isNotEmpty()) { "old_text must not be empty." }
        val existingRaw = target.readText(Charsets.UTF_8)
        val usesCrlf = existingRaw.contains("\r\n")
        val existing = existingRaw.replace("\r\n", "\n")
        val normOld = oldText.replace("\r\n", "\n")
        val normNew = newText.replace("\r\n", "\n")
        if (!existing.contains(normOld)) {
            error("old_text was not found in '$relativePath' (${existing.lines().size} lines). Use fs_read to inspect the exact lines before editing.")
        }
        val updatedLf = if (replaceAll) {
            existing.replace(normOld, normNew)
        } else {
            val firstIdx = existing.indexOf(normOld)
            val secondIdx = existing.indexOf(normOld, startIndex = firstIdx + normOld.length)
            if (secondIdx != -1) {
                val matchLines = mutableListOf<Int>()
                var searchIdx = 0
                while (true) {
                    val hit = existing.indexOf(normOld, startIndex = searchIdx)
                    if (hit == -1) break
                    val lineNum = existing.substring(0, hit).count { it == '\n' } + 1
                    matchLines.add(lineNum)
                    searchIdx = hit + normOld.length.coerceAtLeast(1)
                }
                error("old_text matched ${matchLines.size} locations in '$relativePath' (at lines ${matchLines.joinToString(", ")}). Include more surrounding context or set replace_all=true.")
            }
            existing.replaceFirst(normOld, normNew)
        }
        val finalContent = if (usesCrlf) updatedLf.replace("\n", "\r\n") else updatedLf
        target.writeText(finalContent, Charsets.UTF_8)
        "Edited '$relativePath' (+${normNew.lines().size} / -${normOld.lines().size} lines)."
    }

    actual fun workspaceShellExec(workspaceRoot: String, command: String, timeoutSeconds: Int): Result<String> = runCatching {
        val rootDir = File(workspaceRoot).canonicalFile
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        runCatching { proc.outputStream.close() }

        val outputBuf = StringBuilder()
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val readCount = reader.read(chunk)
                        if (readCount < 0) break
                        synchronized(outputBuf) {
                            if (outputBuf.length < 48_000) {
                                val allowed = (48_000 - outputBuf.length).coerceAtMost(readCount)
                                outputBuf.append(chunk, 0, allowed)
                            }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "parchment-android-shell-reader"
            start()
        }

        val finished = proc.waitFor(timeoutSeconds.coerceIn(1, 120).toLong(), java.util.concurrent.TimeUnit.SECONDS)
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
        "Exit Code: ${proc.exitValue()}\n${output.take(6000)}"
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
        require(rootFile.exists() && rootFile.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        require(query.isNotBlank()) { "Search query must not be blank." }
        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val matches = mutableListOf<String>()
        rootFile.walkTopDown()
            .maxDepth(5)
            .filter { f ->
                f.isFile &&
                    !f.path.contains(".git") &&
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

    actual fun notifyLongDecodeStart(modelName: String) {
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

    actual fun saveGeneratedMediaFile(fileName: String, bytes: ByteArray): String {
        val mediaDir = File(resolveStorageDir(), "media")
        if (!mediaDir.exists()) mediaDir.mkdirs()
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(mediaDir, safeName)
        outFile.writeBytes(bytes)
        synchronized(imageCache) { imageCache.remove(outFile.absolutePath) }
        return outFile.absolutePath
    }

    actual fun readBinaryFileBytes(filePath: String): ByteArray? = runCatching {
        if (filePath.isBlank()) return null
        val f = File(filePath)
        if (f.exists() && f.isFile) f.readBytes() else null
    }.getOrNull()

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? {
        if (filePath.isBlank()) return null
        synchronized(imageCache) { imageCache[filePath]?.let { return it } }
        return runCatching {
            val f = File(filePath)
            if (!f.exists() || !f.isFile) return null
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
                val f = File(target)
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
