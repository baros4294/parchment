package io.parchment.ai.platform

import java.io.File
import java.util.concurrent.TimeUnit

internal enum class WindowsPowerLeaseStatus {
    IDLE, STARTING, ACTIVE, UNAVAILABLE
}

internal data class WindowsPowerLeaseSnapshot(
    val owners: Int,
    val status: WindowsPowerLeaseStatus,
    val error: String = ""
)

/**
 * Owns one monitored native sleep inhibitor for active generation calls.
 * Heartbeats confirm the Win32 call, not merely that PowerShell is alive.
 * Windows power policy and explicit suspend can still override the request.
 */
internal class WindowsPowerLease(
    private val parentPid: Long = ProcessHandle.current().pid(),
    private val launchHelper: (String) -> Process = ::launchWindowsPowerHelper,
    private val nanoTime: () -> Long = System::nanoTime
) : AutoCloseable {
    private val lock = Any()
    private var owners = 0
    private var closed = false
    private var helper: Process? = null
    private var monitor: Thread? = null
    private var monitorGeneration = 0L
    private var launchedAt = 0L
    private var heartbeatAt = 0L
    private var lastAttemptAt: Long? = null

    @Volatile
    var snapshot = WindowsPowerLeaseSnapshot(0, WindowsPowerLeaseStatus.IDLE)
        private set

    fun acquire() = synchronized(lock) {
        check(!closed) { "Windows power lease is closed." }
        owners++
        startOrRefreshLocked()
    }

    fun reconcile(activeOwners: Int) = synchronized(lock) {
        check(!closed) { "Windows power lease is closed." }
        require(activeOwners >= 0)
        owners = activeOwners
        if (owners == 0) stopLocked() else startOrRefreshLocked()
    }

    private fun startOrRefreshLocked() {
        if (monitor == null) {
            val generation = ++monitorGeneration
            monitor = Thread({ monitorLoop(generation) }, "parchment-power-monitor").apply {
                isDaemon = true
                start()
            }
        }
        maintainHelper()
        publish(snapshot.status, snapshot.error)
    }

    fun refresh() = synchronized(lock) {
        if (owners > 0 && !closed) maintainHelper()
    }

    fun release() = synchronized(lock) {
        if (owners > 0) owners--
        if (owners == 0) stopLocked() else publish(snapshot.status, snapshot.error)
    }

    override fun close() = synchronized(lock) {
        closed = true
        owners = 0
        stopLocked()
    }

    private fun monitorLoop(generation: Long) {
        try {
            while (true) {
                Thread.sleep(RETRY_INTERVAL_MS)
                synchronized(lock) {
                    if (owners == 0 || closed || generation != monitorGeneration) return
                    maintainHelper()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun maintainHelper() {
        val now = nanoTime()
        val current = helper
        if (current?.isAlive == true) {
            val healthy = when (snapshot.status) {
                WindowsPowerLeaseStatus.ACTIVE -> now - heartbeatAt < HEARTBEAT_TIMEOUT_NS
                WindowsPowerLeaseStatus.STARTING -> now - launchedAt < START_TIMEOUT_NS
                else -> false
            }
            if (healthy) return
            helper = null
            current.destroyForcibly()
            publish(WindowsPowerLeaseStatus.UNAVAILABLE, "Native power heartbeat was not confirmed.")
        } else if (current != null) {
            helper = null
            publish(WindowsPowerLeaseStatus.UNAVAILABLE, snapshot.error.ifBlank { "Power helper exited." })
        }
        val lastAttempt = lastAttemptAt
        if (lastAttempt != null && now - lastAttempt < RETRY_INTERVAL_NS) return
        lastAttemptAt = now
        try {
            val process = launchHelper(buildWindowsPowerHelperScript(parentPid))
            helper = process
            launchedAt = now
            heartbeatAt = 0L
            publish(WindowsPowerLeaseStatus.STARTING)
            Thread({ readHeartbeats(process) }, "parchment-power-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            publish(
                WindowsPowerLeaseStatus.UNAVAILABLE,
                error.message?.take(512) ?: "Unable to start the Windows power helper."
            )
        }
    }

    private fun readHeartbeats(process: Process) {
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lock) {
                        if (helper !== process || owners == 0) return
                        if (line.trim() == POWER_HEARTBEAT) {
                            heartbeatAt = nanoTime()
                            publish(WindowsPowerLeaseStatus.ACTIVE)
                        } else if (line.isNotBlank()) {
                            publish(WindowsPowerLeaseStatus.UNAVAILABLE, line.take(512))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (helper === process && owners > 0) {
                    publish(
                        WindowsPowerLeaseStatus.UNAVAILABLE,
                        error.message?.take(512) ?: "Power helper output was interrupted."
                    )
                }
            }
        } finally {
            synchronized(lock) {
                if (helper === process && owners > 0) {
                    publish(WindowsPowerLeaseStatus.UNAVAILABLE, snapshot.error.ifBlank { "Power helper exited." })
                }
            }
        }
    }

    private fun publish(status: WindowsPowerLeaseStatus, error: String = "") {
        snapshot = WindowsPowerLeaseSnapshot(owners, status, error)
    }

    private fun stopLocked() {
        ++monitorGeneration
        monitor?.interrupt()
        monitor = null
        val current = helper
        helper = null
        current?.destroyForcibly()
        heartbeatAt = 0L
        lastAttemptAt = null
        publish(WindowsPowerLeaseStatus.IDLE)
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 5_000L
        val RETRY_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(RETRY_INTERVAL_MS)
        val START_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(20)
        val HEARTBEAT_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60)
    }
}

internal const val POWER_HEARTBEAT = "PARCHMENT_POWER_ACTIVE"

internal fun buildWindowsPowerHelperScript(parentPid: Long): String = buildString {
    append("\$ProgressPreference = 'SilentlyContinue'; ")
    append("\$ErrorActionPreference = 'Stop'; ")
    append("\$code = '[DllImport(\"kernel32.dll\", SetLastError = true)] public static extern uint SetThreadExecutionState(uint esFlags);'; ")
    append("\$ste = Add-Type -MemberDefinition \$code -Name 'Win32Ste' -Namespace 'Parchment' -PassThru; ")
    append("try { while (\$true) { ")
    if (parentPid > 0L) {
        append("if (-not (Get-Process -Id ")
        append(parentPid)
        append(" -ErrorAction SilentlyContinue)) { break }; ")
    }
    // Explicit UInt32 values prevent PowerShell's signed hexadecimal conversion.
    append("\$previous = \$ste::SetThreadExecutionState([uint32]2147483649); ")
    append("if (\$previous -eq 0) { throw ('SetThreadExecutionState failed: ' + [Runtime.InteropServices.Marshal]::GetLastWin32Error()) }; ")
    append("[Console]::Out.WriteLine('")
    append(POWER_HEARTBEAT)
    append("'); Start-Sleep -Seconds 15 ")
    append("} } finally { [void]\$ste::SetThreadExecutionState([uint32]2147483648) }")
}

internal fun launchWindowsPowerHelper(script: String): Process {
    val systemRoot = System.getenv("SystemRoot") ?: throw IllegalStateException("Windows system directory is unavailable.")
    val executable = File(systemRoot, "System32/WindowsPowerShell/v1.0/powershell.exe")
    require(executable.isFile) { "Windows PowerShell is unavailable." }
    val encodedCommand = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return ProcessBuilder(
        executable.absolutePath, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encodedCommand
    ).redirectErrorStream(true).start()
}
