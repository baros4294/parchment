package io.parchment.ai.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.nio.file.StandardCopyOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Stages and flushes complete content beside its target before an atomic rename.
 * A failed commit preserves both the previous target and the recoverable .tmp.
 */
internal object AtomicFileStorage {
    // Close the shared NIO handle before UTF-8 decoding; buffered readers can starve Windows replacement.
    fun readText(file: File): String = Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8)

    @Synchronized
    fun writeText(
        target: File,
        text: String,
        previousBackup: File? = null,
        commit: (Path, Path) -> Unit = ::commitAtomically
    ) {
        val parent = checkNotNull(target.absoluteFile.parentFile) { "Storage target has no parent directory." }
        check(parent.exists() || parent.mkdirs()) { "Unable to create storage directory." }
        val staged = File(parent, target.name + ".tmp")
        FileOutputStream(staged).use { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            writer.write(text)
            writer.flush()
            stream.fd.sync()
        }
        if (previousBackup != null && target.isFile) {
            copy(target, previousBackup)
        }
        commit(staged.toPath(), target.toPath())
    }

    fun writeBytes(target: File, bytes: ByteArray, commit: (Path, Path) -> Unit = ::commitExport) =
        writeStream(target, commit) { it.write(bytes) }

    /**
     * Replaces a user-chosen export only after the new file is complete; a failure leaves the old one.
     * Deliberately unsynchronized: a long export must not block app state saves.
     */
    fun writeStream(target: File, commit: (Path, Path) -> Unit = ::commitExport, write: (OutputStream) -> Unit) {
        val parent = checkNotNull(target.absoluteFile.parentFile) { "Export target has no parent directory." }
        val staged = File(parent, target.name + ".tmp")
        try {
            FileOutputStream(staged).use { stream ->
                write(stream)
                stream.flush()
                stream.fd.sync()
            }
            commit(staged.toPath(), target.toPath())
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    // Exports can target network or removable volumes where atomic rename is unavailable.
    private fun commitExport(source: Path, target: Path) {
        try {
            commitAtomically(source, target)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun copy(source: File, target: File) {
        val parent = checkNotNull(target.absoluteFile.parentFile) { "Backup target has no parent directory." }
        check(parent.exists() || parent.mkdirs()) { "Unable to create backup directory." }
        val staged = File(parent, target.name + ".tmp")
        FileOutputStream(staged).use { output ->
            FileInputStream(source).use { input -> input.copyTo(output, 8 * 1024) }
            output.flush()
            output.fd.sync()
        }
        commitAtomically(staged.toPath(), target.toPath())
    }

    // About 5 seconds in total. Saves run on the background writer, so a slow machine with an antivirus
    // scanner holding the file can wait this long without the interface noticing.
    private const val MAX_REPLACE_ATTEMPTS = 24

    private fun commitAtomically(source: Path, target: Path) {
        repeat(MAX_REPLACE_ATTEMPTS) { attempt ->
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (error: AccessDeniedException) {
                if (attempt == MAX_REPLACE_ATTEMPTS - 1) throw error
                // Windows readers/indexers can briefly hold handles without delete sharing.
                Thread.sleep(minOf(8L shl attempt, 256L))
            }
        }
    }

    /** Deletes owned storage without following directory links into another tree. */
    fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
