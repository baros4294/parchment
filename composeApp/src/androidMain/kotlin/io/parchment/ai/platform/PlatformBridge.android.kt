package io.parchment.ai.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.parchment.ai.LongDecodeForegroundService
import io.parchment.ai.MainActivity
import io.parchment.ai.OtaInstallReceiver
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

actual object PlatformBridge {
    actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    actual val isAndroid: Boolean = true

    private var appContext: Context? = null
    private var mainActivityRef: WeakReference<MainActivity>? = null
    private val fontCache = ConcurrentHashMap<String, FontFamily>()
    private val imageCache = ConcurrentHashMap<String, ImageBitmap>()

    fun initAndroid(context: Context, activity: MainActivity) {
        appContext = context.applicationContext
        mainActivityRef = WeakReference(activity)
        // Ensure default local workspace folder exists inside noBackupFilesDir
        val wsDir = File(resolveStorageDir(), "workspace")
        if (!wsDir.exists()) wsDir.mkdirs()
    }

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    private fun resolveStorageDir(): File {
        val ctx = appContext
        val dir = ctx?.noBackupFilesDir ?: File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "parchment")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    actual fun getAppStorageDirectoryPath(): String = resolveStorageDir().absolutePath

    private fun stateFile(): File = File(resolveStorageDir(), "parchment-state.json")

    actual fun loadAppStateJson(): String? = runCatching {
        val f = stateFile()
        if (f.exists() && f.isFile) f.readText(Charsets.UTF_8) else null
    }.getOrNull()

    actual fun saveAppStateJson(json: String) {
        runCatching {
            val dir = resolveStorageDir()
            if (!dir.exists()) dir.mkdirs()
            stateFile().writeText(json, Charsets.UTF_8)
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
        val entries = (target.listFiles()?.toList() ?: emptyList())
            .sortedBy { it.name.lowercase() }
            .map { f ->
                val rel = rootPath.relativize(f.canonicalFile.toPath()).toString()
                if (f.isDirectory) "$rel/" else "$rel (${f.length()} bytes)"
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
        val existing = target.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val normOld = oldText.replace("\r\n", "\n")
        val normNew = newText.replace("\r\n", "\n")
        require(existing.contains(normOld)) { "old_text not found in '$relativePath'." }
        val updated = if (replaceAll) existing.replace(normOld, normNew) else existing.replaceFirst(normOld, normNew)
        target.writeText(updated, Charsets.UTF_8)
        "Edited '$relativePath' (+${normNew.lines().size} / -${normOld.lines().size} lines)."
    }

    actual fun workspaceShellExec(workspaceRoot: String, command: String, timeoutSeconds: Int): Result<String> = runCatching {
        val rootDir = File(workspaceRoot).canonicalFile
        require(rootDir.exists() && rootDir.isDirectory) { "Workspace root does not exist: $workspaceRoot" }
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(timeoutSeconds.coerceIn(1, 120).toLong(), java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            error("Command timed out after $timeoutSeconds seconds.")
        }
        val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        "Exit Code: ${proc.exitValue()}\n${output.take(6000)}"
    }

    actual fun workspaceSearch(workspaceRoot: String, query: String, fileGlob: String): Result<String> = runCatching {
        val rootFile = File(workspaceRoot).canonicalFile
        val rootPath = rootFile.toPath().normalize()
        val matches = mutableListOf<String>()
        rootFile.walkTopDown().maxDepth(5).filter { it.isFile && it.length() < 1_500_000L }.forEach { f ->
            if (matches.size >= 100) return@forEach
            val rel = rootPath.relativize(f.canonicalFile.toPath()).toString()
            f.useLines(Charsets.UTF_8) { seq ->
                seq.forEachIndexed { idx, line ->
                    if (matches.size < 100 && line.contains(query, ignoreCase = true)) {
                        matches.add("$rel:${idx + 1}: ${line.trim().take(160)}")
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
        imageCache.remove(outFile.absolutePath)
        return outFile.absolutePath
    }

    actual fun loadImageBitmapFromFile(filePath: String): ImageBitmap? {
        if (filePath.isBlank()) return null
        imageCache[filePath]?.let { return it }
        return runCatching {
            val f = File(filePath)
            if (!f.exists() || !f.isFile) return null
            val bmp = BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() ?: return null
            imageCache[filePath] = bmp
            bmp
        }.getOrNull()
    }

    actual fun openOrPlayMedia(localFilePathOrUrl: String) {
        val ctx = appContext ?: return
        runCatching {
            val uri = if (localFilePathOrUrl.startsWith("http://") || localFilePathOrUrl.startsWith("https://")) {
                Uri.parse(localFilePathOrUrl)
            } else {
                Uri.fromFile(File(localFilePathOrUrl))
            }
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(viewIntent)
        }
    }
}
