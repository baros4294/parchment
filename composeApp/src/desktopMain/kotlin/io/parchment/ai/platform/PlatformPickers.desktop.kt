package io.parchment.ai.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilenameFilter
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object PlatformPickers {
    private val fontCache = ConcurrentHashMap<String, FontFamily>()

    @Volatile
    var dragHoverCallback: ((Boolean) -> Unit)? = null
    @Volatile
    var filesDroppedCallback: ((List<PickedBinaryFile>) -> Unit)? = null

    actual suspend fun pickFileForImport(allowedExtensions: List<String>): PickedBinaryFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                chooseImportFiles(allowedExtensions).firstOrNull { it.isFile }?.let {
                    pickedFileFromDisk(it, maxPickedFileBytes(allowedExtensions), PlatformRuntime.currentTimeMillis())
                }
            }.getOrNull()
        }

    private fun chooseImportFiles(allowedExtensions: List<String>): List<File> {
        val allowAll = allowedExtensions.isEmpty() || allowedExtensions.contains("*")
        val title = if (allowAll) "Attach Image, Video, or File(s)" else "Import File(s) into Parchment"
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (!allowAll && allowedExtensions.isNotEmpty()) {
            dialog.file = allowedExtensions.joinToString(";") { "*.$it" }
            dialog.filenameFilter = FilenameFilter { _, name ->
                allowedExtensions.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
            }
        }
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty().ifEmpty {
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) listOf(File(dir, file)) else emptyList()
        }
    }

    actual suspend fun <T> pickAndImportChatFiles(decode: (chatJsons: List<String>) -> T): List<ChatImportOutcome<T>> =
        withContext(Dispatchers.IO) {
            chooseImportFiles(listOf("zip", "json")).filter { it.isFile }.map { file ->
                ChatImportOutcome(file.name, runCatching {
                    ChatArchiveExtractor.importChatFile(
                        file.name,
                        FileInputStream(file),
                        DesktopPlatformHost.resolveStorageDir(),
                        decode = decode
                    )
                })
            }
        }

    /** Refuses an oversized file from its length alone, so none of it is read. */
    internal fun pickedFileFromDisk(target: File, maxBytes: Long, fallbackTimestamp: Long): PickedBinaryFile =
        if (target.length() > maxBytes) {
            PickedBinaryFile(target.name, ByteArray(0), oversized = true)
        } else {
            normalizePickedBinaryFile(rawFileName = target.name, bytes = target.readBytes(), fallbackTimestamp = fallbackTimestamp)
        }

    actual suspend fun saveFileForExport(defaultFileName: String, bytes: ByteArray): String? =
        runCatching { exportStreamed(defaultFileName) { it.write(bytes) } }.getOrNull()

    actual suspend fun exportChatBundle(defaultFileName: String, chats: Sequence<ChatBundleEntry>): String? =
        exportStreamed(defaultFileName) { ChatArchiveWriter.writeBundle(it, chats, PlatformStorage::resolveLocalMediaFile) }

    actual suspend fun exportLocalFile(defaultFileName: String, filePath: String): String? {
        val source = PlatformStorage.resolveLocalMediaFile(filePath) ?: return null
        return exportStreamed(defaultFileName) { output -> FileInputStream(source).use { it.copyTo(output, 64 * 1024) } }
    }

    private suspend fun exportStreamed(defaultFileName: String, write: (OutputStream) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, "Export from Parchment", FileDialog.SAVE)
            dialog.file = defaultFileName
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir == null || file == null) return@withContext null
            File(dir, file).also { AtomicFileStorage.writeStream(it, write = write) }.absolutePath
        }

    actual suspend fun pickWorkspaceDirectory(currentPath: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val chooser = JFileChooser()
                chooser.dialogTitle = "Select Local Workspace Folder for Agent Harness"
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (currentPath.isNotBlank()) {
                    val cur = File(currentPath)
                    if (cur.exists()) chooser.currentDirectory = cur
                }
                val res = chooser.showOpenDialog(null)
                if (res == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                    chooser.selectedFile.absolutePath
                } else null
            }.getOrNull()
        }

    actual suspend fun pickFontFile(dialogTitle: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dialog = FileDialog(null as Frame?, dialogTitle, FileDialog.LOAD)
                dialog.file = "*.ttf;*.otf;*.ttc"
                dialog.filenameFilter = FilenameFilter { _, name ->
                    name.endsWith(".ttf", ignoreCase = true) ||
                        name.endsWith(".otf", ignoreCase = true) ||
                        name.endsWith(".ttc", ignoreCase = true)
                }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val selected = File(dir, file)
                    if (selected.exists() && selected.isFile) {
                        selected.absolutePath
                    } else null
                } else null
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
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                arrayOf(DataFlavor.imageFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                flavor == DataFlavor.imageFlavor

            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        true
    }.getOrDefault(false)

    actual fun loadBundledFontFamily(bundleName: String): FontFamily? {
        val key = "bundled:$bundleName"
        fontCache[key]?.let { return it }

        fun readResBytes(resPath: String): ByteArray? =
            Thread.currentThread().contextClassLoader?.getResourceAsStream(resPath)?.use { it.readBytes() }
                ?: PlatformPickers::class.java.classLoader?.getResourceAsStream(resPath)?.use { it.readBytes() }

        return runCatching {
            val family = when (bundleName.lowercase()) {
                "inter" -> {
                    val reg = readResBytes("fonts/Inter-Regular.ttf") ?: return null
                    val ital = readResBytes("fonts/Inter-Italic.ttf") ?: reg
                    FontFamily(
                        Font(identity = "Inter-Regular", data = reg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "Inter-Medium", data = reg, weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(identity = "Inter-SemiBold", data = reg, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "Inter-Bold", data = reg, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "Inter-Italic", data = ital, weight = FontWeight.Normal, style = FontStyle.Italic),
                        Font(identity = "Inter-BoldItalic", data = ital, weight = FontWeight.Bold, style = FontStyle.Italic)
                    )
                }
                "jetbrains mono", "jetbrainsmono nerd", "jetbrainsmono", "nerd" -> {
                    val reg = readResBytes("fonts/JetBrainsMono-Regular.ttf") ?: return null
                    val semi = readResBytes("fonts/JetBrainsMono-SemiBold.ttf") ?: reg
                    val bold = readResBytes("fonts/JetBrainsMono-Bold.ttf") ?: reg
                    val ital = readResBytes("fonts/JetBrainsMono-Italic.ttf") ?: reg
                    FontFamily(
                        Font(identity = "JBM-Regular", data = reg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "JBM-Medium", data = reg, weight = FontWeight.Medium, style = FontStyle.Normal),
                        Font(identity = "JBM-SemiBold", data = semi, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "JBM-Bold", data = bold, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "JBM-Italic", data = ital, weight = FontWeight.Normal, style = FontStyle.Italic)
                    )
                }
                "latex", "katex", "math" -> {
                    val katexReg = readResBytes("fonts/KaTeX_Main-Regular.ttf") ?: return null
                    val katexBold = readResBytes("fonts/KaTeX_Main-Bold.ttf") ?: katexReg
                    val katexItal = readResBytes("fonts/KaTeX_Math-Italic.ttf") ?: katexReg
                    val notoMath = readResBytes("fonts/NotoSansMath-Regular.ttf") ?: katexReg
                    FontFamily(
                        Font(identity = "KaTeX-Regular", data = katexReg, weight = FontWeight.Normal, style = FontStyle.Normal),
                        Font(identity = "KaTeX-Bold", data = katexBold, weight = FontWeight.Bold, style = FontStyle.Normal),
                        Font(identity = "KaTeX-SemiBold", data = katexBold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
                        Font(identity = "KaTeX-MathItalic", data = katexItal, weight = FontWeight.Normal, style = FontStyle.Italic),
                        Font(identity = "NotoSansMath-Regular", data = notoMath, weight = FontWeight.Medium, style = FontStyle.Normal)
                    )
                }
                else -> return null
            }
            fontCache[key] = family
            family
        }.getOrNull()
    }

    fun extractTransferableFiles(contents: Transferable): List<PickedBinaryFile> = runCatching {
        // 1. Check for copied/dropped files from Explorer (images, GIFs, videos, PDFs, audio, code, etc.)
        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val fileList = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            val picked = fileList?.mapNotNull { item ->
                val f = item as? File
                if (f != null && f.exists() && f.isFile) {
                    runCatching { pickedFileFromDisk(f, MAX_INLINE_ATTACHMENT_BYTES, PlatformRuntime.currentTimeMillis()) }.getOrNull()
                } else null
            }.orEmpty()
            if (picked.isNotEmpty()) return@runCatching picked
        }

        // 2. Check for raw binary image InputStream flavors (preserves animated GIFs & WebP from browsers)
        val rawImageFlavor = contents.transferDataFlavors.firstOrNull { flavor ->
            val mime = "${flavor.primaryType}/${flavor.subType}".lowercase()
            (mime == "image/gif" || mime == "image/webp" || mime == "image/png" || mime == "image/jpeg") &&
                InputStream::class.java.isAssignableFrom(flavor.representationClass)
        }
        if (rawImageFlavor != null) {
            val mime = "${rawImageFlavor.primaryType}/${rawImageFlavor.subType}".lowercase()
            val streamBytes = runCatching {
                (contents.getTransferData(rawImageFlavor) as? InputStream)?.use { readAtMost(it, MAX_INLINE_ATTACHMENT_BYTES) }
            }.getOrNull()
            if (streamBytes != null && streamBytes.isNotEmpty()) {
                return@runCatching listOf(
                    normalizePickedBinaryFile(
                        rawFileName = "",
                        bytes = streamBytes,
                        explicitMimeType = mime,
                        fallbackTimestamp = PlatformRuntime.currentTimeMillis()
                    )
                )
            }
        }

        // 3. Check for raw clipboard/dragged AWT Image (Snipping Tool Win+Shift+S, PrintScreen, Browser Copy Image)
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val rawAwtImage = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
            if (rawAwtImage != null) {
                val buffered = if (rawAwtImage is BufferedImage) {
                    rawAwtImage
                } else {
                    // Use ImageIcon's MediaTracker so asynchronous ToolkitImage dimensions (>0) finish loading
                    val loaded = ImageIcon(rawAwtImage).image
                    val w = loaded.getWidth(null).coerceAtLeast(1)
                    val h = loaded.getHeight(null).coerceAtLeast(1)
                    val b = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                    val g = b.createGraphics()
                    g.drawImage(loaded, 0, 0, null)
                    g.dispose()
                    b
                }
                val baos = ByteArrayOutputStream()
                ImageIO.write(buffered, "png", baos)
                val bytes = baos.toByteArray()
                if (bytes.isNotEmpty()) {
                    return@runCatching listOf(
                        normalizePickedBinaryFile(
                            rawFileName = "clipboard-${PlatformRuntime.currentTimeMillis()}.png",
                            bytes = bytes,
                            explicitMimeType = "image/png",
                            fallbackTimestamp = PlatformRuntime.currentTimeMillis()
                        )
                    )
                }
            }
        }

        emptyList()
    }.getOrDefault(emptyList())

    actual fun readClipboardFiles(): List<PickedBinaryFile> = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null) ?: return@runCatching emptyList()
        extractTransferableFiles(contents)
    }.getOrDefault(emptyList())

    actual fun registerFileDropCallbacks(
        onDragHover: (Boolean) -> Unit,
        onFilesDropped: (List<PickedBinaryFile>) -> Unit
    ) {
        dragHoverCallback = onDragHover
        filesDroppedCallback = onFilesDropped
    }
}
