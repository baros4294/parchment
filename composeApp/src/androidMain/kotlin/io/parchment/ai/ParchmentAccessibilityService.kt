package io.parchment.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Does nothing by itself: it only carries out actions the user approved in a chat. */
class ParchmentAccessibilityService : AccessibilityService() {
    private val executor by lazy { Executors.newSingleThreadExecutor() }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun capture(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val done = CountDownLatch(1)
        val image = AtomicReference<Bitmap?>()
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                image.set(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false))
                buffer.close()
                done.countDown()
            }
            override fun onFailure(errorCode: Int) = done.countDown()
        })
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return image.get()
    }

    private fun perform(gesture: GestureDescription): Boolean {
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val started = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }
            override fun onCancelled(description: GestureDescription?) = done.countDown()
        }, null)
        return started && done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) && completed.get()
    }

    private fun stroke(path: Path, durationMs: Long) =
        GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()

    fun press(x: Float, y: Float, durationMs: Long): Boolean = perform(stroke(Path().apply { moveTo(x, y) }, durationMs))

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean =
        perform(stroke(Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }, 300))

    fun enterText(text: String): Boolean {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 8L
        @Volatile var instance: ParchmentAccessibilityService? = null
            private set
    }
}
