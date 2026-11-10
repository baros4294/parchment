package io.parchment.ai.platform

/** Keeps the start and end of long command output: failures usually appear last. */
internal class BoundedTextCapture(
    private val headLimit: Int = 32_000,
    private val tailLimit: Int = 32_000
) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var totalChars = 0L

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        totalChars += count
        var offset = 0
        if (head.length < headLimit) {
            offset = minOf(count, headLimit - head.length)
            head.append(chars, 0, offset)
        }
        if (offset < count) {
            tail.append(chars, offset, count - offset)
            if (tail.length >= tailLimit * 2) tail.delete(0, tail.length - tailLimit)
        }
    }

    /** At most [maxChars] of output, plus a note saying how much was left out of the middle. */
    @Synchronized
    fun snapshot(maxChars: Int = headLimit + tailLimit): String {
        val retainedTail = tail.substring(maxOf(0, tail.length - tailLimit))
        val keepEach = maxChars / 2
        val start: String
        val end: String
        if (totalChars == (head.length + retainedTail.length).toLong()) {
            val text = head.toString() + retainedTail
            if (text.length <= maxChars) return text
            start = text.take(keepEach)
            end = text.takeLast(keepEach)
        } else {
            start = head.take(keepEach).toString()
            end = retainedTail.takeLast(keepEach)
        }
        val omitted = totalChars - start.length - end.length
        val safeStart = if (start.lastOrNull()?.isHighSurrogate() == true) start.dropLast(1) else start
        val safeEnd = if (end.firstOrNull()?.isLowSurrogate() == true) end.drop(1) else end
        return "$safeStart\n… [$omitted characters omitted] …\n$safeEnd"
    }
}
