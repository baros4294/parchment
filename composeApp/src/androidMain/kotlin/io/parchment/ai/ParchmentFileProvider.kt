package io.parchment.ai

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * A small ContentProvider, with no library dependency, for sharing local PDFs, videos,
 * audio, and images with Android's default external viewer apps
 * without triggering FileUriExposedException.
 */
class ParchmentFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.parchment.ai.fileprovider"

        fun getUriForFile(context: Context, file: File): Uri {
            val canonicalPath = file.canonicalPath
            val encodedPath = Uri.encode(canonicalPath)
            return Uri.parse("content://$AUTHORITY/local/$encodedPath")
        }

        fun resolveMimeType(file: File): String {
            val ext = file.extension.lowercase()
            val mapped = when (ext) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                "bmp" -> "image/bmp"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "txt", "md", "log", "json", "kt", "py", "js", "ts", "html", "css", "xml", "csv" -> "text/plain"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
            return mapped
        }
    }

    override fun onCreate(): Boolean = true

    private fun resolveValidatedFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "local") {
            throw FileNotFoundException("Invalid URI path: $uri")
        }
        val decodedPath = Uri.decode(segments.drop(1).joinToString("/"))
        val target = File(decodedPath).canonicalFile

        val allowedRoots = listOfNotNull(
            ctx.filesDir?.canonicalFile,
            ctx.noBackupFilesDir?.canonicalFile,
            ctx.cacheDir?.canonicalFile,
            ctx.externalCacheDir?.canonicalFile
        )
        val isAllowed = allowedRoots.any { root ->
            target.path.startsWith(root.path)
        }
        if (!isAllowed || !target.exists() || !target.isFile) {
            throw FileNotFoundException("Access denied or file not found: $uri")
        }
        return target
    }

    override fun getType(uri: Uri): String? = runCatching {
        val file = resolveValidatedFile(uri)
        resolveMimeType(file)
    }.getOrNull()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = runCatching {
        val file = resolveValidatedFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> values[i] = file.name
                OpenableColumns.SIZE -> values[i] = file.length()
            }
        }
        MatrixCursor(cols, 1).apply { addRow(values) }
    }.getOrNull()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveValidatedFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
