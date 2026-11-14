package io.parchment.ai.platform

/** One downloaded installer, staged on disk until it is verified and handed to the platform. */
interface OtaStagedDownload {
    fun write(buffer: ByteArray, count: Int)

    /** Throws unless the complete download matches the published size and checksum. */
    fun finish(expectedBytes: Long, expectedSha256: String)

    fun install(): Result<String>

    /** Removes the staged file unless the platform installer now owns it. */
    fun discard()
}
