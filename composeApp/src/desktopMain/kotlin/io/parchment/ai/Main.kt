package io.parchment.ai

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.parchment.ai.platform.PickedBinaryFile
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.ui.ParchmentApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.jetbrains.skia.Image
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.util.Properties

private data class SavedWindowGeometry(
    val isMaximized: Boolean,
    val widthDp: Int,
    val heightDp: Int,
    val xDp: Int,
    val yDp: Int
)

private fun windowBoundsFile(): File =
    File(PlatformBridge.getAppStorageDirectoryPath(), "window-bounds.properties")

private fun loadSavedWindowGeometry(): SavedWindowGeometry {
    return runCatching {
        val f = windowBoundsFile()
        if (!f.exists() || !f.isFile) {
            return SavedWindowGeometry(isMaximized = true, widthDp = 1400, heightDp = 900, xDp = -1, yDp = -1)
        }
        val props = Properties()
        f.inputStream().use { props.load(it) }
        SavedWindowGeometry(
            isMaximized = props.getProperty("isMaximized", "true").toBoolean(),
            widthDp = (props.getProperty("widthDp", "1400").toIntOrNull() ?: 1400).coerceIn(640, 7680),
            heightDp = (props.getProperty("heightDp", "900").toIntOrNull() ?: 900).coerceIn(480, 4320),
            xDp = props.getProperty("xDp", "-1").toIntOrNull() ?: -1,
            yDp = props.getProperty("yDp", "-1").toIntOrNull() ?: -1
        )
    }.getOrDefault(SavedWindowGeometry(isMaximized = true, widthDp = 1400, heightDp = 900, xDp = -1, yDp = -1))
}

private fun saveWindowGeometry(geom: SavedWindowGeometry) {
    runCatching {
        val f = windowBoundsFile()
        f.parentFile?.mkdirs()
        val props = Properties().apply {
            setProperty("isMaximized", geom.isMaximized.toString())
            setProperty("widthDp", geom.widthDp.toString())
            setProperty("heightDp", geom.heightDp.toString())
            setProperty("xDp", geom.xDp.toString())
            setProperty("yDp", geom.yDp.toString())
        }
        f.outputStream().use { props.store(it, "Parchment Desktop Window Geometry") }
    }
}

private fun attachDropTargetRecursively(component: Component, createTarget: () -> DropTarget) {
    runCatching {
        component.dropTarget = createTarget()
        if (component is Container) {
            for (child in component.components) {
                attachDropTargetRecursively(child, createTarget)
            }
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
fun main() {
    // Enable hardware VSync so Skia renders smoothly at the monitor's native refresh rate (60Hz / 120Hz / 144Hz / 240Hz)
    System.setProperty("skiko.vsync.enabled", "true")

    application {
        var windowTitle by remember { mutableStateOf("Parchment — Literary & Bleeding-Edge AI Studio") }
        PlatformBridge.windowStatusCallback = { newTitle -> windowTitle = newTitle }

    val initialGeom = remember { loadSavedWindowGeometry() }

    val windowState = rememberWindowState(
        placement = if (initialGeom.isMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        size = DpSize(initialGeom.widthDp.dp, initialGeom.heightDp.dp),
        position = if (!initialGeom.isMaximized && initialGeom.xDp >= 0 && initialGeom.yDp >= 0) {
            WindowPosition(initialGeom.xDp.dp, initialGeom.yDp.dp)
        } else {
            WindowPosition.Aligned(Alignment.Center)
        }
    )

    // Keep track of the last floating (non-maximized) size & position so restoring from Maximized works cleanly
    var lastFloatingWidth by remember { mutableStateOf(initialGeom.widthDp) }
    var lastFloatingHeight by remember { mutableStateOf(initialGeom.heightDp) }
    var lastFloatingX by remember { mutableStateOf(initialGeom.xDp) }
    var lastFloatingY by remember { mutableStateOf(initialGeom.yDp) }

    LaunchedEffect(windowState) {
        snapshotFlow {
            Triple(
                windowState.placement,
                windowState.size,
                windowState.position
            )
        }
            .debounce(250L)
            .collectLatest { (placement, size, pos) ->
                val isMax = placement == WindowPlacement.Maximized || placement == WindowPlacement.Fullscreen
                if (!isMax) {
                    val w = size.width.value.toInt()
                    val h = size.height.value.toInt()
                    if (w >= 400 && h >= 300) {
                        lastFloatingWidth = w
                        lastFloatingHeight = h
                    }
                    if (pos.isSpecified) {
                        lastFloatingX = pos.x.value.toInt()
                        lastFloatingY = pos.y.value.toInt()
                    }
                }
                saveWindowGeometry(
                    SavedWindowGeometry(
                        isMaximized = isMax,
                        widthDp = lastFloatingWidth,
                        heightDp = lastFloatingHeight,
                        xDp = lastFloatingX,
                        yDp = lastFloatingY
                    )
                )
            }
    }

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
            onCloseRequest = {
                val isMax = windowState.placement == WindowPlacement.Maximized || windowState.placement == WindowPlacement.Fullscreen
                saveWindowGeometry(
                    SavedWindowGeometry(
                        isMaximized = isMax,
                        widthDp = lastFloatingWidth,
                        heightDp = lastFloatingHeight,
                        xDp = lastFloatingX,
                        yDp = lastFloatingY
                    )
                )
                PlatformBridge.flushPendingStateNow()
                exitApplication()
            },
            state = windowState,
            title = windowTitle,
            icon = appIconPainter
        ) {
            LaunchedEffect(window) {
                val makeDropTarget: () -> DropTarget = {
                    DropTarget(
                        null,
                        DnDConstants.ACTION_COPY_OR_MOVE,
                        object : DropTargetAdapter() {
                            override fun dragEnter(dtde: DropTargetDragEvent) {
                                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                                    PlatformBridge.dragHoverCallback?.invoke(true)
                                }
                            }

                            override fun dragOver(dtde: DropTargetDragEvent) {
                                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                                }
                            }

                            override fun dragExit(dte: DropTargetEvent) {
                                PlatformBridge.dragHoverCallback?.invoke(false)
                            }

                            override fun drop(dtde: DropTargetDropEvent) {
                                PlatformBridge.dragHoverCallback?.invoke(false)
                                runCatching {
                                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                                        val dropped = dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                                        val pickedFiles = dropped?.mapNotNull { item ->
                                            val f = item as? File
                                            if (f != null && f.exists() && f.isFile && f.length() <= 150_000_000L) {
                                                runCatching { PickedBinaryFile(f.name, f.readBytes()) }.getOrNull()
                                            } else null
                                        }.orEmpty()
                                        if (pickedFiles.isNotEmpty()) {
                                            PlatformBridge.filesDroppedCallback?.invoke(pickedFiles)
                                        }
                                        dtde.dropComplete(true)
                                    } else {
                                        dtde.rejectDrop()
                                    }
                                }.onFailure {
                                    runCatching { dtde.dropComplete(false) }
                                }
                            }
                        },
                        true
                    )
                }
                attachDropTargetRecursively(window, makeDropTarget)
            }

            ParchmentApp()
        }
    }
}
