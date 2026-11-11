package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.parchment.ai.harness.McpRegistry
import io.parchment.ai.harness.McpServerState
import io.parchment.ai.model.McpServerConfig
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.network.RegistryServer
import io.parchment.ai.network.installSkillFromGitHub
import io.parchment.ai.network.parseGitHubFolder
import io.parchment.ai.network.searchMcpRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps connections matching the saved servers; the pause stops a connection per keystroke. */
@Composable
internal fun McpServerConnections(servers: List<McpServerConfig>) {
    val enabled = remember(servers) { servers.filter { it.enabled } }
    LaunchedEffect(enabled) {
        delay(600)
        McpRegistry.sync(enabled)
    }
}

/** Added servers start switched off, because the registry checks who owns a name, not what a server does. */
@Composable
private fun McpServerBrowser(prefs: PreferencesConfig, onUpdatePreferences: (PreferencesConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RegistryServer>>(emptyList()) }
    var nextCursor by remember { mutableStateOf("") }
    var loadingMore by remember { mutableStateOf(false) }
    var tokenFreeOnly by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search the public MCP directory.") }
    LaunchedEffect(query) {
        val term = query.trim()
        nextCursor = ""
        if (term.length < 2) {
            results = emptyList()
            message = "Search the public MCP directory."
            return@LaunchedEffect
        }
        delay(400)
        message = "Searching…"
        searchMcpRegistry(term).fold(
            { page ->
                results = page.servers
                nextCursor = page.nextCursor
                message = if (page.servers.isEmpty()) "No servers you can add directly were found." else ""
            },
            { error ->
                if (error is CancellationException) throw error
                results = emptyList()
                message = "The directory could not be reached. Try again later."
            }
        )
    }
    val shown = if (tokenFreeOnly) results.filter { !it.needsToken } else results
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Find a server", style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold))
        BasicText("Anyone can publish to this directory. Check who made a server before you switch it on.", style = p.uiLabelStyle)
        SepiaTextField(query, { query = it }, placeholder = "Search, for example github", modifier = Modifier.fillMaxWidth())
        if (results.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaChip("No token needed", selected = tokenFreeOnly, onClick = { tokenFreeOnly = !tokenFreeOnly })
                BasicText(plural(shown.size, "server"), style = p.uiLabelStyle)
            }
        }
        if (message.isNotEmpty()) BasicText(message, style = p.uiLabelStyle)
        if (shown.isNotEmpty() || nextCursor.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.cardBg)
                    .border(0.75.dp, p.border.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                shown.forEachIndexed { index, server ->
                    if (index > 0) SepiaDivider()
                    McpRegistryRow(server, added = prefs.mcpServers.any { it.url == server.url }) {
                        val entry = McpServerConfig("mcp-${PlatformBridge.currentTimeMillis()}", server.title.ifBlank { server.name.substringAfterLast('/') }, url = server.url)
                        onUpdatePreferences(prefs.copy(mcpServers = prefs.mcpServers + entry))
                    }
                }
                if (nextCursor.isNotEmpty()) {
                    SepiaButton(if (loadingMore) "Loading…" else "Show more", compact = true, style = SepiaButtonStyle.GHOST, enabled = !loadingMore, modifier = Modifier.padding(vertical = 6.dp), onClick = {
                        val term = query.trim()
                        val cursor = nextCursor
                        loadingMore = true
                        scope.launch {
                            searchMcpRegistry(term, cursor).fold(
                                { page ->
                                    if (query.trim() == term) {
                                        results = (results + page.servers).distinctBy { it.name }
                                        nextCursor = page.nextCursor
                                    }
                                },
                                { error ->
                                    if (error is CancellationException) throw error
                                    message = "More servers could not be loaded. Try again."
                                }
                            )
                            loadingMore = false
                        }
                    })
                }
            }
        }
    }
}

/** One line for the name, one muted line for the publisher and token need, two lines of description. */
@Composable
private fun McpRegistryRow(server: RegistryServer, added: Boolean, onAdd: () -> Unit) {
    val p = LocalParchmentPalette.current
    val title = server.title.ifBlank { server.name.substringAfterLast('/') }
    val details = listOfNotNull(server.name.takeIf { it != title }, "Needs a token".takeIf { server.needsToken }).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details.isNotEmpty()) BasicText(details, style = p.uiLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (server.description.isNotBlank()) BasicText(server.description, style = p.uiLabelStyle.copy(color = p.primaryInk), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SepiaButton(if (added) "Added" else "Add", compact = true, enabled = !added, onClick = onAdd)
    }
}

@Composable
internal fun SkillInstallCard(workspacePath: String, canWrite: Boolean, onNotify: (String) -> Unit) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SepiaSectionCard(title = "Install a skill", subtitle = "Skills teach the agent how to do a job. Paste a GitHub link to one.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SepiaTextField(link, { link = it }, placeholder = "https://github.com/owner/repo/tree/main/skill", modifier = Modifier.fillMaxWidth())
            BasicText(
                if (workspacePath.isNotBlank() && canWrite) "It is copied into this workspace. Read a skill before you install it: its instructions guide the agent."
                else "Choose a folder with write access first.",
                style = p.uiLabelStyle
            )
            SepiaButton(if (busy) "Installing…" else "Install", compact = true, enabled = !busy && canWrite && workspacePath.isNotBlank() && link.isNotBlank(), onClick = {
                val folder = parseGitHubFolder(link)
                if (folder == null) {
                    onNotify("That is not a GitHub repository or folder link.")
                    return@SepiaButton
                }
                busy = true
                scope.launch {
                    val result = installSkillFromGitHub(
                        folder,
                        exists = { path -> withContext(Dispatchers.IO) { PlatformBridge.workspaceRead(workspacePath, path, 1, 1).isSuccess } },
                        write = { path, text -> withContext(Dispatchers.IO) { PlatformBridge.workspaceWrite(workspacePath, path, text) } },
                        fetch = { url, limit -> withContext(Dispatchers.IO) { PlatformBridge.fetchPublicBytes(url, limit).getOrThrow() } }
                    )
                    busy = false
                    result.fold({ onNotify(it); link = "" }, { onNotify(it.message ?: "The skill could not be installed.") })
                }
            })
        }
    }
}

@Composable
internal fun McpServersCard(prefs: PreferencesConfig, onUpdatePreferences: (PreferencesConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    val statuses by McpRegistry.statuses.collectAsState()
    val localAllowed = remember { PlatformBridge.supportsLocalMcpServers }
    fun update(server: McpServerConfig, change: (McpServerConfig) -> McpServerConfig) {
        onUpdatePreferences(prefs.copy(mcpServers = prefs.mcpServers.map { if (it.id == server.id) change(it) else it }))
    }
    SepiaSectionCard(title = "MCP servers", subtitle = "Give the agent tools from other apps. Each call follows your approval setting.") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            prefs.mcpServers.forEach { server ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaToggleSwitch(checked = server.enabled, onCheckedChange = { on -> update(server) { it.copy(enabled = on) } }, label = server.name.ifBlank { "New server" })
                    SepiaTextField(server.name, { text -> update(server) { it.copy(name = text) } }, placeholder = "Name", modifier = Modifier.fillMaxWidth())
                    SepiaTextField(server.url, { text -> update(server) { it.copy(url = text) } }, placeholder = "Server URL", modifier = Modifier.fillMaxWidth())
                    if (server.url.isNotBlank()) {
                        SepiaTextField(server.bearerToken, { text -> update(server) { it.copy(bearerToken = text) } }, placeholder = "Access token (optional)", modifier = Modifier.fillMaxWidth())
                    } else if (localAllowed) {
                        SepiaTextField(server.command, { text -> update(server) { it.copy(command = text) } }, placeholder = "Or a local command", monospace = true, modifier = Modifier.fillMaxWidth())
                        if (server.command.isNotBlank()) BasicText("A local command runs with your full account access. Add only servers you trust.", style = p.uiLabelStyle)
                    }
                    statuses[server.id]?.let { status ->
                        BasicText(when (status.state) {
                            McpServerState.CONNECTING -> "Connecting…"
                            McpServerState.READY -> "Connected, ${plural(status.toolCount, "tool")}"
                            McpServerState.FAILED -> status.message
                        }, style = p.uiLabelStyle)
                    }
                    SepiaButton("Remove", compact = true, style = SepiaButtonStyle.GHOST, onClick = {
                        onUpdatePreferences(prefs.copy(mcpServers = prefs.mcpServers.filter { it.id != server.id }))
                    })
                    SepiaDivider()
                }
            }
            SepiaButton("Add server", compact = true, onClick = {
                onUpdatePreferences(prefs.copy(mcpServers = prefs.mcpServers + McpServerConfig("mcp-${PlatformBridge.currentTimeMillis()}")))
            })
            SepiaDivider()
            McpServerBrowser(prefs, onUpdatePreferences)
        }
    }
}
