package io.parchment.ai.platform

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotFileStorageTest {
    private val json = Json { encodeDefaults = true }
    private fun settings(name: String) = json.encodeToString(AppStateSnapshot.serializer(), AppStateSnapshot(
        profiles = listOf(ProviderProfile(id = "profile", name = name, protocol = ProviderProtocol.OPENAI_RESPONSES,
            apiKey = "test-only-key")), activeProfileId = "profile", activeSessionId = "chat"
    ))
    private fun chat(id: String, text: String) = json.encodeToString(ChatSession.serializer(), ChatSession(
        id = id, title = id, createdAt = 1, updatedAt = 2, profileId = "profile",
        messages = listOf(MessageNode(id = "message", role = MessageRole.ASSISTANT,
            variants = listOf(MessageVariant(id = "variant", content = text))))
    ))

    @Test
    fun corruptSettingsPrimaryRecoversWithoutDestroyingValidBackup() = withStorage { root, storage ->
        val first = settings("first")
        storage.saveSettings(first)
        storage.saveSettings(settings("second"))
        val primary = File(root, "parchment-settings.json")
        val corrupt = "{\"profiles\":["
        primary.writeText(corrupt)
        assertEquals(first, storage.loadSettings())
        storage.saveSettings(first)
        assertEquals(first, primary.readText())
        assertEquals(first, File(root, "backups/parchment-settings.prev.json").readText())
        assertEquals(corrupt, File(root, "backups/parchment-settings.corrupt.json").readText())
    }

    @Test
    fun corruptChatPrimaryRecoversWithoutReplacingItsValidBackup() = withStorage { root, storage ->
        val first = chat("chat", "完整 history")
        storage.saveChat("chat", first)
        storage.saveChat("chat", chat("chat", "second"))
        val primary = File(root, "chats/chat/chat.json")
        primary.writeText("{broken")
        assertEquals(first, storage.loadChat("chat"))
        storage.saveChat("chat", first)
        assertEquals(first, primary.readText())
        assertEquals(first, File(primary.parentFile, "chat.prev.json").readText())
        assertEquals("{broken", File(primary.parentFile, "chat.corrupt.json").readText())
    }

    @Test
    fun failedLegacyMigrationRetainsSourceAndReadableHistory() = withStorage { root, storage ->
        val blocked = File(root, "chats/legacy/chat.json")
        check(blocked.mkdirs())
        File(blocked, "blocker").writeText("preserve")
        val legacy = File(root, "chats/legacy.json")
        val raw = chat("legacy", "preserved legacy content")
        legacy.writeText(raw)
        assertEquals(raw, storage.loadChat("legacy"))
        assertEquals(raw, legacy.readText())
        assertTrue(blocked.isDirectory)
        assertEquals("preserve", File(blocked, "blocker").readText())
        assertEquals(raw, File(blocked.parentFile, "chat.json.tmp").readText())
    }

    @Test
    fun newerCorruptLegacyFileCannotReplaceAValidChat() = withStorage { root, storage ->
        val valid = chat("chat", "valid history")
        storage.saveChat("chat", valid)
        val legacy = File(root, "chats/chat.json")
        legacy.writeText("{broken newer file")
        check(legacy.setLastModified(System.currentTimeMillis() + 60_000))
        assertEquals(valid, storage.loadChat("chat"))
        assertEquals(valid, File(root, "chats/chat/chat.json").readText())
        assertTrue(legacy.exists())
    }

    @Test
    fun successfulMigrationDeletesOnlyAfterACompleteDestinationExists() = withStorage { root, storage ->
        val legacy = File(root, "chats/legacy.json")
        check(legacy.parentFile.mkdirs())
        val raw = chat("legacy", "legacy content")
        legacy.writeText(raw)
        assertEquals(raw, storage.loadChat("legacy"))
        assertFalse(legacy.exists())
        assertEquals(raw, File(root, "chats/legacy/chat.json").readText())
    }

    @Test
    fun nativeWriteFailurePropagatesAndPreservesStagedContent() = withStorage { root, storage ->
        val target = File(root, "parchment-settings.json")
        check(target.mkdir())
        File(target, "blocker").writeText("preserve")
        val raw = settings("new")
        assertFailsWith<IOException> { storage.saveSettings(raw) }
        assertTrue(target.isDirectory)
        assertEquals(raw, File(root, "parchment-settings.json.tmp").readText())
    }

    @Test
    fun invalidAndMismatchedChatWritesCannotReplaceExistingHistory() = withStorage { root, storage ->
        val original = chat("chat", "original")
        storage.saveChat("chat", original)
        assertFailsWith<IllegalArgumentException> { storage.saveChat("chat", "{broken") }
        assertFailsWith<IllegalArgumentException> { storage.saveChat("chat", chat("different", "wrong ID")) }
        assertEquals(original, storage.loadChat("chat"))
    }

    @Test
    fun initialDefaultsCannotEraseConfiguredOrUnrecoverableSettings() = withStorage { root, storage ->
        val configured = settings("configured")
        storage.saveSettings(configured)
        val defaults = json.encodeToString(AppStateSnapshot.serializer(), AppStateSnapshot())
        assertFailsWith<IOException> { storage.saveSettings(defaults) }
        assertEquals(configured, storage.loadSettings())
        File(root, "parchment-settings.json").writeText("{broken")
        assertFailsWith<IOException> { storage.saveSettings(defaults) }
        assertEquals("{broken", File(root, "parchment-settings.json").readText())
    }

    @Test
    fun sanitizedImportedIdsRemainLoadableAndCannotCollide() = withStorage { _, storage ->
        val original = chat("imported:chat", "imported content")
        storage.saveChat("imported:chat", original)
        assertEquals(listOf(original), storage.loadAllChats())
        assertFailsWith<IOException> { storage.saveChat("imported/chat", chat("imported/chat", "collision")) }
        assertEquals(original, storage.loadChat("imported:chat"))
    }

    @Test
    fun dotIdentifiersCannotDeleteTheChatRoot() = withStorage { root, storage ->
        val original = chat("safe", "safe history")
        storage.saveChat("safe", original)
        assertFailsWith<IllegalArgumentException> { storage.deleteChat(".") }
        assertFailsWith<IllegalArgumentException> { storage.deleteChat("..") }
        assertEquals(original, File(root, "chats/safe/chat.json").readText())
    }

    private fun withStorage(block: (File, SnapshotFileStorage) -> Unit) {
        val root = Files.createTempDirectory("parchment-snapshot-test-").toFile()
        try {
            block(root, SnapshotFileStorage({ root }))
        } finally {
            val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
            check(root.canonicalFile.toPath().startsWith(temporaryRoot) && root.name.startsWith("parchment-snapshot-test-"))
            AtomicFileStorage.deleteTree(root.toPath())
        }
    }
}
