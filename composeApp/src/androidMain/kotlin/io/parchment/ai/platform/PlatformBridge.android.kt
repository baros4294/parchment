package io.parchment.ai.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import io.parchment.ai.LongDecodeForegroundService
import io.parchment.ai.MainActivity
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Path

actual object PlatformBridge {
    actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    actual val isAndroid: Boolean = true

    private var appContext: Context? = null
    private var mainActivityRef: WeakReference<MainActivity>? = null

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
        val existing = target.readText(Charsets.UTF_8)
        require(existing.contains(oldText)) { "old_text not found in '$relativePath'." }
        val updated = if (replaceAll) existing.replace(oldText, newText) else existing.replaceFirst(oldText, newText)
        target.writeText(updated, Charsets.UTF_8)
        "Successfully edited '$relativePath'."
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
}
