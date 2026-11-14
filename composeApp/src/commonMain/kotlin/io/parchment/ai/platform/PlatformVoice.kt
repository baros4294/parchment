package io.parchment.ai.platform

expect object PlatformVoice {
    // Native Text-to-Speech (TTS) & Live Voice Input (STT)
    fun speakTextAloud(text: String, onFinished: () -> Unit = {})
    fun stopSpeakingAloud()
    fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit = {})
    fun stopVoiceInput()
}
