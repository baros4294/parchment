package io.parchment.ai.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.ContentInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.parchment.ai.ParchmentFileProvider
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalEncodingApi::class)
actual object PlatformPickers {
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

    private val fontCache = ConcurrentHashMap<String, FontFamily>()
    private val pendingSharedOrPastedFiles = mutableListOf<PickedBinaryFile>()
    @Volatile
    private var filesDroppedCallback: ((List<PickedBinaryFile>) -> Unit)? = null

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? {
        val act = AndroidPlatformHost.mainActivityRef?.get() ?: return null
        return act.requestImportFile(arrayOf("image/png", "application/json", "text/plain", "*/*"), maxPickedFileBytes(allowedExtensions))
    }

    actual suspend fun <T> pickAndImportChatFiles(decode: (chatJsons: List<String>) -> T): List<ChatImportOutcome<T>> {
        val act = AndroidPlatformHost.mainActivityRef?.get() ?: return emptyList()
        val ctx = AndroidPlatformHost.appContext ?: return emptyList()
        val mimes = arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "*/*")
        val uris = act.requestImportDocumentUris(mimes)
        return withContext(Dispatchers.IO) {
            uris.map { uri ->
                val name = uriDisplayName(ctx, uri)
                ChatImportOutcome(name, runCatching {
                    val source = checkNotNull(ctx.contentResolver.openInputStream(uri)) { "Unable to open the selected file." }
                    ChatArchiveExtractor.importChatFile(name, source, AndroidPlatformHost.resolveStorageDir(), decode = decode)
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
        val act = AndroidPlatformHost.mainActivityRef?.get() ?: return null
        return act.requestExportFile(defaultFileName, bytes)
    }

    actual suspend fun exportChatBundle(defaultFileName: String, chats: Sequence<ChatBundleEntry>): String? {
        val act = AndroidPlatformHost.mainActivityRef?.get() ?: return null
        return act.requestExportStream(defaultFileName) { ChatArchiveWriter.writeBundle(it, chats, PlatformStorage::resolveLocalMediaFile) }
    }

    actual suspend fun exportLocalFile(defaultFileName: String, filePath: String): String? {
        val act = AndroidPlatformHost.mainActivityRef?.get() ?: return null
        val source = PlatformStorage.resolveLocalMediaFile(filePath) ?: return null
        return act.requestExportStream(defaultFileName) { output -> FileInputStream(source).use { it.copyTo(output, 64 * 1024) } }
    }

    actual suspend fun pickWorkspaceDirectory(currentPath: String): String? {
        val act = AndroidPlatformHost.mainActivityRef?.get()
        if (act != null) {
            return act.requestPickWorkspaceDirectory(currentPath)
        }
        val wsDir = File(AndroidPlatformHost.resolveStorageDir(), "workspace")
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
        val act = AndroidPlatformHost.mainActivityRef?.get() ?: return null
        val picked = act.requestImportFile(
            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"),
            maxPickedFileBytes(listOf("ttf", "otf"))
        )?.takeUnless { it.oversized } ?: return null
        return runCatching {
            val fontsDir = File(AndroidPlatformHost.resolveStorageDir(), "fonts")
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
        val ctx = AndroidPlatformHost.appContext ?: return false
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
        val assets = AndroidPlatformHost.appContext?.assets ?: return null

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

    fun readUriAsPickedFile(
        uri: Uri,
        hintMimeType: String = "",
        maxBytes: Long = MAX_INLINE_ATTACHMENT_BYTES
    ): PickedBinaryFile? = runCatching {
        val ctx = AndroidPlatformHost.appContext ?: return@runCatching null
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
            fallbackTimestamp = PlatformRuntime.currentTimeMillis()
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
        val ctx = AndroidPlatformHost.appContext ?: return@runCatching emptyList()
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
                            fallbackTimestamp = PlatformRuntime.currentTimeMillis() + i
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
}
