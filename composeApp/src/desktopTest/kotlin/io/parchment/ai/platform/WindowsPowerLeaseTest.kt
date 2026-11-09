package io.parchment.ai.platform

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsPowerLeaseTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun absoluteSnapshotsReleaseApprovalOnlyHoldsAndCanResume() {
        if (!isWindows) return
        val processes = CopyOnWriteArrayList<Process>()
        WindowsPowerLease(launchHelper = { script -> launchWindowsPowerHelper(script).also { processes.add(it) } }).use { lease ->
            lease.reconcile(2)
            await("Native hold was not acquired.") { lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE }
            lease.reconcile(1)
            assertEquals(1, lease.snapshot.owners)
            assertEquals(1, processes.size)
            lease.reconcile(0)
            assertEquals(WindowsPowerLeaseStatus.IDLE, lease.snapshot.status)
            await("Approval-only wait retained a native hold.") { processes.none { it.isAlive } }
            lease.reconcile(1)
            await("Native hold did not resume.") { lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE }
            assertEquals(1, lease.snapshot.owners)
        }
        await("Final cleanup retained a helper.") { processes.none { it.isAlive } }
    }

    @Test
    fun nativeHeartbeatConfirmsUnsignedPowerRequest() {
        if (!isWindows) return
        val processes = CopyOnWriteArrayList<Process>()
        WindowsPowerLease(launchHelper = { script ->
            launchWindowsPowerHelper(script).also { processes.add(it) }
        }).use { lease ->
            lease.acquire()
            await("Win32 sleep inhibitor was not acquired.") {
                lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE
            }
            assertEquals(1, lease.snapshot.owners)
            lease.release()
            assertEquals(WindowsPowerLeaseStatus.IDLE, lease.snapshot.status)
            await("Power helper survived the final release.") { processes.none { it.isAlive } }
        }
    }

    @Test
    fun completingOneOwnerKeepsTheOtherOwnerProtected() {
        if (!isWindows) return
        val processes = CopyOnWriteArrayList<Process>()
        WindowsPowerLease(launchHelper = { script ->
            launchWindowsPowerHelper(script).also { processes.add(it) }
        }).use { lease ->
            lease.acquire()
            lease.acquire()
            await("Native power request failed.") { lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE }
            lease.release()
            assertEquals(1, lease.snapshot.owners)
            assertEquals(WindowsPowerLeaseStatus.ACTIVE, lease.snapshot.status)
            assertEquals(1, processes.size)
            assertTrue(processes.single().isAlive)
            lease.release()
            await("Final owner did not release the helper.") { processes.none { it.isAlive } }
            lease.release()
            assertEquals(0, lease.snapshot.owners)
        }
    }

    @Test
    fun failedHelperRecoversWithoutTokenProgress() {
        if (!isWindows) return
        val attempts = AtomicInteger()
        val processes = CopyOnWriteArrayList<Process>()
        WindowsPowerLease(launchHelper = { script ->
            val process = if (attempts.incrementAndGet() == 1) {
                val executable = File(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
                ProcessBuilder(executable.absolutePath, "-NoProfile", "-NonInteractive", "-Command", "exit 1")
                    .redirectErrorStream(true).start()
            } else {
                launchWindowsPowerHelper(script)
            }
            process.also { processes.add(it) }
        }).use { lease ->
            lease.acquire()
            // No refresh/progress call: the monitor must recover a quiet run.
            await("The failed power helper was not recovered.", 25) {
                lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE
            }
            assertTrue(attempts.get() >= 2)
            lease.release()
            await("Recovered helper was not cleaned up.") { processes.none { it.isAlive } }
        }
    }

    @Test
    fun concurrentOwnersShareOneHolderAndReleaseWithoutRaces() {
        if (!isWindows) return
        val processes = CopyOnWriteArrayList<Process>()
        val executor = Executors.newFixedThreadPool(4)
        try {
            WindowsPowerLease(launchHelper = { script ->
                launchWindowsPowerHelper(script).also { processes.add(it) }
            }).use { lease ->
                lease.acquire()
                await("Native power request failed.") { lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE }
                val tasks = (1..4).map {
                    executor.submit {
                        repeat(100) {
                            lease.acquire()
                            lease.refresh()
                            lease.release()
                        }
                    }
                }
                tasks.forEach { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, lease.snapshot.owners)
                assertEquals(1, processes.size)
                assertTrue(processes.single().isAlive)
                lease.release()
                await("Concurrent owner cleanup left a power helper.") { processes.none { it.isAlive } }
                assertEquals(WindowsPowerLeaseStatus.IDLE, lease.snapshot.status)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun arbitraryNanoTimeOriginDoesNotDelayFirstAcquisition() {
        if (!isWindows) return
        WindowsPowerLease(nanoTime = { -TimeUnit.SECONDS.toNanos(100) }).use { lease ->
            lease.acquire()
            await("A negative monotonic-clock origin prevented startup.") {
                lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE
            }
        }
    }

    @Test
    fun externallyTerminatedHolderRecoversItsExistingOwners() {
        if (!isWindows) return
        val processes = CopyOnWriteArrayList<Process>()
        WindowsPowerLease(launchHelper = { script ->
            launchWindowsPowerHelper(script).also { processes.add(it) }
        }).use { lease ->
            lease.acquire()
            lease.acquire()
            await("Native power request failed.") { lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE }
            processes.single().destroyForcibly()
            await("The terminated helper was not replaced.", 25) {
                processes.size >= 2 && lease.snapshot.status == WindowsPowerLeaseStatus.ACTIVE
            }
            assertEquals(2, lease.snapshot.owners)
            lease.release()
            assertTrue(processes.last().isAlive)
            lease.release()
            await("Replacement power helper survived final release.") { processes.none { it.isAlive } }
        }
    }

    @Test
    fun helperStopsAndReleasesWhenItsParentExits() {
        if (!isWindows) return
        val parent = launchWindowsPowerHelper("Start-Sleep -Seconds 60")
        val helper = launchWindowsPowerHelper(buildWindowsPowerHelperScript(parent.pid()))
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val firstLine = readerExecutor.submit<String> {
                helper.inputStream.bufferedReader().readLine()
            }.get(15, TimeUnit.SECONDS)
            assertEquals(POWER_HEARTBEAT, firstLine)
            parent.destroyForcibly()
            assertTrue(helper.waitFor(20, TimeUnit.SECONDS), "Power helper outlived its parent.")
            assertEquals(0, helper.exitValue(), "Native request release failed.")
        } finally {
            helper.destroyForcibly()
            parent.destroyForcibly()
            readerExecutor.shutdownNow()
        }
    }

    private fun await(message: String, seconds: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() - deadline < 0) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition(), message)
    }
}
