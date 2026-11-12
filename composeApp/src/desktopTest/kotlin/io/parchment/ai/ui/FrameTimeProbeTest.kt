package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Frame times while a reply streams: the reply grows a few characters per frame and each frame is composed,
 * laid out and drawn. Drawing here is on the CPU, so these numbers are a pessimistic bound for the real app,
 * which draws on the GPU. Results go to .parchment-local/verification/frame-times.txt for docs/PERFORMANCE.md.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FrameTimeProbeTest {
    private val reply = (1..60).joinToString("\n\n") { number ->
        if (number % 10 == 0) "```kotlin\nval item$number = compute($number)\nprintln(item$number)\n```"
        else "Paragraph $number has **bold**, *italic* and `code` so the reply looks like a normal answer from a model."
    }

    private fun percentile(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    @Test fun streamingReplyFrameTimes() {
        val text = mutableStateOf("")
        val scene = ImageComposeScene(760, 860, density = Density(1f), coroutineContext = Dispatchers.Unconfined)
        val frames = mutableListOf<Long>()
        try {
            scene.setContent {
                Box(Modifier.fillMaxSize().background(LocalParchmentPalette.current.canvasBg).padding(24.dp)) {
                    LiteraryProseRenderer(text.value)
                }
            }
            var time = 0L
            var shown = 0
            var frame = 0
            while (shown < reply.length) {
                shown = minOf(reply.length, shown + CHARS_PER_FRAME)
                text.value = reply.substring(0, shown)
                Snapshot.sendApplyNotifications()
                time += 16_000_000L
                val started = System.nanoTime()
                scene.render(time).close()
                val micros = (System.nanoTime() - started) / 1_000
                if (frame++ >= WARMUP_FRAMES) frames += micros
            }
        } finally { scene.close() }

        val sorted = frames.sorted()
        val line = "streaming_reply chars=${reply.length} frames=${frames.size} " +
            "p50_ms=${"%.1f".format(percentile(sorted, 0.50) / 1000.0)} p95_ms=${"%.1f".format(percentile(sorted, 0.95) / 1000.0)} " +
            "max_ms=${"%.1f".format(sorted.last() / 1000.0)}"
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "TODO.md").isFile }
        File(repo, ".parchment-local/verification/frame-times.txt").apply { parentFile.mkdirs() }.writeText(line + "\n")
        println(line)
        // A loose ceiling: this is a measurement, and CI machines vary. It still catches a frame that has become far too slow.
        assertTrue(percentile(sorted, 0.95) < 250_000, line)
    }

    private companion object {
        const val CHARS_PER_FRAME = 12
        const val WARMUP_FRAMES = 30
    }
}
