package io.parchment.ai.runtime

import io.parchment.ai.harness.*
import io.parchment.ai.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class RunActivityTest {
    @Test fun overlappingToolAndApprovalWaitsAreSubtractedOnce() {
        var now = 0L
        val holds = mutableListOf<Boolean>()
        val activity = RunActivity({ now }, { holds.add(it) })
        now = 100
        val batch = activity.beginTools(2)
        val (first, second) = batch.branches
        now = 200
        activity.setWaiting(first, true)
        now = 300
        activity.setWaiting(second, true)
        now = 1_300
        assertEquals(300L, activity.elapsedMs)
        assertEquals(200L, activity.toolMs)
        assertEquals(listOf(false), holds)
        activity.setWaiting(first, false)
        now = 1_500
        activity.completeBranch(first)
        assertEquals(listOf(false, true, false), holds)
        now = 2_500
        activity.setWaiting(second, false)
        now = 2_600
        activity.completeBranch(second)
        batch.close()
        now = 2_800
        assertEquals(800L, activity.elapsedMs)
        assertEquals(500L, activity.toolMs)
        assertEquals(300L, activity.elapsedMs - activity.toolMs)
        assertEquals(listOf(false, true, false, true), holds)
    }

    @Test fun clockRollbackAndUnobservedSuspensionCannotInflateDurations() {
        var now = 10_000L
        val activity = RunActivity({ now })
        now += 100
        assertEquals(100L, activity.elapsedMs)
        now -= 50
        assertEquals(100L, activity.elapsedMs)
        now += 100
        assertEquals(150L, activity.elapsedMs)
        now += 3_600_000
        activity.pulse()
        assertEquals(150L, activity.elapsedMs)
        repeat(10) { now += 1_000; activity.pulse() }
        assertEquals(10_150L, activity.elapsedMs)
    }

    @Test fun twoRunsAndStaleCloseReconcileAbsoluteOwners() {
        var now = 0L
        val snapshots = mutableListOf<Pair<Int, Int>>()
        val leases = RunLeaseRegistry({ owners, active, _ -> snapshots.add(owners to active) }, { now })
        val first = leases.open("one")
        val second = leases.open("two")
        first.activity.setWaiting(0, true)
        assertEquals(2 to 1, snapshots.last())
        second.activity.setWaiting(0, true)
        assertEquals(2 to 0, snapshots.last())
        first.close()
        val replacement = leases.open("replacement")
        assertEquals(2 to 1, snapshots.last())
        first.close()
        first.activity.setWaiting(0, false)
        assertEquals(2 to 1, snapshots.last())
        replacement.close()
        assertEquals(1 to 0, snapshots.last())
        second.close()
        assertEquals(0 to 0, snapshots.last())
    }

    @Test fun approvalDecisionCancellationAndRevocationRestoreAccounting() = runBlocking {
        var now = 0L
        val activity = RunActivity({ now })
        val policy = ToolAccessScope(AgentPermissionPolicy(approvalMode = ToolApprovalMode.ASK_EVERY_TOOL), goalModeActive = true)
        val broker = ToolApprovalBroker("run", "chat", { policy })
        suspend fun pending(call: String) = async(RunActivityContext(activity)) {
            broker.authorize(call, "calc_eval", """{"expression":"$call"}""")
        }
        try {
            now = 100
            val first = pending("1")
            val request = withTimeout(2_000) { broker.requests.first { it.isNotEmpty() }.single() }
            activity.waitingForApproval.first { it }
            now = 1_100
            assertEquals(100L, activity.elapsedMs)
            broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE)
            assertTrue(first.await() is ToolAuthorization.Allowed)
            assertFalse(activity.waitingForApproval.value)
            now = 1_200
            val cancelled = pending("2")
            activity.waitingForApproval.first { it }
            now = 2_200
            cancelled.cancelAndJoin()
            assertFalse(activity.waitingForApproval.value)
            assertTrue(broker.requests.value.isEmpty())
            val revoked = pending("3")
            activity.waitingForApproval.first { it }
            now = 3_200
            broker.invalidate()
            broker.refreshPolicy()
            assertTrue(revoked.await() is ToolAuthorization.Denied)
            assertFalse(activity.waitingForApproval.value)
            assertEquals(200L, activity.elapsedMs)
        } finally { broker.close() }
    }

    @Test fun lazyCancellationAndQueueHandoffDoNotLeakOrDropOwners() = runBlocking {
        val snapshots = mutableListOf<Pair<Int, Int>>()
        val leases = RunLeaseRegistry({ owners, active, _ -> snapshots.add(owners to active) })
        val abandoned = leases.launch(this, "never started", start = CoroutineStart.LAZY) { fail("Lazy run started") }
        abandoned.cancelAndJoin()
        assertEquals(0 to 0, snapshots.last())
        snapshots.clear()
        val releaseNext = CompletableDeferred<Unit>()
        var next: Job? = null
        val first = leases.launch(this, "first") {
            // Compaction/stream restarts keep the same parent context and owner.
            val activity = currentCoroutineContext()[RunActivityContext]?.activity
            withContext(Dispatchers.Default) {
                assertSame(activity, currentCoroutineContext()[RunActivityContext]?.activity)
            }
            next = leases.launch(this@runBlocking, "queued") { releaseNext.await() }
        }
        first.join()
        assertEquals(listOf(1 to 1, 2 to 2, 1 to 1), snapshots)
        releaseNext.complete(Unit)
        requireNotNull(next).join()
        assertEquals(0 to 0, snapshots.last())
    }
}
