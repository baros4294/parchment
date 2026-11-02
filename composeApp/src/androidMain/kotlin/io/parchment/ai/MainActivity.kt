package io.parchment.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
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
    private var pendingExportBytes: ByteArray? = null
    private var pendingExportDeferred: CompletableDeferred<String?>? = null
    private var pendingVoiceCallback: ((String) -> Unit)? = null
    private var pendingVoiceErrorCallback: ((String) -> Unit)? = null

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>
    private lateinit var voiceRecognizerLauncher: ActivityResultLauncher<Intent>

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

    fun launchVoiceRecognizer(onResult: (String) -> Unit, onError: (String) -> Unit) {
        pendingVoiceCallback = onResult
        pendingVoiceErrorCallback = onError
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Parchment…")
            }
            voiceRecognizerLauncher.launch(intent)
        }.onFailure { err ->
            pendingVoiceCallback = null
            pendingVoiceErrorCallback = null
            onError("Speech recognition unavailable: ${err.message}")
        }
    }
}
