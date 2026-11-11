package io.parchment.ai.platform

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFileStorageTest {
    @Test
    fun replacesExistingFileAndPreservesUtf8() = withStorage { root ->
        val target = File(root, "chat.json")
        target.writeText("previous content")
        val replacement = "{\"text\":\"café — 你好\",\"size\":123}"
        AtomicFileStorage.writeText(target, replacement)
        assertEquals(replacement, target.readText(Charsets.UTF_8))
        assertFalse(File(root, "chat.json.tmp").exists())
    }

    @Test
    fun failedCommitPreservesOldTargetAndRecoverableNewContent() = withStorage { root ->
        val target = File(root, "chat.json")
        target.writeText("previous")
        assertFailsWith<IOException> {
            AtomicFileStorage.writeText(target, "replacement", commit = { _, _ ->
                throw IOException("Simulated atomic replacement failure.")
            })
        }
        assertEquals("previous", target.readText())
        assertEquals("replacement", File(root, "chat.json.tmp").readText())
    }

    @Test
    fun backupContainsPreviousVersionAndNewTargetIsComplete() = withStorage { root ->
        val target = File(root, "settings.json")
        val backup = File(root, "backups/settings.prev.json")
        target.writeText("previous")
        AtomicFileStorage.writeText(target, "replacement", previousBackup = backup)
        assertEquals("previous", backup.readText())
        assertEquals("replacement", target.readText())
        assertFalse(File(backup.parentFile, backup.name + ".tmp").exists())
        AtomicFileStorage.copy(target, backup)
        assertEquals("replacement", backup.readText())
    }

    @Test
    fun readersOnlyObserveCompleteVersionsDuringReplacement() = withStorage { root ->
        val target = File(root, "chat.json")
        val first = "first:".repeat(5_000)
        val second = "second:".repeat(5_000)
        target.writeText(first)
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                while (running.get()) {
                    val contents = AtomicFileStorage.readText(target)
                    check(contents == first || contents == second) { "Reader observed a partial snapshot." }
                    // Real readers pause between reads. A reader that never lets go of the file can starve Windows'
                    // replace on a busy machine (failed twice under load, 2026-11-11), which is not what this checks.
                    Thread.sleep(1)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            repeat(20) { AtomicFileStorage.writeText(target, if (it % 2 == 0) second else first) }
        } finally {
            running.set(false)
            reader.join(5_000)
        }
        assertFalse(reader.isAlive)
        assertEquals(null, failure.get())
        assertTrue(target.readText() == first || target.readText() == second)
    }

    @Test
    fun transientLegacyReaderLockDoesNotForceTruncateInPlace() = withStorage { root ->
        val target = File(root, "chat.json")
        target.writeText("previous")
        val legacyReader = java.io.FileInputStream(target)
        val closer = Thread {
            Thread.sleep(100)
            legacyReader.close()
        }.apply { isDaemon = true; start() }
        try {
            AtomicFileStorage.writeText(target, "replacement")
            assertEquals("replacement", AtomicFileStorage.readText(target))
        } finally {
            legacyReader.close()
            closer.join(5_000)
        }
        assertFalse(closer.isAlive)
    }

    private fun withStorage(block: (File) -> Unit) {
        val root = Files.createTempDirectory("parchment-atomic-test-").toFile()
        try {
            block(root)
        } finally {
            val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
            check(root.canonicalFile.toPath().startsWith(temporaryRoot) && root.name.startsWith("parchment-atomic-test-"))
            root.deleteRecursively()
        }
    }
}
