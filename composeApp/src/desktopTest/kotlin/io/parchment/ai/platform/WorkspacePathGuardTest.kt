package io.parchment.ai.platform

import java.nio.file.Files
import kotlin.test.*

class WorkspacePathGuardTest {
    @Test fun existingAndMissingTargetsStayInsideRoot() {
        val root = Files.createTempDirectory("parchment-path-guard-")
        try {
            assertEquals(root.toRealPath().resolve("new/note.md"), WorkspacePathGuard.resolve(root.toString(), "new/note.md"))
            assertFails { WorkspacePathGuard.resolve(root.toString(), "../escape") }
            assertFails { WorkspacePathGuard.resolve(root.toString(), "/absolute") }
            assertFails { WorkspacePathGuard.resolve(root.toString(), "C:drive-relative") }
            assertFails { WorkspacePathGuard.resolve(root.toString(), "\\\\server\\share") }
            assertFails { WorkspacePathGuard.resolve(root.toString(), "invalid\u0000path") }
        } finally { Files.delete(root) }
    }

    @Test fun junctionEscapesAndRecursiveTraversalAreDenied() {
        val root = Files.createTempDirectory("parchment-junction-guard-")
        val external = Files.createTempDirectory("parchment-external-guard-")
        val nested = Files.createDirectory(root.resolve("nested"))
        val link = nested.resolve("linked")
        val safe = nested.resolve("safe.txt")
        val secret = external.resolve("external.txt")
        try {
            Files.writeString(safe, "safe marker")
            Files.writeString(secret, "external marker")
            if (System.getProperty("os.name").startsWith("Windows", true)) {
                // Native PowerShell creates an owned test junction; no elevated symlink privilege.
                val script = "New-Item -ItemType Junction -Path '" + link.toString().replace("'", "''") + "' -Target '" + external.toString().replace("'", "''") + "' | Out-Null"
                val command = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), output)
            } else Files.createSymbolicLink(link, external)
            assertFails { WorkspacePathGuard.resolve(root.toString(), "nested/linked/new.md") }
            assertFalse(WorkspacePathGuard.canTraverse(root.toRealPath(), link))
            assertFails { WorkspacePathGuard.requireUnlinkedTree(root.toRealPath(), root.toFile()) }
            assertTrue(PlatformBridge.workspaceRead(root.toString(), "nested/linked/external.txt").isFailure)
            assertTrue(PlatformBridge.workspaceWrite(root.toString(), "nested/linked/new.txt", "escape").isFailure)
            assertTrue(PlatformBridge.workspaceEdit(root.toString(), "nested/linked/external.txt", "external", "changed").isFailure)
            assertTrue(PlatformBridge.workspaceMultiEdit(root.toString(), "nested/linked/external.txt", listOf(Triple("external", "changed", false))).isFailure)
            assertTrue(PlatformBridge.workspaceMove(root.toString(), "nested/safe.txt", "nested/linked/moved.txt").isFailure)
            assertTrue(PlatformBridge.workspaceDelete(root.toString(), "nested").isFailure)
            assertEquals("safe marker", Files.readString(safe))
            assertEquals("external marker", Files.readString(secret))
            assertFalse(Files.exists(external.resolve("new.txt")))
            assertFalse(Files.exists(external.resolve("moved.txt")))
            assertFalse("external.txt" in PlatformBridge.workspaceList(root.toString(), "", true).getOrThrow())
            assertFalse("external.txt" in PlatformBridge.workspaceGlob(root.toString(), "**/*.txt").getOrThrow())
            val search = PlatformBridge.workspaceSearch(root.toString(), "external marker").getOrThrow()
            assertFalse("external.txt" in search)
            assertContains(search, "No matches found")
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(safe)
            Files.deleteIfExists(secret)
            Files.delete(nested)
            Files.delete(root)
            Files.delete(external)
        }
    }
}
