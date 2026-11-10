package io.parchment.ai.network

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import io.parchment.ai.runtime.AppForeground
import kotlinx.coroutines.*
import kotlin.test.*

class ForegroundStallTest {
    private val events = mutableListOf<String>()

    private fun CoroutineScope.read(channel: ByteChannel) = async(Dispatchers.Default + SupervisorJob()) {
        runCatching {
            AiProviderEngine.readSseOrJsonFallback(channel, { true }, { _, data -> events += data }, {})
        }
    }

    private suspend fun ByteChannel.line(text: String) {
        writeStringUtf8("data: $text\n\n")
        flush()
    }

    private fun leaveAndReturn() {
        AppForeground.markLeft()
        Thread.sleep(50)
        AppForeground.markResumed()
    }

    @Test fun aConnectionCutWhileAwayIsDroppedSoonAfterReturning() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        val reading = read(channel)
        channel.line("first")
        delay(300)
        leaveAndReturn()
        val failure = withTimeout(8_000) { reading.await() }.exceptionOrNull()
        assertTrue("SSE stream timed out" in failure?.message.orEmpty(), failure.toString())
        assertEquals(listOf("first"), events)
    }

    @Test fun aConnectionCutWhileAwayRetriesWithoutWaiting() = runBlocking<Unit> {
        val statuses = mutableListOf<String>()
        withTimeout(2_000) {
            AiProviderEngine.waitWithRetryCountdown(1, 3, waitSeconds = 60, reasonSummary = "SSE stream timed out while the app was in the background.") { statuses += it }
        }
        assertEquals(listOf("Retrying now (attempt 1/3)…"), statuses)
    }

    @Test fun dataArrivingAfterReturningKeepsTheStream() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        val reading = read(channel)
        channel.line("first")
        delay(300)
        leaveAndReturn()
        delay(500)
        channel.line("second")
        delay(4_000)
        channel.line("third")
        channel.close()
        assertNull(withTimeout(5_000) { reading.await() }.exceptionOrNull())
        assertEquals(listOf("first", "second", "third"), events)
    }

    @Test fun aSlowFirstReplyIsNotMistakenForACutConnection() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        val reading = read(channel)
        delay(100)
        leaveAndReturn()
        delay(4_000)
        assertFalse(reading.isCompleted, "Silent thinking before the first line is normal")
        channel.line("first")
        channel.close()
        assertNull(withTimeout(5_000) { reading.await() }.exceptionOrNull())
    }

    @Test fun returningWithoutHavingLeftChangesNothing() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        val reading = read(channel)
        channel.line("first")
        delay(200)
        val started = AppForeground.leftAt
        delay(100)
        assertEquals(started, AppForeground.leftAt)
        channel.line("second")
        channel.close()
        assertNull(withTimeout(5_000) { reading.await() }.exceptionOrNull())
    }
}
