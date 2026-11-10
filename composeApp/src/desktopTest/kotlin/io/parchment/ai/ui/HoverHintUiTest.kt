package io.parchment.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class HoverHintUiTest {
    private var frame = 0L

    private fun pump(scene: ImageComposeScene, millis: Long = 800) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            Snapshot.sendApplyNotifications()
            frame += 16_000_000L
            scene.render(frame).close()
            Thread.sleep(16)
        }
    }

    private fun withButton(windowWidth: Int, check: (ImageComposeScene) -> Unit) {
        val scene = ImageComposeScene(600, 400, coroutineContext = Dispatchers.Unconfined)
        try {
            scene.setContent {
                CompositionLocalProvider(LocalParchmentWindowSize provides ParchmentWindowSize(windowWidth.dp, 400.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        SepiaButton("✎", onClick = {}, contentDescription = "Edit message", modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
            pump(scene, 50)
            check(scene)
        } finally { scene.close() }
    }

    private fun hover(scene: ImageComposeScene, x: Float, y: Float) =
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Mouse)

    @Test fun hintAppearsOnHoverAndLeavesWithThePointer() = withButton(900) { scene ->
        assertEquals(1, scene.semanticsOwners.size, "No hint before hovering")
        hover(scene, 300f, 200f)
        pump(scene)
        assertEquals(2, scene.semanticsOwners.size, "The hint opens after a short pause")
        hover(scene, 2f, 2f)
        pump(scene, 150)
        assertEquals(1, scene.semanticsOwners.size, "The hint closes with the pointer")
    }

    @Test fun touchLayoutsHaveNoHoverHints() = withButton(400) { scene ->
        hover(scene, 300f, 200f)
        pump(scene)
        assertEquals(1, scene.semanticsOwners.size)
    }
}
