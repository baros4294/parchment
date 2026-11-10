package io.parchment.ai.network

import io.parchment.ai.harness.AgentToolRunContext
import io.parchment.ai.harness.ToolAccessScope
import io.parchment.ai.harness.ToolApprovalBroker
import io.parchment.ai.harness.WorkspaceHarness
import io.parchment.ai.model.AgentPermissionPolicy
import io.parchment.ai.model.ApiUsageRecord
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.MessageNode
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.MessageVariant
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ShellAccess
import io.parchment.ai.model.ToolApprovalMode
import io.parchment.ai.model.WorkspaceMode
import io.parchment.ai.model.asOverrides
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

internal const val SUBAGENT_PURPOSE = "subagent"
private const val SUBAGENT_TIMEOUT_MS = 600_000L
private const val SUBAGENT_MAX_RESULT_CHARS = 32_000
private const val SUBAGENT_INSTRUCTIONS = "You are a focused helper working on one task for another agent. " +
    "Use your tools to find what is needed. You cannot change anything. " +
    "Reply with only your findings, including file paths and exact details the other agent will need."
private val SUBAGENT_FILE_TOOLS = setOf("fs_read", "fs_list", "fs_glob", "fs_search", "skill_load", "calc_eval")
private val SUBAGENT_WEB_TOOLS = setOf("web_search", "web_fetch")
/** Run on the provider's side, so they are not in the local tool list. */
private val PROVIDER_SIDE_TOOLS = listOf("code_execution", "image_generation", "video_generation")

/**
 * A helper can only read. It cannot ask for approval because nobody is watching its cards, so web
 * use needs a parent that already runs without asking, and every other tool is switched off.
 */
internal fun subagentPolicy(parent: AgentPermissionPolicy): AgentPermissionPolicy {
    val web = parent.webAccess && parent.approvalMode == ToolApprovalMode.NEVER
    val allowed = SUBAGENT_FILE_TOOLS + if (web) SUBAGENT_WEB_TOOLS else emptySet()
    return parent.copy(
        workspaceMode = if (parent.hasWorkspace) WorkspaceMode.READ_ONLY else parent.workspaceMode,
        shellAccess = ShellAccess.DISABLED,
        webAccess = web,
        approvalMode = ToolApprovalMode.NEVER,
        computerAccess = false,
        toolOverrides = parent.toolOverrides + (WorkspaceHarness.allTools().map { it.name } + PROVIDER_SIDE_TOOLS).filter { it !in allowed }.associateWith { false }
    )
}

internal suspend fun AiProviderEngine.runSubagent(
    profile: ProviderProfile,
    parentSession: ChatSession,
    parent: ToolApprovalBroker,
    number: Int,
    task: String,
    spendLimitUsd: Double,
    spentUsd: Double,
    onProgress: suspend (String) -> Unit,
    onUsage: suspend (ApiUsageRecord) -> Unit
): Result<String> {
    val parentPolicy = parent.currentScope()?.policy ?: return Result.failure(IllegalStateException("This run is no longer active."))
    val policy = subagentPolicy(parentPolicy)
    // Follows the parent, so stopping the run or revoking its access also ends the helper.
    val broker = ToolApprovalBroker("${parent.runId}:helper-$number", parentSession.id, {
        parent.currentScope()?.let { ToolAccessScope(subagentPolicy(it.policy)) }
    })
    val session = ChatSession(
        "${parentSession.id}:helper-$number", "Helper", parentSession.createdAt, parentSession.updatedAt, parentSession.profileId,
        modelOverride = parentSession.modelOverride, systemPrompt = SUBAGENT_INSTRUCTIONS,
        workspacePath = policy.workspacePath, workspaceMode = policy.workspaceMode, permissionOverrides = policy.asOverrides()
    )
    var answer: String? = null
    var failure: String? = null
    try {
        withTimeoutOrNull(SUBAGENT_TIMEOUT_MS) {
            // flowOn keeps collection on the caller, so usage can be re-emitted from the parent's flow.
            streamGeneration(
                profile = profile, session = session,
                historyNodes = listOf(MessageNode("task", MessageRole.USER, listOf(MessageVariant("task-v", content = task)))),
                spendLimitUsd = spendLimitUsd, spendBeforeRunUsd = spentUsd,
                usageOwnerSessionId = parentSession.id, usageOwnerRunId = parent.runId, usagePurpose = SUBAGENT_PURPOSE
            ).flowOn(AgentToolRunContext(broker)).collect { update ->
                when (update) {
                    is StreamUpdate.Status -> onProgress(update.message)
                    is StreamUpdate.UsageUpdated -> onUsage(update.record)
                    is StreamUpdate.Completed -> answer = update.fullContent
                    is StreamUpdate.Error -> failure = update.message
                    else -> Unit
                }
            }
        } ?: return Result.failure(IllegalStateException("The helper ran out of time."))
    } finally {
        broker.close()
    }
    failure?.let { return Result.failure(IllegalStateException(it)) }
    return Result.success(answer?.takeIf { it.isNotBlank() }?.take(SUBAGENT_MAX_RESULT_CHARS) ?: "The helper finished without a reply.")
}
