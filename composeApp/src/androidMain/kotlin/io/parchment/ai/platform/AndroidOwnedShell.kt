package io.parchment.ai.platform

import android.system.Os
import android.system.OsConstants
import io.parchment.ai.harness.OwnedShellProtocol
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Per-call process session. A live supervisor keeps the group identity owned until cleanup. */
internal object AndroidOwnedShell {
    private val setsidPath: String? by lazy {
        val probe = ProcessBuilder("/system/bin/sh", "-c", "command -v setsid").start()
        try {
            if (!probe.waitFor(2, TimeUnit.SECONDS)) null else {
                val path = probe.inputStream.bufferedReader().use { readControlLine(it) }.trim()
                path.takeIf { probe.exitValue() == 0 && File(it).isAbsolute && File(it).canExecute() }
            }
        } finally {
            if (probe.isAlive) probe.destroyForcibly()
            runCatching { probe.inputStream.close() }
            runCatching { probe.errorStream.close() }
            runCatching { probe.outputStream.close() }
        }
    }

    private fun readControlLine(reader: BufferedReader): String {
        val line = StringBuilder()
        repeat(256) {
            val character = reader.read()
            if (character == -1 || character == '\n'.code) return line.toString().trimEnd('\r')
            line.append(character.toChar())
        }
        error("Owned Shell control line exceeded its limit.")
    }

    fun execute(root: File, command: String, timeoutSeconds: Int, onOutput: ((String) -> Unit)?, cancelled: (() -> Boolean)?): Result<String> = runCatching {
        require(cancelled?.invoke() != true) { "The owning run stopped before shell startup." }
        val launcher = setsidPath ?: error("Owned Shell sessions are unavailable: setsid was not found.")
        val protocol = OwnedShellProtocol(UUID.randomUUID().toString())
        val process = ProcessBuilder(launcher, "-w", "/system/bin/sh", "-c", protocol.supervisorScript(command))
            .directory(root).redirectErrorStream(false).start()
        val groupPid = AtomicInteger(0)
        val groupTerminated = AtomicBoolean(false)
        val exitCode = AtomicInteger(-1)
        val failure = AtomicReference<Throwable?>(null)
        val cleanupFailure = AtomicReference<Throwable?>(null)
        val output = StringBuilder()

        fun terminateOwnedSession() {
            // Never signal 0, -1, the app's group, or a numeric identity after its supervisor exits.
            val pid = groupPid.get()
            if (process.isAlive && pid > 1 && pid != android.os.Process.myPid() && groupTerminated.compareAndSet(false, true)) {
                runCatching { Os.kill(-pid, OsConstants.SIGKILL) }.onFailure { cleanupFailure.compareAndSet(null, it) }
            }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }

        val outputReader = Thread {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    var lastEmit = 0L
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val snapshot = synchronized(output) {
                            if (output.length < 48_000) output.append(buffer, 0, minOf(count, 48_000 - output.length))
                            val now = System.nanoTime()
                            if (onOutput != null && now - lastEmit >= TimeUnit.MILLISECONDS.toNanos(100)) {
                                lastEmit = now
                                output.toString()
                            } else null
                        }
                        if (snapshot != null) runCatching { onOutput?.invoke(snapshot) }
                    }
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }.apply { name = "parchment-shell-output"; isDaemon = true; start() }
        val controlReader = Thread {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    groupPid.set(protocol.parseOwnerPid(readControlLine(reader), android.os.Process.myPid()))
                    check(cancelled?.invoke() != true) { "The owning run stopped before command execution." }
                    // The command cannot start before identity is established. Its stdin is /dev/null;
                    // the supervisor's separate pipe stays open so its identity cannot be recycled.
                    process.outputStream.write((protocol.startAcknowledgement + "\n").toByteArray(Charsets.UTF_8))
                    process.outputStream.flush()
                    exitCode.set(protocol.parseExitCode(readControlLine(reader)))
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }.apply { name = "parchment-shell-control"; isDaemon = true; start() }

        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceIn(1, 120).toLong())
            while (exitCode.get() < 0) {
                check(cancelled?.invoke() != true) { "Shell command stopped or its permission was revoked." }
                failure.get()?.let { throw it }
                check(System.nanoTime() < deadline) { "Command timed out after $timeoutSeconds seconds." }
                if (process.waitFor(100, TimeUnit.MILLISECONDS) && exitCode.get() < 0) error("Owned Shell supervisor exited before reporting an outcome.")
            }
            terminateOwnedSession()
            cleanupFailure.get()?.let { throw IllegalStateException("Command finished (exit code ${exitCode.get()}) but the OS could not terminate the owned Shell session.", it) }
            outputReader.join(1500)
            val text = synchronized(output) { output.toString().trim() }
            if (text.isNotEmpty()) runCatching { onOutput?.invoke(text) }
            "Exit Code: ${exitCode.get()}\n${text.take(6000)}"
        } catch (error: Throwable) {
            val partial = synchronized(output) { output.toString().trim().take(3000) }
            throw IllegalStateException((error.message ?: "Shell execution failed.") +
                if (partial.isEmpty()) "" else "\nPartial output:\n$partial", error)
        } finally {
            terminateOwnedSession()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { outputReader.join(1500) }
            runCatching { controlReader.join(1500) }
        }
    }
}
