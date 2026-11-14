package io.parchment.ai.platform

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Streams an update to an owned file and hands it to [installer] only after the complete download
 * matches its published size and checksum. [requireDigest] is for platforms with no signature check.
 */
internal class OtaStagedFile(
    private val target: File,
    private val requireDigest: Boolean,
    private val installer: (File) -> Result<String>
) : OtaStagedDownload {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val output: FileOutputStream
    private var written = 0L
    private var installerOwnsFile = false

    init {
        check(target.parentFile?.let { it.exists() || it.mkdirs() } == true) { "Unable to create the update folder." }
        output = FileOutputStream(target)
    }

    override fun write(buffer: ByteArray, count: Int) {
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        written += count
    }

    override fun finish(expectedBytes: Long, expectedSha256: String) {
        output.flush()
        output.fd.sync()
        output.close()
        check(written > 0L && written == expectedBytes) { "The download was incomplete, so the update was not installed." }
        if (expectedSha256.isBlank()) {
            check(!requireDigest) { "This release publishes no checksum, so the update was not installed." }
            return
        }
        val actual = digest.digest().joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
        check(actual == expectedSha256) { "The download failed its checksum, so the update was not installed." }
    }

    override fun install(): Result<String> {
        installerOwnsFile = true
        return installer(target).onFailure { installerOwnsFile = false }
    }

    override fun discard() {
        runCatching { output.close() }
        if (!installerOwnsFile) target.delete()
    }
}
