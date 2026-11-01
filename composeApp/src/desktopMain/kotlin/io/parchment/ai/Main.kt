package io.parchment.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.ui.ParchmentApp
import org.jetbrains.skia.Image

fun main() = application {
    var windowTitle by remember { mutableStateOf("Parchment — Literary & Bleeding-Edge AI Studio") }
    PlatformBridge.windowStatusCallback = { newTitle -> windowTitle = newTitle }

    val windowState = rememberWindowState(
        size = DpSize(1240.dp, 840.dp),
        position = WindowPosition.Aligned(Alignment.Center)
    )

    val appIconPainter = remember {
        runCatching {
            val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("icon/parchment-icon.png")
            val bytes = stream?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
            } else null
        }.getOrNull()
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = windowTitle,
        icon = appIconPainter
    ) {
        ParchmentApp()
    }
}
