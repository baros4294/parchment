package io.parchment.ai.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File

/** Reads an image's dimensions before decoding it, so a tiny file cannot demand gigabytes of pixels. */
internal object DesktopImageDecoder {
    private const val MAX_ENCODED_BYTES = 128L * 1024L * 1024L
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    private const val MAX_DISPLAY_DIMENSION = 2560

    fun decode(file: File): ImageBitmap? {
        if (file.length() !in 1..MAX_ENCODED_BYTES) return null
        return decode(file.readBytes())
    }

    fun decode(bytes: ByteArray): ImageBitmap? {
        val (width, height) = Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { it.width to it.height }
        }
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null
        return Image.makeFromEncoded(bytes).use { image ->
            val longest = maxOf(width, height)
            if (longest <= MAX_DISPLAY_DIMENSION) return image.toComposeImageBitmap()
            val targetWidth = (width.toLong() * MAX_DISPLAY_DIMENSION / longest).toInt().coerceAtLeast(1)
            val targetHeight = (height.toLong() * MAX_DISPLAY_DIMENSION / longest).toInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(targetWidth, targetHeight).use { surface ->
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true
                )
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }
}
