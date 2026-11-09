package io.parchment.ai.harness

/** Private supervisor channel. Model output uses a different pipe and cannot be parsed as control. */
internal class OwnedShellProtocol(val token: String) {
    init { require(token.isNotEmpty() && token.all { it.isLetterOrDigit() || it == '-' }) }
    val startAcknowledgement: String get() = "${token}START"
    fun supervisorScript(command: String): String = """
        printf '${token}PID:%s\n' "${'$'}${'$'}" >&2
        IFS= read -r acknowledgement || exit 125
        [ "${'$'}acknowledgement" = '$startAcknowledgement' ] || exit 125
        (
        $command
        ) </dev/null 2>&1
        command_status=${'$'}?
        printf '${token}DONE:%s\n' "${'$'}command_status" >&2
        IFS= read -r cleanup_acknowledgement
        exit "${'$'}command_status"
    """.trimIndent()

    fun parseOwnerPid(line: String, appPid: Int): Int {
        require(line.startsWith("${token}PID:")) { "Owned Shell session did not establish its process identity." }
        val pid = line.removePrefix("${token}PID:").toIntOrNull()
        require(pid != null && pid > 1 && pid != appPid) { "Owned Shell session returned an invalid process identity." }
        return pid
    }

    fun parseExitCode(line: String): Int {
        require(line.startsWith("${token}DONE:")) { "Owned Shell session did not report a valid outcome." }
        val code = line.removePrefix("${token}DONE:").toIntOrNull()
        require(code != null && code in 0..255) { "Owned Shell session returned an invalid exit status." }
        return code
    }
}
