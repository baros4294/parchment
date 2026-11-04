package io.parchment.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.ui.KissIcon
import io.parchment.ai.ui.KissVectorIcon
import io.parchment.ai.ui.LocalParchmentPalette
import io.parchment.ai.ui.ParchmentApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.jetbrains.skia.Image
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
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
            component.addContainerListener(object : ContainerAdapter() {
                override fun componentAdded(e: ContainerEvent) {
                    e.child?.let { attachDropTargetRecursively(it, createTarget) }
                }
            })
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

        val closeAndSave: () -> Unit = {
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
        }

        val toggleMaximizeRestore: () -> Unit = {
            val isMax = windowState.placement == WindowPlacement.Maximized || windowState.placement == WindowPlacement.Fullscreen
            if (isMax) {
                windowState.placement = WindowPlacement.Floating
                windowState.size = DpSize(lastFloatingWidth.dp, lastFloatingHeight.dp)
                if (lastFloatingX >= 0 && lastFloatingY >= 0) {
                    windowState.position = WindowPosition(lastFloatingX.dp, lastFloatingY.dp)
                }
            } else {
                windowState.placement = WindowPlacement.Maximized
            }
        }

        Window(
            onCloseRequest = closeAndSave,
            state = windowState,
            title = windowTitle,
            icon = appIconPainter,
            undecorated = true,
            onKeyEvent = { keyEvent ->
                val isPasteShortcut = keyEvent.type == KeyEventType.KeyDown &&
                    !keyEvent.isAltPressed &&
                    (((keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.key == Key.V) ||
                        (keyEvent.isShiftPressed && keyEvent.key == Key.Insert))
                if (isPasteShortcut) {
                    val pastedFiles = PlatformBridge.readClipboardFiles()
                    if (pastedFiles.isNotEmpty()) {
                        PlatformBridge.filesDroppedCallback?.invoke(pastedFiles)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        ) {
            LaunchedEffect(window) {
                fun isSupportedDrop(dtde: DropTargetDragEvent): Boolean {
                    return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                        dtde.isDataFlavorSupported(DataFlavor.imageFlavor)
                }

                val makeDropTarget: () -> DropTarget = {
                    DropTarget(
                        null,
                        DnDConstants.ACTION_COPY_OR_MOVE,
                        object : DropTargetAdapter() {
                            override fun dragEnter(dtde: DropTargetDragEvent) {
                                if (isSupportedDrop(dtde)) {
                                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                                    PlatformBridge.dragHoverCallback?.invoke(true)
                                }
                            }

                            override fun dragOver(dtde: DropTargetDragEvent) {
                                if (isSupportedDrop(dtde)) {
                                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                                    PlatformBridge.dragHoverCallback?.invoke(true)
                                }
                            }

                            override fun dragExit(dte: DropTargetEvent) {
                                // Only clear drag hover if cursor actually left the window bounds (prevents child-component flicker)
                                val stillInside = runCatching {
                                    val mousePt = MouseInfo.getPointerInfo()?.location
                                    val winBounds = window.bounds
                                    mousePt != null && winBounds.contains(mousePt)
                                }.getOrDefault(false)
                                if (!stillInside) {
                                    PlatformBridge.dragHoverCallback?.invoke(false)
                                }
                            }

                            override fun drop(dtde: DropTargetDropEvent) {
                                PlatformBridge.dragHoverCallback?.invoke(false)
                                runCatching {
                                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                                        dtde.isDataFlavorSupported(DataFlavor.imageFlavor)
                                    ) {
                                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                                        val pickedFiles = PlatformBridge.extractTransferableFiles(dtde.transferable)
                                        if (pickedFiles.isNotEmpty()) {
                                            PlatformBridge.filesDroppedCallback?.invoke(pickedFiles)
                                            dtde.dropComplete(true)
                                        } else {
                                            dtde.dropComplete(false)
                                        }
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
            val isMaximized = windowState.placement == WindowPlacement.Maximized ||
                windowState.placement == WindowPlacement.Fullscreen

            Box(modifier = Modifier.fillMaxSize()) {
                ParchmentApp(
                    desktopWindowHeader = {
                        KissDesktopTitleBar(
                            windowTitle = windowTitle,
                            appIconPainter = appIconPainter,
                            isMaximized = isMaximized,
                            onMinimize = { windowState.isMinimized = true },
                            onToggleMaximize = toggleMaximizeRestore,
                            onClose = closeAndSave
                        )
                    }
                )

                if (!isMaximized) {
                    DesktopWindowResizeEdges(window = window)
                }
            }
        }
    }
}

@Composable
private fun WindowScope.KissDesktopTitleBar(
    windowTitle: String,
    appIconPainter: BitmapPainter?,
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit
) {
    val p = LocalParchmentPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(p.sheetBg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Draggable Window Caption Region with Double-Click to Maximize / Restore
        WindowDraggableArea(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(isMaximized) {
                    detectTapGestures(
                        onDoubleTap = { onToggleMaximize() }
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (appIconPainter != null) {
                    Image(
                        painter = appIconPainter,
                        contentDescription = "Parchment",
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.5.dp))
                    )
                } else {
                    KissVectorIcon(
                        icon = KissIcon.EDIT,
                        tint = p.accent,
                        size = 13.dp
                    )
                }
                BasicText(
                    text = "Parchment",
                    maxLines = 1,
                    style = p.uiHeaderStyle.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.primaryInk
                    )
                )
                val subtitle = windowTitle.removePrefix("Parchment").removePrefix(" — ").removePrefix(" - ").trim()
                if (subtitle.isNotEmpty()) {
                    BasicText(
                        text = "— $subtitle",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = p.uiLabelStyle.copy(
                            fontSize = 11.sp,
                            color = p.mutedInk
                        )
                    )
                }
            }
        }

        // Right Caption Controls: Minimize (—), Maximize/Restore (□/❐), Close (×)
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Minimize Button
            val minInteraction = remember { MutableInteractionSource() }
            val minHovered by minInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .background(if (minHovered) p.primaryInk.copy(alpha = 0.09f) else Color.Transparent)
                    .clickable(interactionSource = minInteraction, indication = null, onClick = onMinimize),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val y = size.height / 2f
                    drawLine(
                        color = p.primaryInk,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 2. Maximize / Restore Button
            val maxInteraction = remember { MutableInteractionSource() }
            val maxHovered by maxInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .background(if (maxHovered) p.primaryInk.copy(alpha = 0.09f) else Color.Transparent)
                    .clickable(interactionSource = maxInteraction, indication = null, onClick = onToggleMaximize),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val strokeW = 1.25.dp.toPx()
                    val stroke = Stroke(width = strokeW)
                    if (isMaximized) {
                        val boxW = size.width * 0.78f
                        val boxH = size.height * 0.78f
                        val off = size.width - boxW
                        drawRoundRect(
                            color = p.primaryInk,
                            topLeft = Offset(off, 0f),
                            size = Size(boxW, boxH),
                            cornerRadius = CornerRadius(1.dp.toPx()),
                            style = stroke
                        )
                        drawRoundRect(
                            color = p.sheetBg,
                            topLeft = Offset(0f, off),
                            size = Size(boxW, boxH),
                            cornerRadius = CornerRadius(1.dp.toPx())
                        )
                        drawRoundRect(
                            color = p.primaryInk,
                            topLeft = Offset(0f, off),
                            size = Size(boxW, boxH),
                            cornerRadius = CornerRadius(1.dp.toPx()),
                            style = stroke
                        )
                    } else {
                        drawRoundRect(
                            color = p.primaryInk,
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = CornerRadius(1.2.dp.toPx()),
                            style = stroke
                        )
                    }
                }
            }

            // 3. Close Button (Warm Crimson Hover with Crisp White X)
            val closeInteraction = remember { MutableInteractionSource() }
            val closeHovered by closeInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight()
                    .background(if (closeHovered) p.danger else Color.Transparent)
                    .clickable(interactionSource = closeInteraction, indication = null, onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                val xColor = if (closeHovered) Color.White else p.primaryInk
                Canvas(modifier = Modifier.size(10.dp)) {
                    val strokeW = 1.35.dp.toPx()
                    drawLine(
                        color = xColor,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = xColor,
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopWindowResizeEdges(window: java.awt.Window) {
    val edgeThickness = 5.dp
    val cornerSize = 10.dp

    @Composable
    fun ResizeZone(
        modifier: Modifier,
        cursorType: Int,
        dxLeft: Boolean = false,
        dxRight: Boolean = false,
        dyTop: Boolean = false,
        dyBottom: Boolean = false
    ) {
        Box(
            modifier = modifier
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursorType)))
                .pointerInput(cursorType) {
                    var startMouseX = 0
                    var startMouseY = 0
                    var startBounds = Rectangle()
                    var dragging = false
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            when (ev.type) {
                                PointerEventType.Press -> {
                                    val pt = MouseInfo.getPointerInfo()?.location
                                    if (pt != null) {
                                        startMouseX = pt.x
                                        startMouseY = pt.y
                                        startBounds = window.bounds
                                        dragging = true
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (dragging && ev.changes.any { it.pressed }) {
                                        val pt = MouseInfo.getPointerInfo()?.location
                                        if (pt != null) {
                                            val deltaX = pt.x - startMouseX
                                            val deltaY = pt.y - startMouseY
                                            var newX = startBounds.x
                                            var newY = startBounds.y
                                            var newW = startBounds.width
                                            var newH = startBounds.height
                                            val minW = 640
                                            val minH = 480
                                            if (dxRight) {
                                                newW = (startBounds.width + deltaX).coerceAtLeast(minW)
                                            }
                                            if (dyBottom) {
                                                newH = (startBounds.height + deltaY).coerceAtLeast(minH)
                                            }
                                            if (dxLeft) {
                                                val candidateW = (startBounds.width - deltaX).coerceAtLeast(minW)
                                                newX = startBounds.x + (startBounds.width - candidateW)
                                                newW = candidateW
                                            }
                                            if (dyTop) {
                                                val candidateH = (startBounds.height - deltaY).coerceAtLeast(minH)
                                                newY = startBounds.y + (startBounds.height - candidateH)
                                                newH = candidateH
                                            }
                                            window.setBounds(newX, newY, newW, newH)
                                        }
                                    } else {
                                        dragging = false
                                    }
                                }
                                PointerEventType.Release -> {
                                    dragging = false
                                }
                            }
                        }
                    }
                }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Edges
        ResizeZone(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edgeThickness), Cursor.N_RESIZE_CURSOR, dyTop = true)
        ResizeZone(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edgeThickness), Cursor.S_RESIZE_CURSOR, dyBottom = true)
        ResizeZone(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edgeThickness), Cursor.W_RESIZE_CURSOR, dxLeft = true)
        ResizeZone(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edgeThickness), Cursor.E_RESIZE_CURSOR, dxRight = true)
        // Corners
        ResizeZone(Modifier.align(Alignment.TopStart).size(cornerSize), Cursor.NW_RESIZE_CURSOR, dxLeft = true, dyTop = true)
        ResizeZone(Modifier.align(Alignment.TopEnd).size(cornerSize), Cursor.NE_RESIZE_CURSOR, dxRight = true, dyTop = true)
        ResizeZone(Modifier.align(Alignment.BottomStart).size(cornerSize), Cursor.SW_RESIZE_CURSOR, dxLeft = true, dyBottom = true)
        ResizeZone(Modifier.align(Alignment.BottomEnd).size(cornerSize), Cursor.SE_RESIZE_CURSOR, dxRight = true, dyBottom = true)
    }
}
