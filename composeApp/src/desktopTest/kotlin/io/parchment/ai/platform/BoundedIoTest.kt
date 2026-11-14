package io.parchment.ai.platform

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.downloadMediaBytes
import io.parchment.ai.network.oversizedAttachmentMessage
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlin.test.*

class BoundedIoTest {
    private val root: File = Files.createTempDirectory("parchment-bounded-io-").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun capture(text: String, head: Int, tail: Int) = BoundedTextCapture(head, tail).apply {
        text.chunked(7).forEach { append(it.toCharArray(), it.length) }
    }

    private fun lines(content: String, maxLineChars: Int = 100): List<Pair<String, Boolean>> {
        val file = File(root, "lines.txt").apply { writeText(content) }
        val result = mutableListOf<Pair<String, Boolean>>()
        WorkspaceTextReader.forEachBoundedLine(file, maxLineChars) { line, truncated -> result += line to truncated }
        return result
    }

    @Test fun shortOutputIsReturnedUnchanged() {
        assertEquals("all of it", capture("all of it", 100, 100).snapshot())
    }

    @Test fun longOutputKeepsTheEndAndSaysWhatWasOmitted() {
        val text = "START" + "x".repeat(100_000) + "FINAL-ERROR"
        val result = capture(text, 1_000, 1_000).snapshot()
        assertTrue(result.startsWith("START"))
        assertTrue(result.endsWith("FINAL-ERROR"), "The end of the output is usually the failure")
        assertTrue("characters omitted" in result)
        assertTrue(result.length < 2_100)
    }

    @Test fun smallerSnapshotStaysWithinItsLimitAndCountsOmissions() {
        val text = (0 until 50_000).joinToString("") { (it % 10).toString() }
        val result = capture(text, 32_000, 32_000).snapshot(8_000)
        assertEquals(text.take(4_000), result.substringBefore("\n…"))
        assertEquals(text.takeLast(4_000), result.substringAfter("…\n"))
        assertTrue("42000 characters omitted" in result, result.substringAfter(text.take(4_000)).take(60))
    }

    @Test fun truncationNeverSplitsASurrogatePair() {
        val result = capture("😀".repeat(5_000), 1_001, 1_001).snapshot(1_002)
        assertTrue("characters omitted" in result)
        result.forEachIndexed { index, character ->
            if (character.isHighSurrogate()) assertTrue(result[index + 1].isLowSurrogate(), "Unpaired high surrogate at $index")
            if (character.isLowSurrogate()) assertTrue(result[index - 1].isHighSurrogate(), "Unpaired low surrogate at $index")
        }
    }

    @Test fun boundedLinesMatchUsualLineSplitting() {
        assertEquals(listOf("a", "b", "c", "d"), lines("a\nb\r\nc\rd").map { it.first })
        assertEquals(listOf("a", "", "b"), lines("a\n\nb\n").map { it.first })
        assertEquals(emptyList(), lines(""))
        assertEquals(listOf(""), lines("\n").map { it.first })
    }

    @Test fun overlongLineIsCutAtTheLimitAndMarked() {
        val result = lines("x".repeat(500) + "\nshort", maxLineChars = 100)
        assertEquals("x".repeat(100) to true, result[0])
        assertEquals("short" to false, result[1])
    }

    @Test fun workspaceReadHandlesAHugeSingleLineWithinBounds() {
        val huge = File(root, "minified.js")
        huge.bufferedWriter().use { writer -> repeat(40) { writer.write("y".repeat(1_000_000)) }; writer.write("\nend") }
        val output = WorkspaceTextReader.read(huge, "minified.js", 1, 10, 200_000)
        assertTrue(output.startsWith("File: minified.js (Lines 1..2 of 2)"), output.take(60))
        assertTrue("[line truncated]" in output)
        assertTrue(output.endsWith("2: end"))
        assertTrue(output.length < 4_200)
    }

    private fun encodedPng(width: Int, height: Int): ByteArray = Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.makeImageSnapshot().use { requireNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes }
    }

    private fun pngClaiming(width: Int, height: Int): ByteArray {
        fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size)
            val typed = type.encodeToByteArray() + data
            out.write(typed)
            out.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
            chunk(out, "IHDR", header.toByteArray())
            chunk(out, "IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0, 0, 0, 1, 0, 1))
            chunk(out, "IEND", ByteArray(0))
        }
        return bytes.toByteArray()
    }

    @Test fun ordinaryImagesKeepTheirSize() {
        val bitmap = assertNotNull(DesktopImageDecoder.decode(encodedPng(640, 480)))
        assertEquals(640, bitmap.width)
        assertEquals(480, bitmap.height)
    }

    @Test fun largeImagesAreDownscaledForDisplay() {
        val bitmap = assertNotNull(DesktopImageDecoder.decode(encodedPng(4_000, 2_000)))
        assertEquals(2_560, bitmap.width)
        assertEquals(1_280, bitmap.height)
    }

    @Test fun decompressionBombsAndGarbageAreRefusedBeforeDecoding() {
        assertNull(runCatching { DesktopImageDecoder.decode(pngClaiming(40_000, 40_000)) }.getOrNull())
        assertNull(runCatching { DesktopImageDecoder.decode("not an image".encodeToByteArray()) }.getOrNull())
        assertNull(DesktopImageDecoder.decode(File(root, "missing.png")))
    }

    private fun <T> serving(status: Int, body: ByteArray, block: suspend (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { exchange ->
            try {
                exchange.sendResponseHeaders(status, 0)
                exchange.responseBody.write(body)
            } finally { exchange.close() }
        }
        server.start()
        try { return runBlocking { block("http://127.0.0.1:${server.address.port}/media") } } finally { server.stop(0) }
    }

    @Test fun mediaDownloadReturnsCompleteBodies() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        assertContentEquals(body, serving(200, body) { AiProviderEngine.downloadMediaBytes(it, 300_000) })
    }

    @Test fun mediaDownloadStopsAtItsLimit() {
        val failure = assertFails { serving(200, ByteArray(300_000)) { AiProviderEngine.downloadMediaBytes(it, 100_000) } }
        assertTrue("limit" in failure.message.orEmpty(), failure.message)
    }

    @Test fun mediaDownloadRejectsErrorPages() {
        val failure = assertFails { serving(404, "not found".encodeToByteArray()) { AiProviderEngine.downloadMediaBytes(it, 100_000) } }
        assertTrue("HTTP 404" in failure.message.orEmpty(), failure.message)
    }

    @Test fun oversizedAttachmentNamesTheFileAndLimit() {
        val message = oversizedAttachmentMessage("holiday.mov", 200L * 1024L * 1024L)
        assertTrue("holiday.mov" in message && "200 MB" in message && "${MAX_INLINE_ATTACHMENT_BYTES / (1024L * 1024L)} MB" in message, message)
    }

    @Test fun localFileSizeReportsMissingFiles() {
        assertEquals(-1L, PlatformStorage.localFileSizeBytes(File(root, "missing.bin").absolutePath))
        val present = File(root, "present.bin").apply { writeBytes(ByteArray(321)) }
        assertEquals(321L, PlatformStorage.localFileSizeBytes(present.absolutePath))
    }

    @Test fun exportKeepsNamesContainingDotsButNotParentSegments() {
        val archive = ByteArrayOutputStream().also { out ->
            ChatArchiveWriter.writeBundle(
                out,
                sequenceOf(
                    ChatBundleEntry("chat/media/a..b.png", byteArrayOf(1), emptyList()),
                    ChatBundleEntry("../escape.json", byteArrayOf(2), emptyList()),
                    ChatBundleEntry("chat/../x.json", byteArrayOf(3), emptyList())
                )
            ) { null }
        }.toByteArray()
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip -> generateSequence { zip.nextEntry }.forEach { names += it.name } }
        assertEquals(listOf("chat/media/a..b.png"), names)
    }

    @Test fun streamedBundleRoundTripsAndSkipsMissingMedia() {
        val photo = File(root, "photo.png").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val json = """{"id":"chat-1"}""".encodeToByteArray()
        val bundle = ByteArrayOutputStream()
        ChatArchiveWriter.writeBundle(
            bundle,
            sequenceOf(ChatBundleEntry("chat-1/chat.json", json, listOf(
                "chat-1/media/photo.png" to photo.absolutePath,
                "chat-1/media/missing.png" to File(root, "missing.png").absolutePath
            )))
        ) { path -> File(path).takeIf { it.isFile } }

        val storage = File(root, "storage").apply { mkdirs() }
        val chats = ChatArchiveExtractor.extract(bundle.toByteArray(), storage, ChatArchiveLimits(minFreeBytes = 0)) { it }
        assertEquals(listOf(json.decodeToString()), chats)
        assertContentEquals(photo.readBytes(), File(storage, "chats/chat-1/media/photo.png").readBytes())
        assertFalse(File(storage, "chats/chat-1/media/missing.png").exists())
    }

    @Test fun bundleIsBuiltOneChatAtATimeAndLeavesOutputOpen() {
        val consumed = mutableListOf<String>()
        var closed = false
        val sink = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        val chats = sequence {
            listOf("a", "b").forEach { id ->
                consumed += id
                assertEquals(if (id == "a") 1 else 2, consumed.size, "Chats must be requested as they are written")
                yield(ChatBundleEntry("$id/chat.json", "{}".encodeToByteArray(), emptyList()))
            }
        }
        ChatArchiveWriter.writeBundle(sink, chats) { null }
        assertEquals(listOf("a", "b"), consumed)
        assertFalse(closed, "The caller owns the destination so it can sync before closing")
        assertTrue(sink.size() > 0)
    }

    @Test fun failedChatStopsTheExportWithoutReplacingTheEarlierFile() {
        val export = File(root, "backup.zip").apply { writeText("earlier") }
        val failure = assertFailsWith<IllegalStateException> {
            AtomicFileStorage.writeStream(export) { output ->
                ChatArchiveWriter.writeBundle(output, sequence<ChatBundleEntry> { error("Chat data is unavailable") }) { null }
            }
        }
        assertEquals("Chat data is unavailable", failure.message)
        assertEquals("earlier", export.readText())
        assertFalse(File(root, "backup.zip.tmp").exists())
    }

    private fun sparseFile(name: String, bytes: Long) = File(root, name).apply {
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    @Test fun oversizedPickIsRefusedWithoutReadingIt() {
        val huge = sparseFile("movie.mp4", 3L * 1024L * 1024L * 1024L)
        val picked = PlatformPickers.pickedFileFromDisk(huge, MAX_INLINE_ATTACHMENT_BYTES, 1L)
        assertTrue(picked.oversized)
        assertEquals("movie.mp4", picked.fileName)
        assertEquals(0, picked.bytes.size)
    }

    @Test fun ordinaryPickIsReadAndClassified() {
        val png = File(root, "shot").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) }
        val picked = PlatformPickers.pickedFileFromDisk(png, MAX_INLINE_ATTACHMENT_BYTES, 1L)
        assertFalse(picked.oversized)
        assertEquals("image/png", picked.mimeType)
        assertEquals("shot.png", picked.fileName)
        assertEquals(8, picked.bytes.size)
    }

    @Test fun droppedFilesUseTheAttachmentLimitAndReportOversizedOnes() {
        val small = File(root, "notes.txt").apply { writeText("hello") }
        val huge = sparseFile("huge.bin", MAX_INLINE_ATTACHMENT_BYTES + 1L)
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = listOf(small, huge)
        }
        val picked = PlatformPickers.extractTransferableFiles(transferable)
        assertEquals(listOf("notes.txt" to false, "huge.bin" to true), picked.map { it.fileName to it.oversized })
    }

    @Test fun pickLimitsDependOnPurpose() {
        assertEquals(MAX_INLINE_ATTACHMENT_BYTES, maxPickedFileBytes(emptyList()))
        assertEquals(MAX_INLINE_ATTACHMENT_BYTES, maxPickedFileBytes(listOf("*")))
        assertTrue(maxPickedFileBytes(listOf("json")) > MAX_INLINE_ATTACHMENT_BYTES)
    }

    @Test fun boundedStreamReadStopsAsSoonAsItExceedsTheLimit() {
        assertContentEquals(ByteArray(100), readAtMost(ByteArrayInputStream(ByteArray(100)), 100))
        var delivered = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { delivered += len; return len }
        }
        assertNull(readAtMost(endless, 200_000))
        assertTrue(delivered < 400_000, "Reading must stop near the limit, read $delivered")
    }

    @Test fun onlySmallTextIsPlacedInTheRequest() {
        assertEquals("plain notes", inlineAttachmentText("plain notes".encodeToByteArray()))
        assertNull(inlineAttachmentText(byteArrayOf(0x41, 0, 0x42)), "Binary data must not be treated as text")
        assertNull(inlineAttachmentText(ByteArray((MAX_INLINE_TEXT_ATTACHMENT_BYTES + 1).toInt()) { 0x41 }), "Oversized text must stay out of history")
    }

    @Test fun commandOutputKeepsItsFinalLines() {
        val command = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "1..30000 | ForEach-Object { 'line number ' + \$_ + ' padding padding padding' }; 'FINAL-ERROR'"
        } else {
            "i=0; while [ \$i -lt 30000 ]; do echo line number \$i padding padding padding; i=\$((i+1)); done; echo FINAL-ERROR"
        }
        val result = PlatformWorkspace.workspaceShellExec(root.absolutePath, command, 120, null, null).getOrThrow()
        assertTrue(result.startsWith("Exit Code: 0"), result.take(80))
        assertTrue(result.trimEnd().endsWith("FINAL-ERROR"), result.takeLast(80))
        assertTrue("characters omitted" in result)
        assertTrue(result.length < 8_200)
    }
}
