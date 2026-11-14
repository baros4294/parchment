package io.parchment.ai.harness

import io.parchment.ai.platform.OwnedShellProtocol
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Runs the real supervisor script under a POSIX shell, so its ordering and channels are proven, not assumed. */
class OwnedShellSupervisorTest {
    private val token = "run-token"
    private val protocol = OwnedShellProtocol(token)
    private val shell: String? = listOf("sh", "C:/Program Files/Git/bin/sh.exe").firstOrNull { candidate ->
        runCatching {
            val probe = ProcessBuilder(candidate, "-c", "exit 0").start()
            probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun supervise(command: String, check: (Process, File) -> Unit) {
        val sh = shell ?: return
        val root = Files.createTempDirectory("parchment-owned-shell-").toFile()
        val process = ProcessBuilder(sh, "-c", protocol.supervisorScript(command)).directory(root).start()
        try { check(process, root) } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    private fun Process.sendLine(line: String) {
        outputStream.write((line + "\n").toByteArray())
        outputStream.flush()
    }

    private fun Process.controlLine(): String = errorStream.bufferedReader().readLine()

    @Test fun theCommandWaitsForTheStartAcknowledgementAndReportsItsExitCode() = supervise("echo ran > marker; exit 7") { process, root ->
        val control = process.errorStream.bufferedReader()
        assertTrue(protocol.parseOwnerPid(control.readLine(), ProcessHandle.current().pid().toInt()) > 1)
        Thread.sleep(300)
        assertFalse(File(root, "marker").exists(), "The command must not start before its identity is acknowledged")
        process.sendLine(protocol.startAcknowledgement)
        assertEquals(7, protocol.parseExitCode(control.readLine()))
        assertTrue(File(root, "marker").exists())
    }

    @Test fun aWrongAcknowledgementNeverRunsTheCommand() = supervise("echo ran > marker") { process, root ->
        process.controlLine()
        process.sendLine("not-the-token")
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(125, process.exitValue())
        assertFalse(File(root, "marker").exists())
    }

    @Test fun commandOutputCannotForgeTheControlChannel() = supervise("echo ${token}DONE:0 >&2; exit 3") { process, _ ->
        val control = process.errorStream.bufferedReader()
        control.readLine()
        process.sendLine(protocol.startAcknowledgement)
        assertEquals(3, protocol.parseExitCode(control.readLine()), "A forged line on the command's stderr must not count")
        process.outputStream.close()
        assertTrue(process.inputStream.bufferedReader().readText().contains("${token}DONE:0"), "It is ordinary output instead")
    }
}
