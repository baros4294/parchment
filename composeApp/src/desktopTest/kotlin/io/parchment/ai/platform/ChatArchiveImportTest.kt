package io.parchment.ai.platform

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.model.isWebLink
import io.parchment.ai.model.portableImportedMediaPath
import io.parchment.ai.model.withImportedPermissions
import io.parchment.ai.protocol.CardAndPromptCodec
import io.parchment.ai.ui.decodeChatSessionsFromPickedFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.experimental.xor
import kotlin.random.Random
import kotlin.test.*

class ChatArchiveImportTest {
    private val root: File = Files.createTempDirectory("parchment-archive-test-").toFile()
    private val chatJson = """{"id":"chat-1"}""".encodeToByteArray()
    private val permissive = ChatArchiveLimits(minFreeBytes = 0)

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun media(name: String = "photo.png") = File(root, "chats/chat-1/media/$name")

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun extract(bytes: ByteArray, limits: ChatArchiveLimits = permissive, decode: (List<String>) -> List<String> = { it }) =
        ChatArchiveExtractor.extract(bytes, root, limits, decode)

    private fun assertRejected(bytes: ByteArray, expected: String, limits: ChatArchiveLimits = permissive) {
        val error = assertFailsWith<ChatArchiveException> { extract(bytes, limits) }
        assertTrue(expected in error.message.orEmpty(), error.message)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".chat-import-") }, "Staging must be removed")
    }

    @Test fun validBundleCommitsMediaAndReturnsChats() {
        val image = byteArrayOf(1, 2, 3)
        assertEquals(listOf(chatJson.decodeToString()), extract(zip("chat-1/chat.json" to chatJson, "chat-1/media/photo.png" to image)))
        assertContentEquals(image, media().readBytes())
    }

    @Test fun damagedArchiveLeavesNoPartialMedia() {
        val noise = Random(7).nextBytes(64_000)
        val archive = zip("chat-1/media/first.png" to noise, "chat-1/media/second.png" to noise.reversedArray(), "chat-1/chat.json" to chatJson)
        val index = archive.size * 3 / 4
        archive[index] = archive[index] xor 0x55
        assertRejected(archive, "damaged")
        assertFalse(media("first.png").exists(), "An earlier valid entry must not be committed")
    }

    @Test fun truncatedArchiveIsRejected() {
        val archive = zip("chat-1/chat.json" to chatJson, "chat-1/media/photo.png" to byteArrayOf(1))
        assertRejected(archive.copyOf(archive.size - 30), "damaged")
        assertRejected(byteArrayOf(0x50, 0x4B, 3, 4), "damaged")
    }

    @Test fun oversizedEntriesFailInsteadOfTruncating() {
        val archive = zip("chat-1/chat.json" to chatJson, "chat-1/media/photo.png" to ByteArray(4_096))
        assertRejected(archive, "media file", permissive.copy(maxMediaBytes = 1_024))
        assertFalse(media().exists())
        assertRejected(zip("chat-1/chat.json" to ByteArray(4_096) { 'a'.code.toByte() }), "chat in the archive", permissive.copy(maxChatJsonBytes = 1_024))
    }

    @Test fun limitsBoundEntryCountAndIgnoredDecompression() {
        assertRejected(zip("a.json" to chatJson, "b.json" to chatJson, "c.json" to chatJson), "too many", permissive.copy(maxEntries = 2))
        assertRejected(zip("chat.json" to chatJson, "bomb.bin" to ByteArray(64_000)), "too large", permissive.copy(maxTotalBytes = 1_024))
    }

    @Test fun unsafePathsRejectTheWholeArchive() {
        assertRejected(zip("chat-1/media/ok.png" to byteArrayOf(1), "../../evil/media/x.png" to byteArrayOf(2), "chat.json" to chatJson), "unsafe")
        assertFalse(media("ok.png").exists())
        assertRejected(zip("chat.json" to chatJson, "chat-1/media/NUL.txt" to byteArrayOf(1)), "unsafe")
        assertRejected(zip("chat.json" to chatJson, "chat-1/media/evil.exe." to byteArrayOf(1)), "unsafe")
    }

    @Test fun namesThatCollideAfterSanitizingAreRejected() {
        assertRejected(zip("chat.json" to chatJson, "chat-1/media/a b.png" to byteArrayOf(1), "chat-1/media/a_b.png" to byteArrayOf(2)), "conflicting")
    }

    @Test fun existingMediaIsNeverReplaced() {
        media().parentFile.mkdirs()
        media().writeBytes(byteArrayOf(9, 9, 9))
        val conflicting = zip("chat.json" to chatJson, "chat-1/media/new.png" to byteArrayOf(4), "chat-1/media/photo.png" to byteArrayOf(1, 2, 3))
        assertRejected(conflicting, "different existing")
        assertContentEquals(byteArrayOf(9, 9, 9), media().readBytes())
        assertFalse(media("new.png").exists(), "A rejected archive must not commit its other files")

        val identical = zip("chat.json" to chatJson, "chat-1/media/new.png" to byteArrayOf(4), "chat-1/media/photo.png" to byteArrayOf(9, 9, 9))
        assertEquals(1, extract(identical).size)
        assertContentEquals(byteArrayOf(4), media("new.png").readBytes())
    }

    @Test fun rejectedChatsCommitNoMedia() {
        val archive = zip("chat.json" to chatJson, "chat-1/media/photo.png" to byteArrayOf(1))
        assertFailsWith<IllegalStateException> { extract(archive) { error("No valid chats") } }
        assertFalse(media().exists())
    }

    @Test fun staleStagingFromAnInterruptedImportIsRemoved() {
        val stale = File(root, ".chat-import-interrupted").apply { mkdirs() }
        File(stale, "0").writeBytes(byteArrayOf(1))
        extract(zip("chat.json" to chatJson))
        assertFalse(stale.exists())
    }

    /** Behaves like a network or document provider: small reads, no seeking, no way to know the length. */
    private class TrickleStream(private val bytes: ByteArray) : InputStream() {
        private var position = 0
        var closed = false
            private set

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(len, 17, bytes.size - position)
            bytes.copyInto(b, off, position, position + count)
            position += count
            return count
        }

        override fun close() { closed = true }
    }

    private fun importStream(name: String, bytes: ByteArray, limits: ChatArchiveLimits = permissive) =
        ChatArchiveExtractor.importChatFile(name, TrickleStream(bytes), root, limits) { it }

    @Test fun streamedBundleCommitsItsMedia() {
        val image = Random(3).nextBytes(150_000)
        val stream = TrickleStream(zip("chat-1/chat.json" to chatJson, "chat-1/media/photo.png" to image))
        val chats = ChatArchiveExtractor.importChatFile("export.chat.zip", stream, root, permissive) { it }
        assertEquals(listOf(chatJson.decodeToString()), chats)
        assertContentEquals(image, media().readBytes())
        assertTrue(stream.closed, "The selected file must be released")
    }

    @Test fun archiveCutExactlyAtAnEntryBoundaryIsRejected() {
        val archive = zip("chat-1/chat.json" to chatJson, "chat-1/media/photo.png" to byteArrayOf(1, 2, 3))
        val centralDirectory = (0..archive.size - 4).first {
            archive[it] == 0x50.toByte() && archive[it + 1] == 0x4B.toByte() && archive[it + 2] == 1.toByte() && archive[it + 3] == 2.toByte()
        }
        val error = assertFailsWith<ChatArchiveException> { importStream("cut.zip", archive.copyOf(centralDirectory)) }
        assertTrue("damaged" in error.message.orEmpty(), error.message)
        assertFalse(media().exists(), "A cut archive must commit nothing")
    }

    @Test fun zipIsRecognizedByContentAndJsonIsReadWhole() {
        val archive = zip("chat-1/chat.json" to chatJson, "chat-1/media/photo.png" to byteArrayOf(5))
        assertEquals(listOf(chatJson.decodeToString()), importStream("backup.dat", archive))
        assertContentEquals(byteArrayOf(5), media().readBytes())

        val longJson = """{"id":"${"x".repeat(200_000)}"}""".encodeToByteArray()
        assertEquals(listOf(longJson.decodeToString()), importStream("chat.json", longJson))
    }

    @Test fun oversizedStandaloneJsonAndTrailingDataAreBounded() {
        val tooLarge = assertFailsWith<ChatArchiveException> { importStream("chat.json", ByteArray(4_096) { 'a'.code.toByte() }, permissive.copy(maxTotalChatJsonBytes = 1_024)) }
        assertTrue("too large" in tooLarge.message.orEmpty(), tooLarge.message)

        val padded = zip("chat.json" to chatJson) + ByteArray(8_192)
        val trailing = assertFailsWith<ChatArchiveException> { importStream("padded.zip", padded, permissive.copy(maxTrailingBytes = 1_024)) }
        assertTrue("damaged" in trailing.message.orEmpty(), trailing.message)
    }

    @Test fun nonArchiveNamedZipIsRejected() {
        val error = assertFailsWith<ChatArchiveException> { importStream("notes.zip", "plain text".encodeToByteArray()) }
        assertTrue("damaged" in error.message.orEmpty(), error.message)
    }

    @Test fun importedMediaPathsStayInsideAppStorage() {
        mapOf(
            "chats/a/media/x.png" to "chats/a/media/x.png",
            "C:\\Users\\me\\AppData\\Local\\ParchmentAI\\chats\\a\\media\\x.png" to "chats/a/media/x.png",
            "/data/user/0/io.parchment.ai/files/media/x.png" to "media/x.png",
            "chats/a/media/my photo.png" to "chats/a/media/my_photo.png",
            "C:\\Users\\me\\.ssh\\id_rsa" to "",
            "/etc/passwd" to "",
            "\\\\attacker\\share\\a.png" to "",
            "//attacker/share/a.png" to "",
            "chats/../../secret/media/x" to "",
            "relative-file.txt" to "",
            "" to ""
        ).forEach { (raw, expected) -> assertEquals(expected, portableImportedMediaPath(raw), raw) }
    }

    @Test fun craftedChatCannotPullLocalFilesIntoAppStorage() {
        val secret = File(root, "secret-notes.txt").apply { writeText("private") }
        val crafted = ChatSession("crafted-chat", "Crafted", 1L, 1L, "profile", messages = listOf(
            MessageNode(id = "m", role = MessageRole.USER, variants = listOf(
                MessageVariant(id = "v", content = "x",
                    attachments = listOf(PromptAttachment(id = "a", fileName = "notes.txt", mimeType = "text/plain", localFilePath = secret.absolutePath)),
                    mediaItems = listOf(GeneratedMediaItem(id = "g", kind = MediaKind.IMAGE,
                        localFilePath = "\\\\attacker\\share\\a.png", remoteUrl = "C:\\Windows\\System32\\calc.exe")))
            ))
        ))
        val bytes = CardAndPromptCodec.prettyJson.encodeToString(ChatSession.serializer(), crafted).encodeToByteArray()
        val variant = decodeChatSessionsFromPickedFile("crafted.chat.json", bytes).single().messages.single().variants.single()
        assertEquals("", variant.attachments.single().localFilePath)
        assertEquals("", variant.mediaItems.single().localFilePath)
        assertEquals("", variant.mediaItems.single().remoteUrl)
        assertFalse(File(PlatformStorage.getChatsStorageDirectoryPath(), "crafted-chat/media/secret-notes.txt").exists())
        assertEquals("private", secret.readText())
    }

    @Test fun importedSettingsCannotNameNetworkFonts() {
        val theme = AppStateSnapshot(theme = ThemeConfig(customProseFontPath = "\\\\attacker\\f.ttf", customCodeFontPath = "C:\\Fonts\\code.ttf"))
            .withImportedPermissions().theme
        assertEquals("", theme.customProseFontPath)
        assertEquals("C:\\Fonts\\code.ttf", theme.customCodeFontPath)
    }

    @Test fun onlyWebLinksCountAsLinks() {
        listOf("https://example.com/a", "HTTP://example.com", "  https://example.com ").forEach { assertTrue(isWebLink(it), it) }
        listOf("C:\\Windows\\System32\\calc.exe", "file:///c:/x", "javascript:alert(1)", "//example.com", "").forEach { assertFalse(isWebLink(it), it) }
    }

    @Test fun desktopNeverLaunchesExecutableAttachments() {
        listOf("report.pdf.exe", "evil.exe.", "Run.BAT", "script.ps1", "a.lnk").forEach { assertTrue(isDesktopExecutableName(it), it) }
        listOf("photo.png", "movie.mp4", "notes", "report.pdf").forEach { assertFalse(isDesktopExecutableName(it), it) }
    }

    @Test fun oversizedPngChunkLengthEndsParsingCleanly() {
        val png = ByteArray(8) + byteArrayOf(0x7F, -1, -1, -16) + "tEXt".encodeToByteArray() + ByteArray(8)
        assertEquals(emptyMap(), CardAndPromptCodec.extractPngTextChunks(png))
    }
}
