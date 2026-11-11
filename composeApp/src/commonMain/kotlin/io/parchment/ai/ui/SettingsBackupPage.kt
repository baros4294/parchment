package io.parchment.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.network.OtaReleaseInfo
import io.parchment.ai.network.OtaUpdateEngine
import io.parchment.ai.network.OtaUpdateState
import io.parchment.ai.platform.PlatformBridge
import io.parchment.ai.protocol.CardAndPromptCodec
import kotlinx.coroutines.launch

// ============================================================================================
// Settings page 7: Storage and updates (settings and assistants are stored apart from each chat's file).
// ============================================================================================
@Composable
internal fun BackupAndOtaSubpage(
    appState: AppStateSnapshot,
    activeSession: ChatSession,
    otaState: OtaUpdateState,
    onUpdateOtaSettings: (String, Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onInstallOtaRelease: (OtaReleaseInfo) -> Unit,
    onRestoreFullBackup: (AppStateSnapshot) -> Unit,
    onImportChatSessions: (List<ChatSession>) -> Unit,
    onWipeAllData: () -> Unit,
    onDeleteChatSessionById: (String) -> Unit = {},
    onInspectAttachment: (io.parchment.ai.model.PromptAttachment) -> Unit = {},
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val win = LocalParchmentWindowSize.current
    val scope = rememberCoroutineScope()
    var storageRefreshTick by remember { mutableStateOf(0) }
    var storageCategoryFilter by remember { mutableStateOf<io.parchment.ai.model.StorageFileCategory?>(null) }
    var storageSearchQuery by remember { mutableStateOf("") }

    val allStoredFiles = remember(storageRefreshTick, appState.sessions.size) {
        PlatformBridge.listAppStorageFiles()
    }
    val sessionsByFileStem = remember(appState.sessions) {
        appState.sessions.associateBy { sess ->
            sess.id.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "session" }
        }
    }
    val referencedMediaPaths = remember(appState.sessions) {
        val set = HashSet<String>()
        val rootNorm = PlatformBridge.getAppStorageDirectoryPath().replace('\\', '/').trimEnd('/').lowercase()
        if (appState.preferences.profilePicturePath.isNotBlank()) set.add(appState.preferences.profilePicturePath.replace('\\', '/').lowercase())
        for (sess in appState.sessions) {
            for (node in sess.messages) {
                for (v in node.variants) {
                    for (att in v.attachments) {
                        if (att.localFilePath.isNotBlank()) {
                            val norm = att.localFilePath.replace('\\', '/').lowercase()
                            set.add(norm)
                            if (!norm.startsWith("/") && !norm.contains(":/")) {
                                set.add("$rootNorm/$norm")
                            }
                        }
                    }
                    for (m in v.mediaItems) {
                        if (m.localFilePath.isNotBlank()) {
                            val norm = m.localFilePath.replace('\\', '/').lowercase()
                            set.add(norm)
                            if (!norm.startsWith("/") && !norm.contains(":/")) {
                                set.add("$rootNorm/$norm")
                            }
                        }
                    }
                }
            }
        }
        set
    }

    fun resolveSessionForStoredFile(item: io.parchment.ai.model.StoredAppFileItem): ChatSession? {
        val normRel = item.relativePath.replace('\\', '/')
        if (normRel.startsWith("chats/")) {
            val afterChats = normRel.removePrefix("chats/")
            val folderOrFileStem = if (afterChats.contains('/')) {
                afterChats.substringBefore('/')
            } else {
                afterChats.removeSuffix(".json")
            }
            sessionsByFileStem[folderOrFileStem]?.let { return it }
        }
        if (item.category == io.parchment.ai.model.StorageFileCategory.CHAT_SESSION) {
            return sessionsByFileStem[item.fileName.removeSuffix(".json")]
        }
        return null
    }

    fun isOrphanOrTempFile(item: io.parchment.ai.model.StoredAppFileItem): Boolean {
        if (item.category == io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP) return true
        if (item.category == io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA ||
            item.category == io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA
        ) {
            val normFull = item.fullPath.replace('\\', '/').lowercase()
            val normRel = item.relativePath.replace('\\', '/').lowercase()
            return normFull !in referencedMediaPaths && normRel !in referencedMediaPaths
        }
        return false
    }

    fun formatBytesCompact(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> "${((bytes / (1024.0 * 1024.0)) * 10).toInt() / 10.0} MB"
            bytes >= 1024L -> "${((bytes / 1024.0) * 10).toInt() / 10.0} KB"
            else -> "$bytes B"
        }
    }

        val totalBytes = remember(allStoredFiles) { allStoredFiles.sumOf { it.sizeBytes } }
        val orphanAndTempFiles = remember(allStoredFiles, referencedMediaPaths) {
            allStoredFiles.filter { isOrphanOrTempFile(it) }
        }
        val orphanBytes = remember(orphanAndTempFiles) { orphanAndTempFiles.sumOf { it.sizeBytes } }

    val filteredStorageFiles = remember(allStoredFiles, storageCategoryFilter, storageSearchQuery, sessionsByFileStem) {
        val q = storageSearchQuery.trim().lowercase()
        allStoredFiles.filter { item ->
            val catOk = storageCategoryFilter == null || item.category == storageCategoryFilter
            if (!catOk) return@filter false
            if (q.isEmpty()) return@filter true
            val chatTitle = resolveSessionForStoredFile(item)?.title.orEmpty()
            item.fileName.lowercase().contains(q) ||
                item.relativePath.lowercase().contains(q) ||
                chatTitle.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (win.isCompact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(text = "Storage, backup & updates", style = p.uiHeaderStyle.copy(fontSize = 19.sp))

        // Card 1: storage: inspect and delete chats, uploads, generated media, backups and temporary files.
        SepiaSectionCard(
            title = "Storage & file manager (${allStoredFiles.size} files • ${formatBytesCompact(totalBytes)})",
            subtitle = PlatformBridge.getAppStorageDirectoryPath(),
            headerAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "📂 Open folder",
                        compact = true,
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = { PlatformBridge.openOrPlayMedia(PlatformBridge.getAppStorageDirectoryPath()) }
                    )
                    SepiaButton(
                        text = "↻",
                        contentDescription = "Refresh storage usage",
                        compact = true,
                        style = SepiaButtonStyle.GHOST,
                        onClick = { storageRefreshTick++ }
                    )
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Visual Storage Breakdown Bar by Category
                val catTotals = remember(allStoredFiles) {
                    io.parchment.ai.model.StorageFileCategory.entries.associateWith { cat ->
                        allStoredFiles.filter { it.category == cat }.sumOf { it.sizeBytes }
                    }
                }
                val safeTotal = totalBytes.coerceAtLeast(1L).toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(p.elevatedBg)
                        .border(0.75.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                ) {
                    io.parchment.ai.model.StorageFileCategory.entries.forEach { cat ->
                        val catBytes = catTotals[cat] ?: 0L
                        if (catBytes > 0L) {
                            val col = when (cat) {
                                io.parchment.ai.model.StorageFileCategory.CHAT_SESSION -> p.accent
                                io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA -> Color(0xFF3D6B8E)
                                io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA -> Color(0xFF4A7C59)
                                io.parchment.ai.model.StorageFileCategory.SETTINGS_AND_BACKUPS -> p.accentSecondary
                                io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP -> p.mutedInk
                            }
                            Box(
                                modifier = Modifier
                                    .weight((catBytes.toFloat() / safeTotal).coerceAtLeast(0.02f))
                                    .fillMaxHeight()
                                    .background(col)
                            )
                        }
                    }
                }

                // Category Filter Pills + Orphan Cleanup Action
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SepiaChip(
                        label = "All (${allStoredFiles.size} • ${formatBytesCompact(totalBytes)})",
                        selected = storageCategoryFilter == null,
                        onClick = { storageCategoryFilter = null }
                    )
                    io.parchment.ai.model.StorageFileCategory.entries.forEach { cat ->
                        val count = allStoredFiles.count { it.category == cat }
                        val bytes = catTotals[cat] ?: 0L
                        if (count > 0 || storageCategoryFilter == cat) {
                            SepiaChip(
                                label = "${cat.label} ($count • ${formatBytesCompact(bytes)})",
                                selected = storageCategoryFilter == cat,
                                onClick = {
                                    storageCategoryFilter = if (storageCategoryFilter == cat) null else cat
                                }
                            )
                        }
                    }
                }

                if (orphanAndTempFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "${plural(orphanAndTempFiles.size, "unused media/temp file")} (${formatBytesCompact(orphanBytes)}) can be safely freed.",
                            style = p.uiLabelStyle.copy(color = p.accent)
                        )
                        SepiaButton(
                            text = "🗑 Clean unused (${formatBytesCompact(orphanBytes)})",
                            compact = true,
                            style = SepiaButtonStyle.SECONDARY,
                            onClick = {
                                var deletedCount = 0
                                var freedBytes = 0L
                                orphanAndTempFiles.forEach { item ->
                                    if (PlatformBridge.deleteAppStorageFile(item.fullPath)) {
                                        deletedCount++
                                        freedBytes += item.sizeBytes
                                    }
                                }
                                storageRefreshTick++
                                onNotify("Cleaned ${plural(deletedCount, "unused file")} (${formatBytesCompact(freedBytes)} freed).")
                            }
                        )
                    }
                }

                // Search bar for files
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SepiaTextField(
                        value = storageSearchQuery,
                        onValueChange = { storageSearchQuery = it },
                        placeholder = "🔍 Filter files by name, path, or chat title…",
                        singleLine = true,
                        minHeight = 34.dp,
                        modifier = Modifier.weight(1f)
                    )
                    if (storageSearchQuery.isNotEmpty()) {
                        SepiaButton(
                            text = "×",
                            contentDescription = "Clear search",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { storageSearchQuery = "" }
                        )
                    }
                }

                // Scrollable File List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.sheetBg.copy(alpha = 0.55f))
                        .border(0.75.dp, p.border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filteredStorageFiles.isEmpty()) {
                        BasicText(
                            text = "No matching files in storage.",
                            style = p.uiLabelStyle.copy(color = p.mutedInk),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    filteredStorageFiles.forEach { item ->
                        val matchedSession = resolveSessionForStoredFile(item)
                        val isOrphan = remember(item.fullPath, referencedMediaPaths) { isOrphanOrTempFile(item) }
                        val itemIcon = when (item.category) {
                            io.parchment.ai.model.StorageFileCategory.CHAT_SESSION -> KissIcon.FILE_TEXT
                            io.parchment.ai.model.StorageFileCategory.UPLOADED_MEDIA -> KissIcon.PAPERCLIP
                            io.parchment.ai.model.StorageFileCategory.GENERATED_MEDIA -> KissIcon.IMAGE
                            io.parchment.ai.model.StorageFileCategory.SETTINGS_AND_BACKUPS -> KissIcon.SETTINGS
                            io.parchment.ai.model.StorageFileCategory.OTA_AND_TEMP -> KissIcon.PACKAGE_BOX
                        }
                        val displayTitle = when {
                            item.category == io.parchment.ai.model.StorageFileCategory.CHAT_SESSION && matchedSession != null ->
                                "${matchedSession.title} (${matchedSession.messages.size} msgs)"
                            matchedSession != null ->
                                "${item.fileName} • ${matchedSession.title}"
                            else -> item.fileName
                        }
                        val isPrimarySettingsFile = item.fileName.equals("parchment-settings.json", ignoreCase = true)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(p.cardBg)
                                .border(0.75.dp, p.border.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                KissVectorIcon(icon = itemIcon, tint = p.accent, size = 14.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    BasicText(
                                        text = displayTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    )
                                    val orphanTag = if (isOrphan) " • Unused" else ""
                                    BasicText(
                                        text = "${item.relativePath} • ${formatBytesCompact(item.sizeBytes)} • ${formatCompactTimestamp(item.lastModifiedMs)}$orphanTag",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.monoStyle.copy(
                                            fontSize = 11.sp,
                                            color = if (isOrphan) p.accentSecondary else p.mutedInk
                                        )
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SepiaButton(
                                    text = "👁",
                                    contentDescription = "Preview file",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = {
                                        val lower = item.fileName.lowercase()
                                        val mime = when {
                                            lower.endsWith(".png") -> "image/png"
                                            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                                            lower.endsWith(".webp") -> "image/webp"
                                            lower.endsWith(".gif") -> "image/gif"
                                            lower.endsWith(".svg") -> "image/svg+xml"
                                            lower.endsWith(".mp4") -> "video/mp4"
                                            lower.endsWith(".pdf") -> "application/pdf"
                                            lower.endsWith(".json") -> "application/json"
                                            else -> "text/plain"
                                        }
                                        onInspectAttachment(
                                            io.parchment.ai.model.PromptAttachment(
                                                id = "storage-${item.fileName}",
                                                fileName = item.fileName,
                                                mimeType = mime,
                                                localFilePath = item.fullPath
                                            )
                                        )
                                    }
                                )
                                SepiaButton(
                                    text = "📂",
                                    contentDescription = "Open file",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { PlatformBridge.openOrPlayMedia(item.fullPath) }
                                )
                                if (!isPrimarySettingsFile) {
                                    SepiaButton(
                                        text = "🗑",
                                        contentDescription = "Delete file",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = {
                                            if (matchedSession != null) {
                                                onDeleteChatSessionById(matchedSession.id)
                                                storageRefreshTick++
                                                onNotify("Deleted chat '${matchedSession.title}' (${formatBytesCompact(item.sizeBytes)} freed).")
                                            } else if (PlatformBridge.deleteAppStorageFile(item.fullPath)) {
                                                storageRefreshTick++
                                                onNotify("Deleted '${item.fileName}' (${formatBytesCompact(item.sizeBytes)} freed).")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SepiaSectionCard(
            title = "App updates",
            subtitle = "v${OtaUpdateEngine.currentAppVersion} • ${PlatformBridge.platformName}"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SepiaTextField(
                    value = appState.otaGithubRepo,
                    onValueChange = { repo -> onUpdateOtaSettings(repo, appState.autoCheckOtaOnStartup) },
                    label = "GitHub release repository (owner/repo)",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SepiaToggleRow(
                    title = "Check for updates at launch",
                    checked = appState.autoCheckOtaOnStartup,
                    onCheckedChange = { onUpdateOtaSettings(appState.otaGithubRepo, it) }
                )
                SepiaButton(
                    text = if (otaState is OtaUpdateState.Checking) "Checking…" else "↻ Check for update now",
                    style = SepiaButtonStyle.PRIMARY,
                    onClick = onCheckOtaNow
                )
                when (otaState) {
                    is OtaUpdateState.Available -> {
                        val rel = otaState.release
                        val sizeMb = (rel.assetSizeBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        BasicText(
                            text = "New Release Available: ${rel.tagName} (${rel.assetName} • $sizeMb MB)",
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                        SepiaButton(
                            text = "⬇ Download & Install ${rel.tagName} In-App",
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = { onInstallOtaRelease(rel) }
                        )
                    }
                    is OtaUpdateState.UpToDate -> {
                        BasicText(
                            text = "You are on the latest version (v${otaState.currentVersion}).",
                            style = p.uiBodyStyle.copy(color = p.accentSecondary)
                        )
                        otaState.latestRelease?.let { rel ->
                            SepiaButton(
                                text = "Force Re-Install ${rel.tagName}",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { onInstallOtaRelease(rel) }
                            )
                        }
                    }
                    is OtaUpdateState.Downloading -> {
                        val downMb = (otaState.downloadedBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        val totalMb = (otaState.totalBytes / (1024.0 * 1024.0) * 10).toInt() / 10.0
                        BasicText(
                            text = "Downloading ${otaState.release.assetName}: $downMb MB / $totalMb MB (${otaState.progressPercent}%)…",
                            style = p.uiBodyStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(p.border.copy(alpha = 0.45f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((otaState.progressPercent / 100f).coerceIn(0.02f, 1f))
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(p.accent)
                            )
                        }
                    }
                    is OtaUpdateState.Installing -> {
                        BasicText(text = otaState.message, style = p.uiBodyStyle.copy(color = p.accentSecondary))
                    }
                    is OtaUpdateState.Error -> {
                        BasicText(text = "Update failed: ${otaState.message}", style = p.uiBodyStyle.copy(color = p.danger))
                    }
                    else -> {}
                }
            }
        }

        // Card 3: Self-Contained Per-Chat Folder Export & Import (chats/<sessionId>/chat.json + media/)
        SepiaSectionCard(
            title = "Portable chats & media export / import (Per-Chat folder & .zip bundles)",
            subtitle = PlatformBridge.getChatsStorageDirectoryPath()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "📦 Export All Chats + Media (${appState.sessions.size} Chats .zip)",
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            scope.launch {
                                val saved = runCatching {
                                    exportChatSessionsBundle("parchment-chats-backup.zip", appState.sessions)
                                }.getOrElse { error ->
                                    onNotify("Chat export failed: ${error.message}")
                                    return@launch
                                }
                                if (saved != null) {
                                    onNotify("Exported ${plural(appState.sessions.size, "chat")} + media to $saved")
                                }
                            }
                        }
                    )
                    SepiaButton(
                        text = "📦 Export current chat (.chat.zip + media)",
                        style = SepiaButtonStyle.OUTLINE,
                        onClick = {
                            scope.launch {
                                val zipName = buildExportZipFileNameForChat(activeSession)
                                val saved = runCatching { exportChatSessionsBundle(zipName, listOf(activeSession)) }.getOrElse { error ->
                                    onNotify("Chat export failed: ${error.message}")
                                    return@launch
                                }
                                if (saved != null) onNotify("Exported the chat with its media to $saved")
                            }
                        }
                    )
                    SepiaButton(
                        text = "📤 Export current chat (text only)",
                        style = SepiaButtonStyle.GHOST,
                        onClick = {
                            scope.launch {
                                val fileName = buildExportFileNameForChat(activeSession)
                                val bytes = runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        encodeChatSessionJsonForExport(activeSession)
                                    }
                                }.getOrElse { error ->
                                    onNotify("Chat export failed: ${error.message}")
                                    return@launch
                                }
                                val saved = PlatformBridge.saveFileForExport(fileName, bytes)
                                if (saved != null) onNotify("Exported the chat to $saved")
                            }
                        }
                    )
                    SepiaButton(
                        text = "📥 Import chats (.zip / .json)",
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            scope.launch {
                                val outcomes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    pickAndDecodeChatImports()
                                }
                                if (outcomes.isEmpty()) return@launch
                                val importFailures = outcomes.mapNotNull { outcome ->
                                    outcome.result.exceptionOrNull()?.let { "${outcome.fileName}: ${it.message.orEmpty().take(160)}" }
                                }
                                val importedChats = outcomes.flatMap { it.result.getOrDefault(emptyList()) }
                                if (importedChats.isNotEmpty()) onImportChatSessions(importedChats)
                                when {
                                    importFailures.isNotEmpty() -> onNotify("Import failed for ${importFailures.joinToString("; ")}")
                                    importedChats.isEmpty() -> onNotify("No chats were found in the selected files.")
                                }
                            }
                        }
                    )
                }
            }
        }

        // Card 4: back up settings, providers, assistants and system prompts, separately from chats.
        SepiaSectionCard(
            title = "Settings, providers & assistants backup (.json)",
            subtitle = "${PlatformBridge.getAppStorageDirectoryPath()}/parchment-settings.json"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = "📤 Export settings & assistants (.json)",
                        style = SepiaButtonStyle.PRIMARY,
                        onClick = {
                            scope.launch {
                                val settingsOnly = appState.copy(sessions = emptyList())
                                val bytes = CardAndPromptCodec.prettyJson.encodeToString(AppStateSnapshot.serializer(), settingsOnly).encodeToByteArray()
                                val saved = PlatformBridge.saveFileForExport("parchment-settings.json", bytes)
                                if (saved != null) onNotify("Exported settings & assistants to $saved")
                            }
                        }
                    )
                    SepiaButton(
                        text = "📥 Import settings & assistants (.json)",
                        style = SepiaButtonStyle.SECONDARY,
                        onClick = {
                            scope.launch {
                                val picked = PlatformBridge.pickFileForImport(listOf("json")) ?: return@launch
                                if (picked.oversized) {
                                    onNotify("${picked.fileName} is too large to import.")
                                    return@launch
                                }
                                runCatching {
                                    CardAndPromptCodec.prettyJson.decodeFromString<AppStateSnapshot>(picked.bytes.decodeToString())
                                }.fold(
                                    onSuccess = { restored -> onRestoreFullBackup(restored) },
                                    onFailure = { err -> onNotify("Settings import failed: ${err.message}") }
                                )
                            }
                        }
                    )
                    SepiaButton(
                        text = "Wipe all local data & reset",
                        style = SepiaButtonStyle.DANGER,
                        onClick = onWipeAllData
                    )
                }
            }
        }
    }
}
