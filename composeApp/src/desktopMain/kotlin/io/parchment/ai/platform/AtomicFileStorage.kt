package io.parchment.ai.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.nio.file.StandardCopyOption

/**
 * Stages and flushes complete content beside its target before an atomic rename.
 * A failed commit preserves both the previous target and the recoverable .tmp.
 */
internal object AtomicFileStorage {
    fun readText(file: File): String = Files.readString(file.toPath(), Charsets.UTF_8)

    @Synchronized
    fun writeText(
        target: File,
        text: String,
        previousBackup: File? = null,
        commit: (Path, Path) -> Unit = ::commitAtomically
    ) {
        val parent = target.absoluteFile.parentFile
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

    @Synchronized
    fun copy(source: File, target: File) {
        val parent = target.absoluteFile.parentFile
        check(parent.exists() || parent.mkdirs()) { "Unable to create backup directory." }
        val staged = File(parent, target.name + ".tmp")
        FileOutputStream(staged).use { output ->
            FileInputStream(source).use { input -> input.copyTo(output, 8 * 1024) }
            output.flush()
            output.fd.sync()
        }
        commitAtomically(staged.toPath(), target.toPath())
    }

    private fun commitAtomically(source: Path, target: Path) {
        repeat(8) { attempt ->
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (error: AccessDeniedException) {
                if (attempt == 7) throw error
                // Windows readers/indexers can briefly hold handles without delete sharing.
                Thread.sleep(minOf(8L shl attempt, 256L))
            }
        }
    }
}
