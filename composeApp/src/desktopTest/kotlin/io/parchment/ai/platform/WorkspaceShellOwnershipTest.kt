package io.parchment.ai.platform

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class WorkspaceShellOwnershipTest {
    @Test fun cancellationStopsOnlyTheOwningCommand() {
        val root = Files.createTempDirectory("parchment-shell-owner-")
        val executor = Executors.newFixedThreadPool(2)
        val cancelled = AtomicBoolean(false)
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        val firstCommand = if (windows) "Write-Output FIRST_STARTED; Start-Sleep -Seconds 20; Write-Output FIRST_FINISHED" else "echo FIRST_STARTED; sleep 20; echo FIRST_FINISHED"
        val secondCommand = if (windows) "Write-Output SECOND_STARTED; Start-Sleep -Seconds 2; Write-Output SECOND_FINISHED" else "echo SECOND_STARTED; sleep 2; echo SECOND_FINISHED"
        try {
            val first = executor.submit<Result<String>> {
                PlatformWorkspace.workspaceShellExec(root.toString(), firstCommand, 30, { if ("FIRST_STARTED" in it) firstStarted.countDown() }, { cancelled.get() })
            }
            assertTrue(firstStarted.await(8, TimeUnit.SECONDS), "First command did not start")
            val second = executor.submit<Result<String>> {
                PlatformWorkspace.workspaceShellExec(root.toString(), secondCommand, 10, { if ("SECOND_STARTED" in it) secondStarted.countDown() })
            }
            assertTrue(secondStarted.await(8, TimeUnit.SECONDS), "Second command did not start")
            cancelled.set(true)
            assertTrue(first.get(5, TimeUnit.SECONDS).isFailure)
            val surviving = second.get(5, TimeUnit.SECONDS)
            assertTrue(surviving.isSuccess, surviving.exceptionOrNull()?.message)
            assertContains(surviving.getOrThrow(), "SECOND_FINISHED")
        } finally {
            cancelled.set(true)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            Files.delete(root)
        }
    }
}
