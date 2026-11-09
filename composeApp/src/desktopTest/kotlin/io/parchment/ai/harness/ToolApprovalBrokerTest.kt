package io.parchment.ai.harness

import io.parchment.ai.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class ToolApprovalBrokerTest {
    private val policy = ToolAccessScope(AgentPermissionPolicy(
        workspacePath = "unused", workspaceMode = WorkspaceMode.READ_WRITE,
        approvalMode = ToolApprovalMode.ASK_EVERY_TOOL
    ))
    private val arguments = """{"path":"notes.md"}"""

    @Test fun oncePermitMatchesExactActionAndIsConsumed() = runBlocking {
        val broker = ToolApprovalBroker("run", "chat", { policy })
        val waiting = async { broker.authorize("call", "fs_read", arguments) }
        val request = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
        assertEquals("chat", request.chatId)
        assertEquals(arguments, request.argumentsJson)
        assertTrue(broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE))
        val permit = (waiting.await() as ToolAuthorization.Allowed).permit
        assertNotNull(permit.claim(policy, "fs_read", "{}"))
        assertNull(permit.claim(policy, "fs_read", arguments))
        assertNotNull(permit.claim(policy, "fs_read", arguments))
        assertTrue(broker.requests.value.isEmpty())
    }

    @Test fun runGrantIsExactAndRevokedEvenAfterPolicyChangesBack() = runBlocking {
        val broker = ToolApprovalBroker("run", "chat", { policy })
        val first = async { broker.authorize("first", "fs_read", arguments) }
        val request = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
        broker.respond(request.requestId, ToolApprovalDecision.ALLOW_EXACT_ACTION_FOR_RUN)
        val oldPermit = (first.await() as ToolAuthorization.Allowed).permit
        assertTrue(broker.authorize("second", "fs_read", arguments) is ToolAuthorization.Allowed)
        broker.invalidate()
        broker.refreshPolicy()
        assertNotNull(oldPermit.claim(policy, "fs_read", arguments))
        val next = async { broker.authorize("third", "fs_read", arguments) }
        val renewed = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
        broker.respond(renewed.requestId, ToolApprovalDecision.DENY)
        assertTrue(next.await() is ToolAuthorization.Denied)
        assertTrue(broker.authorize("fourth", "fs_read", arguments) is ToolAuthorization.Denied)
        assertTrue(broker.requests.value.isEmpty())
    }

    @Test fun policyAndFolderRevocationInvalidatePendingRequests() = runBlocking {
        var current = policy
        val broker = ToolApprovalBroker("run", "chat", { current })
        val waiting = async { broker.authorize("call", "fs_read", arguments) }
        val request = withTimeout(2000) { broker.requests.first { it.isNotEmpty() }.single() }
        current = policy.copy(policy = policy.policy.copy(workspacePath = "different"))
        assertFalse(broker.respond(request.requestId, ToolApprovalDecision.ALLOW_ONCE))
        assertTrue(waiting.await() is ToolAuthorization.Denied)
        assertTrue(broker.requests.value.isEmpty())
    }

    @Test fun cancellationAndStopDoNotAffectAnotherChat() = runBlocking {
        var stopped = false
        val firstBroker = ToolApprovalBroker("first", "first-chat", { policy }, { stopped = true })
        val secondBroker = ToolApprovalBroker("second", "second-chat", { policy })
        val first = async { firstBroker.authorize("first-call", "fs_read", arguments) }
        val second = async { secondBroker.authorize("second-call", "fs_read", arguments) }
        val firstRequest = withTimeout(2000) { firstBroker.requests.first { it.isNotEmpty() }.single() }
        val secondRequest = withTimeout(2000) { secondBroker.requests.first { it.isNotEmpty() }.single() }
        firstBroker.respond(firstRequest.requestId, ToolApprovalDecision.STOP)
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(stopped)
        assertTrue(firstBroker.requests.value.isEmpty())
        assertFalse(second.isCompleted)
        assertTrue(secondBroker.respond(secondRequest.requestId, ToolApprovalDecision.ALLOW_ONCE))
        assertTrue(second.await() is ToolAuthorization.Allowed)
        assertTrue(firstBroker.authorize("late", "fs_read", arguments) is ToolAuthorization.Denied)
        val cancelling = async { secondBroker.authorize("cancelled", "fs_read", "{}") }
        withTimeout(2000) { secondBroker.requests.first { it.isNotEmpty() } }
        cancelling.cancelAndJoin()
        assertTrue(secondBroker.requests.value.isEmpty())
    }
}
