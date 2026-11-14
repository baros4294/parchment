package io.parchment.ai.platform

import java.io.File

actual object PlatformVoice {
    @Volatile
    private var activeTtsProcess: Process? = null
    @Volatile
    private var activeSttProcess: Process? = null

    actual fun speakTextAloud(text: String, onFinished: () -> Unit) {
        stopSpeakingAloud()
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        Thread {
            runCatching {
                val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
                if (isWin) {
                    val tempTextFile = File(System.getProperty("java.io.tmpdir"), "parchment-tts-${PlatformRuntime.currentTimeMillis()}.txt")
                    tempTextFile.writeText(cleaned.take(6000), Charsets.UTF_8)
                    val ps = """
                        Add-Type -AssemblyName System.Speech
                        ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                        ${'$'}synth.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet)
                        ${'$'}txt = Get-Content -LiteralPath '${tempTextFile.absolutePath.replace("'", "''")}' -Raw -Encoding UTF8
                        ${'$'}synth.Speak(${'$'}txt)
                        Remove-Item -LiteralPath '${tempTextFile.absolutePath.replace("'", "''")}' -Force -ErrorAction SilentlyContinue
                    """.trimIndent()
                    val proc = ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-WindowStyle", "Hidden",
                        "-Command", ps
                    ).start()
                    activeTtsProcess = proc
                    runCatching { proc.outputStream.close() }
                    runCatching { proc.inputStream.bufferedReader(Charsets.UTF_8).readText() }
                    proc.waitFor()
                    tempTextFile.delete()
                }
            }
            activeTtsProcess = null
            onFinished()
        }.apply { isDaemon = true }.start()
    }

    actual fun stopSpeakingAloud() {
        runCatching {
            activeTtsProcess?.destroyForcibly()
            activeTtsProcess = null
        }
    }

    actual fun startVoiceInput(onRecognizedText: (String) -> Unit, onError: (String) -> Unit) {
        stopVoiceInput()
        Thread {
            runCatching {
                val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
                if (!isWin) {
                    onError("Native voice recognition is supported on Windows and Android.")
                    return@Thread
                }
                val ps = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Add-Type -AssemblyName System.Speech
                    ${'$'}rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine
                    ${'$'}rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                    ${'$'}rec.SetInputToDefaultAudioDevice()
                    ${'$'}res = ${'$'}rec.Recognize([TimeSpan]::FromSeconds(12))
                    if (${'$'}res -ne ${'$'}null -and ${'$'}res.Text) { Write-Output ${'$'}res.Text }
                """.trimIndent()
                val proc = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-Command", ps
                ).start()
                activeSttProcess = proc
                runCatching { proc.outputStream.close() }
                val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                proc.waitFor(18L, java.util.concurrent.TimeUnit.SECONDS)
                activeSttProcess = null
                if (output.isNotEmpty()) {
                    onRecognizedText(output)
                } else {
                    onError("No speech detected from microphone.")
                }
            }.onFailure { err ->
                activeSttProcess = null
                onError("Microphone voice input error: ${err.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    actual fun stopVoiceInput() {
        runCatching {
            activeSttProcess?.destroyForcibly()
            activeSttProcess = null
        }
    }
}
