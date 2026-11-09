package io.parchment.ai.harness

import kotlin.test.*

class OwnedShellProtocolTest {
    @Test fun rejectsBroadOrUnrelatedProcessIdentities() {
        val protocol = OwnedShellProtocol("run-token")
        assertEquals(2345, protocol.parseOwnerPid("run-tokenPID:2345", 1234))
        listOf("0", "1", "-1", "-2345", "1234", "2147483648", "bad").forEach {
            assertFails { protocol.parseOwnerPid("run-tokenPID:$it", 1234) }
        }
        assertFails { protocol.parseOwnerPid("another-tokenPID:2345", 1234) }
        assertFails { OwnedShellProtocol("injected'; command") }
    }

    @Test fun onlyTheOwningSupervisorCanReportAnOutcome() {
        val protocol = OwnedShellProtocol("run-token")
        assertEquals(0, protocol.parseExitCode("run-tokenDONE:0"))
        assertEquals(137, protocol.parseExitCode("run-tokenDONE:137"))
        listOf("-1", "256", "bad").forEach { assertFails { protocol.parseExitCode("run-tokenDONE:$it") } }
        assertFails { protocol.parseExitCode("model output DONE:0") }
    }
}
