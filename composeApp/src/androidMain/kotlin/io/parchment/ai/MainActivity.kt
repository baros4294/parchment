package io.parchment.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.ui.ParchmentApp
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var pendingImportDeferred: CompletableDeferred<PickedBinaryFile?>? = null
    private var pendingMultiImportDeferred: CompletableDeferred<List<PickedBinaryFile>>? = null
    private var pendingExportBytes: ByteArray? = null
    private var pendingExportDeferred: CompletableDeferred<String?>? = null
    private var pendingBatchExportFiles: List<Pair<String, ByteArray>>? = null
    private var pendingBatchExportDeferred: CompletableDeferred<String?>? = null
    private var pendingWorkspaceDirDeferred: CompletableDeferred<String?>? = null
    private var pendingWorkspaceInitialUri: Uri? = null
    private var pendingVoiceCallback: ((String) -> Unit)? = null
    private var pendingVoiceErrorCallback: ((String) -> Unit)? = null
    private var activeSpeechRecognizer: SpeechRecognizer? = null

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var openMultipleDocumentsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>
    private lateinit var openDocumentTreeLauncher: ActivityResultLauncher<Uri?>
    private lateinit var pickWorkspaceTreeLauncher: ActivityResultLauncher<Uri?>
    private lateinit var manageAllFilesLauncher: ActivityResultLauncher<Intent>
    private lateinit var voiceRecognizerLauncher: ActivityResultLauncher<Intent>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformBridge.initAndroid(applicationContext, this)
        handleIncomingShareIntent(intent)

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val deferred = pendingImportDeferred
            pendingImportDeferred = null
            if (uri == null || deferred == null) {
                deferred?.complete(null)
                return@registerForActivityResult
            }
            val result = PlatformBridge.readUriAsPickedFile(uri)
            deferred.complete(result)
        }

        openMultipleDocumentsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            val deferred = pendingMultiImportDeferred
            pendingMultiImportDeferred = null
            if (uris.isEmpty() || deferred == null) {
                deferred?.complete(emptyList())
                return@registerForActivityResult
            }
            val results = uris.mapNotNull { PlatformBridge.readUriAsPickedFile(it) }
            deferred.complete(results)
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

        openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            val deferred = pendingBatchExportDeferred
            val files = pendingBatchExportFiles
            pendingBatchExportDeferred = null
            pendingBatchExportFiles = null
            if (treeUri == null || files.isNullOrEmpty() || deferred == null) {
                deferred?.complete(null)
                return@registerForActivityResult
            }
            val summary = runCatching {
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                var written = 0
                for ((fileName, bytes) in files) {
                    val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "chat-${written + 1}.json" }
                    val mime = if (safeName.endsWith(".json", ignoreCase = true)) "application/json" else "text/plain"
                    val childUri = DocumentsContract.createDocument(contentResolver, parentUri, mime, safeName)
                    if (childUri != null) {
                        contentResolver.openOutputStream(childUri)?.use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                        written++
                    }
                }
                "$written file(s) exported"
            }.getOrNull()
            deferred.complete(summary)
        }

        pickWorkspaceTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            val deferred = pendingWorkspaceDirDeferred
            pendingWorkspaceDirDeferred = null
            pendingWorkspaceInitialUri = null
            if (treeUri == null || deferred == null) {
                deferred?.complete(null)
                return@registerForActivityResult
            }
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            val resolvedPath = PlatformBridge.resolveTreeUriToFilePath(treeUri)
            deferred.complete(resolvedPath)
        }

        manageAllFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val initUri = pendingWorkspaceInitialUri
            runCatching {
                pickWorkspaceTreeLauncher.launch(initUri)
            }.onFailure {
                pendingWorkspaceDirDeferred?.complete(null)
                pendingWorkspaceDirDeferred = null
                pendingWorkspaceInitialUri = null
            }
        }

        voiceRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = pendingVoiceCallback
            val errCb = pendingVoiceErrorCallback
            pendingVoiceCallback = null
            pendingVoiceErrorCallback = null
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val topText = matches?.firstOrNull()?.trim().orEmpty()
                if (topText.isNotEmpty()) {
                    cb?.invoke(topText)
                } else {
                    errCb?.invoke("No speech detected.")
                }
            } else {
                errCb?.invoke("Voice input cancelled.")
            }
        }

        audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = pendingVoiceCallback
            val errCb = pendingVoiceErrorCallback
            if (granted && cb != null && errCb != null) {
                startVoiceRecognitionInternal(cb, errCb)
            } else {
                // Fall back to system RecognizerIntent dialog even if direct mic permission was declined
                if (cb != null && errCb != null) {
                    launchRecognizerDialogFallback(errCb)
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!PlatformBridge.handleSystemBackPressed()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        setContent {
            val view = LocalView.current
            DisposableEffect(view) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.setOnReceiveContentListener(PlatformBridge.RICH_CONTENT_MIME_TYPES) { _, payload ->
                        PlatformBridge.handleReceiveContentPayload(payload)
                    }
                }
                onDispose {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        view.setOnReceiveContentListener(null, null)
                    }
                }
            }

            // Intercept Android InputConnection creation so Gboard Emoji Kitchen, GIFs, Stickers,
            // and Android's floating Paste toolbar see RICH_CONTENT_MIME_TYPES and commit directly!
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    val wrappedRequest = PlatformTextInputMethodRequest { outAttrs ->
                        val baseIc = request.createInputConnection(outAttrs)
                        PlatformBridge.wrapInputConnection(baseIc, outAttrs)
                    }
                    nextHandler.startInputMethod(wrappedRequest)
                }
            ) {
                ParchmentApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val mime = intent.type.orEmpty()
        when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (uri != null) {
                    PlatformBridge.readUriAsPickedFile(uri, mime)?.let {
                        PlatformBridge.deliverRichContentFiles(listOf(it))
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                val picked = uris?.mapNotNull { PlatformBridge.readUriAsPickedFile(it, mime) }.orEmpty()
                if (picked.isNotEmpty()) {
                    PlatformBridge.deliverRichContentFiles(picked)
                }
            }
        }
    }

    override fun onPause() {
        PlatformBridge.flushPendingStateNow()
        super.onPause()
    }

    override fun onStop() {
        PlatformBridge.flushPendingStateNow()
        super.onStop()
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

    suspend fun requestImportMultipleFiles(mimeTypes: Array<String>): List<PickedBinaryFile> {
        val deferred = CompletableDeferred<List<PickedBinaryFile>>()
        pendingMultiImportDeferred = deferred
        runCatching {
            openMultipleDocumentsLauncher.launch(mimeTypes)
        }.onFailure {
            deferred.complete(emptyList())
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

    suspend fun requestExportMultipleFilesToTree(files: List<Pair<String, ByteArray>>): String? {
        if (files.isEmpty()) return null
        if (files.size == 1) {
            return requestExportFile(files.first().first, files.first().second)
        }
        val deferred = CompletableDeferred<String?>()
        pendingBatchExportFiles = files
        pendingBatchExportDeferred = deferred
        runCatching {
            openDocumentTreeLauncher.launch(null)
        }.onFailure {
            deferred.complete(null)
        }
        return deferred.await()
    }

    suspend fun requestPickWorkspaceDirectory(currentPath: String): String? {
        val deferred = CompletableDeferred<String?>()
        pendingWorkspaceDirDeferred = deferred
        val initialUri = PlatformBridge.buildInitialDocumentTreeUri(currentPath)
        pendingWorkspaceInitialUri = initialUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val launchedSettings = runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                manageAllFilesLauncher.launch(intent)
                true
            }.getOrElse {
                runCatching {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageAllFilesLauncher.launch(fallbackIntent)
                    true
                }.getOrDefault(false)
            }
            if (launchedSettings) {
                return deferred.await()
            }
        }
        runCatching {
            pickWorkspaceTreeLauncher.launch(initialUri)
        }.onFailure {
            pendingWorkspaceDirDeferred = null
            pendingWorkspaceInitialUri = null
            deferred.complete(null)
        }
        return deferred.await()
    }

    fun launchVoiceRecognizer(onResult: (String) -> Unit, onError: (String) -> Unit) {
        runOnUiThread {
            pendingVoiceCallback = onResult
            pendingVoiceErrorCallback = onError
            val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else true

            if (hasPerm) {
                startVoiceRecognitionInternal(onResult, onError)
            } else {
                runCatching {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }.onFailure {
                    launchRecognizerDialogFallback(onError)
                }
            }
        }
    }

    private fun startVoiceRecognitionInternal(onResult: (String) -> Unit, onError: (String) -> Unit) {
        runCatching {
            activeSpeechRecognizer?.destroy()
            activeSpeechRecognizer = null
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                activeSpeechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        runCatching { recognizer.destroy() }
                        if (activeSpeechRecognizer === recognizer) activeSpeechRecognizer = null
                        // If in-app SpeechRecognizer service failed to bind, try system dialog fallback
                        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            launchRecognizerDialogFallback(onError)
                        } else {
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network error."
                                else -> "Voice input ended (code $error)."
                            }
                            pendingVoiceCallback = null
                            pendingVoiceErrorCallback = null
                            onError(msg)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        runCatching { recognizer.destroy() }
                        if (activeSpeechRecognizer === recognizer) activeSpeechRecognizer = null
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val topText = matches?.firstOrNull()?.trim().orEmpty()
                        val cb = pendingVoiceCallback
                        val errCb = pendingVoiceErrorCallback
                        pendingVoiceCallback = null
                        pendingVoiceErrorCallback = null
                        if (topText.isNotEmpty()) {
                            cb?.invoke(topText)
                        } else {
                            errCb?.invoke("No speech detected.")
                        }
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                recognizer.startListening(intent)
            } else {
                launchRecognizerDialogFallback(onError)
            }
        }.onFailure {
            launchRecognizerDialogFallback(onError)
        }
    }

    private fun launchRecognizerDialogFallback(onError: (String) -> Unit) {
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Parchment…")
            }
            voiceRecognizerLauncher.launch(intent)
        }.onFailure { err ->
            pendingVoiceCallback = null
            pendingVoiceErrorCallback = null
            onError("Speech recognition unavailable on this device: ${err.message}")
        }
    }

    fun stopVoiceRecognizer() {
        runOnUiThread {
            runCatching {
                activeSpeechRecognizer?.stopListening()
                activeSpeechRecognizer?.destroy()
                activeSpeechRecognizer = null
            }
        }
    }
}
