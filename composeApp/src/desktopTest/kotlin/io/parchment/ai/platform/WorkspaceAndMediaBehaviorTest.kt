package io.parchment.ai.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Pins the observable behavior of workspace edits and media storage through their public API. */
class WorkspaceAndMediaBehaviorTest {
    private val root: File = Files.createTempDirectory("parchment-workspace-behavior-").toFile()
    private val workspace = root.absolutePath

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun file(path: String) = File(root, path)

    @Test fun writeCreatesParentsAndReportsSize() {
        val result = PlatformBridge.workspaceWrite(workspace, "docs/notes.txt", "one\ntwo").getOrThrow()
        assertEquals("Successfully wrote 7 characters (2 lines) to 'docs/notes.txt'.", result)
        assertEquals("one\ntwo", file("docs/notes.txt").readText())
    }

    @Test fun appendAddsToExistingContent() {
        PlatformBridge.workspaceWrite(workspace, "log.txt", "a").getOrThrow()
        val result = PlatformBridge.workspaceAppend(workspace, "log.txt", "b").getOrThrow()
        assertEquals("Successfully appended 1 characters (1 lines) to 'log.txt'.", result)
        assertEquals("ab", file("log.txt").readText())
    }

    @Test fun editKeepsWindowsLineEndings() {
        file("crlf.txt").writeText("one\r\ntwo\r\n")
        val result = PlatformBridge.workspaceEdit(workspace, "crlf.txt", "two", "2", false).getOrThrow()
        assertEquals("one\r\n2\r\n", file("crlf.txt").readText())
        assertTrue(result.startsWith("Edited 'crlf.txt'"), result)
    }

    @Test fun editReportsAMissingFile() {
        val failure = PlatformBridge.workspaceEdit(workspace, "absent.txt", "a", "b", false).exceptionOrNull()
        assertEquals("File not found: absent.txt", failure?.message)
    }

    @Test fun multiEditAppliesEveryStepOrNone() {
        file("code.txt").writeText("alpha\nbeta\n")
        val applied = PlatformBridge.workspaceMultiEdit(workspace, "code.txt", listOf(
            Triple("alpha", "one", false), Triple("beta", "two", false)
        )).getOrThrow()
        assertTrue(applied.startsWith("Applied 2 edit(s) atomically"), applied)
        assertEquals("one\ntwo\n", file("code.txt").readText())

        val failure = PlatformBridge.workspaceMultiEdit(workspace, "code.txt", listOf(
            Triple("one", "first", false), Triple("no such text", "x", false)
        )).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("Edit #2 failed (rolled back all edits)"), failure?.message)
        assertEquals("one\ntwo\n", file("code.txt").readText(), "A failed step must leave the file unchanged")
    }

    @Test fun moveCreatesParentsAndRefusesTheRoot() {
        file("a.txt").writeText("x")
        assertEquals("Moved 'a.txt' -> 'sub/b.txt'.", PlatformBridge.workspaceMove(workspace, "a.txt", "sub/b.txt").getOrThrow())
        assertFalse(file("a.txt").exists())
        assertEquals("x", file("sub/b.txt").readText())
        assertEquals("Cannot move or replace the workspace root.", PlatformBridge.workspaceMove(workspace, "sub/b.txt", ".").exceptionOrNull()?.message)
        assertTrue(PlatformBridge.workspaceMove(workspace, "gone.txt", "c.txt").isFailure)
        assertFalse(file("c.txt").exists())
    }

    @Test fun deleteRemovesTreesAndRefusesTheRoot() {
        file("dir/inner.txt").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals("Deleted 'dir'.", PlatformBridge.workspaceDelete(workspace, "dir").getOrThrow())
        assertFalse(file("dir").exists())
        assertEquals("Cannot delete workspace root directory.", PlatformBridge.workspaceDelete(workspace, ".").exceptionOrNull()?.message)
        assertEquals("Path does not exist: gone", PlatformBridge.workspaceDelete(workspace, "gone").exceptionOrNull()?.message)
    }

    @Test fun workspacePathsCannotEscapeTheRoot() {
        assertTrue(PlatformBridge.workspaceWrite(workspace, "../escaped.txt", "x").isFailure)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test fun ignoredDirectoriesAreCaseInsensitive() {
        listOf(".git", "BUILD", ".gradle", "Node_Modules", ".idea", ".kotlin").forEach { assertTrue(WorkspacePathGuard.isIgnoredDirectory(it), it) }
        assertFalse(WorkspacePathGuard.isIgnoredDirectory("src"))
    }

    @Test fun savedMediaIsSanitizedAndFoundByPortablePath() {
        val saved = PlatformBridge.saveGeneratedMediaFile("my photo?.png", byteArrayOf(1, 2, 3), sessionId = "behavior-chat")
        assertTrue(saved.replace('\\', '/').endsWith("/media/my_photo_.png"), saved)
        assertContentEquals(byteArrayOf(1, 2, 3), PlatformBridge.readBinaryFileBytes(saved))
        assertEquals(3L, PlatformBridge.localFileSizeBytes(saved))
        assertContentEquals(byteArrayOf(1, 2, 3), PlatformBridge.readBinaryFileBytes("D:/other/device/chats/behavior-chat/media/my_photo_.png"))
        assertNull(PlatformBridge.readBinaryFileBytes("chats/behavior-chat/media/absent.png"))
    }

    @Test fun legacySharedMediaMovesIntoItsChatFolder() {
        val legacy = PlatformBridge.saveGeneratedMediaFile("shared.png", byteArrayOf(7), sessionId = "")
        val moved = PlatformBridge.relocateLegacyMediaToChatFolder(legacy, "behavior-move")
        assertNotEquals(legacy, moved)
        assertTrue(moved.replace('\\', '/').contains("behavior-move"), moved)
        assertFalse(File(legacy).exists(), "A file from the legacy shared folder is moved, not copied")
        assertContentEquals(byteArrayOf(7), File(moved).readBytes())
        assertEquals(moved, PlatformBridge.relocateLegacyMediaToChatFolder(moved, "behavior-move"), "Already in place")
    }

    @Test fun filesOutsideLegacyStorageAreCopiedAndLeftAlone() {
        val outside = file("outside.png").apply { writeBytes(byteArrayOf(9)) }
        val copied = PlatformBridge.relocateLegacyMediaToChatFolder(outside.absolutePath, "behavior-copy")
        assertNotEquals(outside.absolutePath, copied)
        assertTrue(outside.exists())
        assertContentEquals(byteArrayOf(9), File(copied).readBytes())
    }

    @Test fun blankOrMissingRelocationsReturnTheOriginalPath() {
        assertEquals("", PlatformBridge.relocateLegacyMediaToChatFolder("", "chat"))
        assertEquals("missing.png", PlatformBridge.relocateLegacyMediaToChatFolder("missing.png", "chat"))
        assertEquals("x.png", PlatformBridge.relocateLegacyMediaToChatFolder("x.png", ""))
    }
}
