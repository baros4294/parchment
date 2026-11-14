package io.parchment.ai.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

actual object PlatformVoice {
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady: Boolean = false
    @Volatile
    private var pendingTtsFinishedCallback: (() -> Unit)? = null
    @Volatile
    private var pendingUtteranceToSpeak: String? = null

    internal fun ensureTtsInitialized(ctx: Context) {
        if (ttsEngine != null && ttsReady) return
        runCatching {
            ttsEngine = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    runCatching {
                        ttsEngine?.language = java.util.Locale.getDefault()
                    }
                    ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.endsWith("-last") == true) {
                                val cb = pendingTtsFinishedCallback
                                pendingTtsFinishedCallback = null
                                cb?.invoke()
                            }
                        }
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            val cb = pendingTtsFinishedCallback
                            pendingTtsFinishedCallback = null
                            cb?.invoke()
                        }
                    })
                    val queued = pendingUtteranceToSpeak
                    if (!queued.isNullOrBlank()) {
                        pendingUtteranceToSpeak = null
                        dispatchTtsChunks(queued)
                    }
                } else {
                    ttsReady = false
                    val cb = pendingTtsFinishedCallback
                    pendingTtsFinishedCallback = null
                    cb?.invoke()
                }
            }
        }
    }

    private fun dispatchTtsChunks(text: String) {
        val engine = ttsEngine ?: return
        val chunks = text.chunked(3200)
        val baseId = "utt-${PlatformRuntime.currentTimeMillis()}"
        chunks.forEachIndexed { idx, chunk ->
            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uttId = if (idx == chunks.lastIndex) "$baseId-last" else "$baseId-$idx"
            engine.speak(chunk, mode, null, uttId)
        }
    }

    actual fun speakTextAloud(text: String, onFinished: () -> Unit) {
        runCatching {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) {
                onFinished()
                return@runCatching
            }
            // Reset any prior speaking turn's callback before starting the new utterance
            pendingTtsFinishedCallback?.invoke()
            pendingTtsFinishedCallback = onFinished
            val ctx = AndroidPlatformHost.appContext
            if (ttsEngine == null || !ttsReady) {
                pendingUtteranceToSpeak = cleaned
                if (ctx != null) ensureTtsInitialized(ctx)
            } else {
                dispatchTtsChunks(cleaned)
            }
        }.onFailure {
            onFinished()
        }
    }

    actual fun stopSpeakingAloud() {
        runCatching {
            pendingUtteranceToSpeak = null
            val cb = pendingTtsFinishedCallback
            pendingTtsFinishedCallback = null
            ttsEngine?.stop()
            cb?.invoke()
        }
    }

    actual fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit) {
        val act = AndroidPlatformHost.mainActivityRef?.get()
        if (act == null) {
            onError("Activity context unavailable.")
            return
        }
        act.launchVoiceRecognizer(onRecognizedText, onError)
    }

    actual fun stopVoiceInput() {
        runCatching {
            AndroidPlatformHost.mainActivityRef?.get()?.stopVoiceRecognizer()
        }
    }
}
