package io.parchment.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.ui.ParchmentApp
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var pendingImportDeferred: CompletableDeferred<PickedBinaryFile?>? = null
    private var pendingExportBytes: ByteArray? = null
    private var pendingExportDeferred: CompletableDeferred<String?>? = null

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformBridge.initAndroid(applicationContext, this)

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val deferred = pendingImportDeferred
            pendingImportDeferred = null
            if (uri == null || deferred == null) {
                deferred?.complete(null)
                return@registerForActivityResult
            }
            val result = runCatching {
                val fileName = queryDisplayName(uri) ?: "imported-file"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read selected file")
                PickedBinaryFile(fileName, bytes)
            }.getOrNull()
            deferred.complete(result)
        }

        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val deferred = pendingExportDeferred
            val bytes = pendingExportBytes
            pendingExportDeferred = null
            pendingExportBytes = null
            if (uri == null || bytes == null || deferred == null) {
                deferred?.complete(null)
                return@registerForActivityResult
            }
            val pathResult = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }
                uri.toString()
            }.getOrNull()
            deferred.complete(pathResult)
        }

        setContent {
            ParchmentApp()
        }
    }

    suspend fun requestImportFile(mimeTypes: Array<String>): PickedBinaryFile? {
        val deferred = CompletableDeferred<PickedBinaryFile?>()
        pendingImportDeferred = deferred
        runCatching {
            openDocumentLauncher.launch(mimeTypes)
        }.onFailure {
            deferred.complete(null)
        }
        return deferred.await()
    }

    suspend fun requestExportFile(defaultFileName: String, bytes: ByteArray): String? {
        val deferred = CompletableDeferred<String?>()
        pendingExportBytes = bytes
        pendingExportDeferred = deferred
        runCatching {
            createDocumentLauncher.launch(defaultFileName)
        }.onFailure {
            deferred.complete(null)
        }
        return deferred.await()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
