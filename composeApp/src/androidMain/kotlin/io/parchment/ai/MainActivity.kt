package io.parchment.ai

import io.parchment.ai.runtime.AppForeground
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
import androidx.lifecycle.lifecycleScope
import io.parchment.ai.platform.AndroidPlatformHost
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformPickers
import io.parchment.ai.platform.PlatformRuntime
import io.parchment.ai.ui.ParchmentApp
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var pendingImportDeferred: CompletableDeferred<Uri?>? = null
    private var pendingImportUrisDeferred: CompletableDeferred<List<Uri>>? = null
    private var pendingExportDeferred: CompletableDeferred<Uri?>? = null
    private var pendingWorkspaceDirDeferred: CompletableDeferred<String?>? = null
    private var pendingWorkspaceInitialUri: Uri? = null
    private var pendingVoiceCallback: ((String) -> Unit)? = null
    private var pendingVoiceErrorCallback: ((String) -> Unit)? = null
    private var activeSpeechRecognizer: SpeechRecognizer? = null

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var openMultipleDocumentsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>
    private lateinit var pickWorkspaceTreeLauncher: ActivityResultLauncher<Uri?>
    private lateinit var manageAllFilesLauncher: ActivityResultLauncher<Intent>
    private lateinit var voiceRecognizerLauncher: ActivityResultLauncher<Intent>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var notificationPermissionRequested = false

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidPlatformHost.initAndroid(applicationContext, this)
        handleIncomingShareIntent(intent)

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val deferred = pendingImportDeferred
            pendingImportDeferred = null
            deferred?.complete(uri)
        }

        openMultipleDocumentsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            val deferred = pendingImportUrisDeferred
            pendingImportUrisDeferred = null
            deferred?.complete(uris)
        }

        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val deferred = pendingExportDeferred
            pendingExportDeferred = null
            deferred?.complete(uri)
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
            val resolvedPath = PlatformPickers.resolveTreeUriToFilePath(treeUri)
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

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            LongDecodeForegroundService.refreshNotificationNotice(applicationContext)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!PlatformRuntime.handleSystemBackPressed()) {
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
                    view.setOnReceiveContentListener(PlatformPickers.RICH_CONTENT_MIME_TYPES) { _, payload ->
                        PlatformPickers.handleReceiveContentPayload(payload)
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
                        PlatformPickers.wrapInputConnection(baseIc, outAttrs)
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
                if (uri != null) deliverSharedUris(listOf(uri), mime)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                deliverSharedUris(uris.orEmpty(), mime)
            }
        }
    }

    private fun deliverSharedUris(uris: List<Uri>, mime: String) {
        if (uris.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            PlatformPickers.deliverRichContentFiles(uris.mapNotNull { PlatformPickers.readUriAsPickedFile(it, mime) })
        }
    }

    override fun onPause() {
        PlatformRuntime.flushPendingStateNow()
        super.onPause()
    }

    override fun onStop() {
        AppForeground.markLeft()
        PlatformRuntime.flushPendingStateNow()
        super.onStop()
    }

    /** Reads off the main thread, so a large selection cannot freeze the interface. */
    suspend fun requestImportFile(mimeTypes: Array<String>, maxBytes: Long): PickedBinaryFile? {
        val deferred = CompletableDeferred<Uri?>()
        pendingImportDeferred = deferred
        runCatching {
            openDocumentLauncher.launch(mimeTypes)
        }.onFailure {
            deferred.complete(null)
        }
        val uri = deferred.await() ?: return null
        return withContext(Dispatchers.IO) { PlatformPickers.readUriAsPickedFile(uri, maxBytes = maxBytes) }
    }

    /** Returns the chosen documents without reading them, so callers can stream them off the main thread. */
    suspend fun requestImportDocumentUris(mimeTypes: Array<String>): List<Uri> {
        val deferred = CompletableDeferred<List<Uri>>()
        pendingImportUrisDeferred = deferred
        runCatching {
            openMultipleDocumentsLauncher.launch(mimeTypes)
        }.onFailure {
            pendingImportUrisDeferred = null
            deferred.complete(emptyList())
        }
        return deferred.await()
    }

    suspend fun requestExportFile(defaultFileName: String, bytes: ByteArray): String? =
        runCatching { requestExportStream(defaultFileName) { it.write(bytes) } }.getOrNull()

    /** Writes off the main thread. A failed export removes the partial document it created. */
    suspend fun requestExportStream(defaultFileName: String, write: (OutputStream) -> Unit): String? {
        val deferred = CompletableDeferred<Uri?>()
        pendingExportDeferred = deferred
        runCatching {
            createDocumentLauncher.launch(defaultFileName)
        }.onFailure {
            deferred.complete(null)
        }
        val uri = deferred.await() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Unable to open the export destination." }.use { output ->
                    write(output)
                    output.flush()
                }
                uri.toString()
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                throw error
            }
        }
    }

    suspend fun requestPickWorkspaceDirectory(currentPath: String): String? {
        val deferred = CompletableDeferred<String?>()
        pendingWorkspaceDirDeferred = deferred
        val initialUri = PlatformPickers.buildInitialDocumentTreeUri(currentPath)
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

    /**
     * Offers notification permission once while visible. Denial does not prevent a foreground service.
     */
    override fun onResume() {
        AppForeground.markResumed()
        super.onResume()
        LongDecodeForegroundService.refreshNotificationNotice(applicationContext)
    }

    fun requestNotificationPermission() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@runOnUiThread
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@runOnUiThread
            notificationPermissionRequested = true
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { LongDecodeForegroundService.reportFailure(it) }
        }
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
