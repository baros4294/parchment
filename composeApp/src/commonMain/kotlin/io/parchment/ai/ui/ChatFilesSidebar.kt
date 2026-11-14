package io.parchment.ai.ui

import androidx.compose.foundation.background
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.GeneratedMediaItem
import io.parchment.ai.model.MediaKind
import io.parchment.ai.model.MessageRole
import io.parchment.ai.model.PromptAttachment
import io.parchment.ai.model.WebSourceCitation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.parchment.ai.platform.PlatformBridge

// ============================================================================================
// Right sidebar: the chat's files, generated media and sources (`ParchmentRightFilesSidebar`).
// Categorizes every item in the active chat into:
// 1. `✦ AI-Generated Files & Media` (Workspace writes/edits, generated images/videos, SVG/code artifacts)
// 2. `↑ User-Uploaded Documents & Files` (Attached PDFs, images, code/text files, video/audio)
// 3. `🌐 Grounded Web Sources` (Internet search citations with explicit line-number & quote pointers)
// ============================================================================================
internal enum class RightVaultFilter(val label: String) {
    ALL(Strings.all3),
    AI_GENERATED("✦ AI Gen"),
    USER_UPLOADED(Strings.uploaded2),
    WEB_SOURCES(Strings.sources2)
}

internal sealed class AiGeneratedChatArtifact {
    abstract val turnNumber: Int

    data class WorkspaceFileArtifact(
        override val turnNumber: Int,
        val filePath: String,
        val actionBadge: String,
        val content: String,
        val lineCount: Int
    ) : AiGeneratedChatArtifact()

    data class MediaArtifact(
        override val turnNumber: Int,
        val media: GeneratedMediaItem
    ) : AiGeneratedChatArtifact()

    data class CodeSnippetArtifact(
        override val turnNumber: Int,
        val fileName: String,
        val language: String,
        val code: String,
        val lineCount: Int
    ) : AiGeneratedChatArtifact()
}

internal data class UserUploadedChatArtifact(
    val turnNumber: Int, // 0 = currently staged in composer
    val attachment: PromptAttachment
)

internal data class SessionGroundedWebSource(
    val turnNumber: Int,
    val source: WebSourceCitation
)

private val rightVaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val VAULT_CODE_FENCE_REGEX = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
private val VAULT_LINE_NUMBER_STRIP_REGEX = Regex("^\\d+:\\s?")

/**
 * Counts the chat's files for the top bar badge without parsing or building lists; the full
 * `extractSessionVaultItems` runs only when the files sidebar is opened.
 */
internal fun countSessionVaultItemsFast(
    session: ChatSession,
    pendingAttachments: List<PromptAttachment> = emptyList()
): Int {
    var total = pendingAttachments.size
    for (node in session.messages) {
        val v = node.activeVariant
        total += v.attachments.size
        if (node.role == MessageRole.ASSISTANT) {
            total += v.mediaItems.size
            total += v.webSources.size
            var wroteWorkspaceFileInTurn = false
            for (tc in v.toolCalls) {
                if (!tc.isError) {
                    val n = tc.name
                    if (n.equals("fs_write", ignoreCase = true) ||
                        n.equals("fs_edit", ignoreCase = true) ||
                        n.equals("fs_multi_edit", ignoreCase = true)
                    ) {
                        wroteWorkspaceFileInTurn = true
                        total++
                    }
                }
            }
            if (!wroteWorkspaceFileInTurn && v.content.length >= 32 && v.content.contains("```")) {
                for (match in VAULT_CODE_FENCE_REGEX.findAll(v.content)) {
                    val lang = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    val code = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (code.isNotEmpty() && (countLinesFast(code) >= 6 || lang.equals("svg", ignoreCase = true))) {
                        total++
                    }
                }
            }
        }
    }
    return total
}

internal fun extractSessionVaultItems(
    session: ChatSession,
    pendingAttachments: List<PromptAttachment> = emptyList()
): Triple<List<AiGeneratedChatArtifact>, List<UserUploadedChatArtifact>, List<SessionGroundedWebSource>> {
    val aiArtifacts = mutableListOf<AiGeneratedChatArtifact>()
    val userUploads = mutableListOf<UserUploadedChatArtifact>()
    val webSources = mutableListOf<SessionGroundedWebSource>()

    for (att in pendingAttachments) {
        userUploads.add(UserUploadedChatArtifact(turnNumber = 0, attachment = att))
    }

    val codeFenceRegex = VAULT_CODE_FENCE_REGEX

    session.messages.forEachIndexed { idx, node ->
        val turnNum = idx + 1
        val v = node.activeVariant
        if (node.role == MessageRole.USER) {
            for (att in v.attachments) {
                userUploads.add(UserUploadedChatArtifact(turnNumber = turnNum, attachment = att))
            }
        } else if (node.role == MessageRole.ASSISTANT) {
            for (att in v.attachments) {
                userUploads.add(UserUploadedChatArtifact(turnNumber = turnNum, attachment = att))
            }
            var wroteWorkspaceFileInTurn = false
            for (tc in v.toolCalls) {
                val low = tc.name.lowercase()
                if ((low == "fs_write" || low == "fs_edit" || low == "fs_multi_edit") && !tc.isError) {
                    val parsed = runCatching { rightVaultJson.parseToJsonElement(tc.argumentsJson).jsonObject }.getOrNull()
                    val path = parsed?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (path.isNotBlank()) {
                        wroteWorkspaceFileInTurn = true
                        val content = when (low) {
                            "fs_write" -> parsed?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                            "fs_edit" -> parsed?.get("new_text")?.jsonPrimitive?.contentOrNull.orEmpty()
                            else -> {
                                val edits = parsed?.get("edits") as? JsonArray
                                edits?.mapNotNull { (it as? JsonObject)?.get("new_text")?.jsonPrimitive?.contentOrNull }
                                    ?.joinToString("\n\n").orEmpty()
                            }
                        }
                        val badge = if (low == "fs_write") "✦ AI wrote" else "✦ AI edited"
                        aiArtifacts.add(
                            AiGeneratedChatArtifact.WorkspaceFileArtifact(
                                turnNumber = turnNum,
                                filePath = path,
                                actionBadge = badge,
                                content = content.ifBlank { tc.resultText },
                                lineCount = countLinesFast(content).coerceAtLeast(1)
                            )
                        )
                    }
                }
            }
            for (media in v.mediaItems) {
                aiArtifacts.add(AiGeneratedChatArtifact.MediaArtifact(turnNumber = turnNum, media = media))
            }
            if (!wroteWorkspaceFileInTurn && v.content.contains("```")) {
                var blockCounter = 0
                for (match in codeFenceRegex.findAll(v.content)) {
                    val lang = match.groupValues.getOrNull(1)?.trim().orEmpty().ifBlank { "txt" }
                    val code = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    val lines = countLinesFast(code)
                    if (code.isNotEmpty() && (lines >= 6 || lang.equals("svg", ignoreCase = true))) {
                        blockCounter++
                        aiArtifacts.add(
                            AiGeneratedChatArtifact.CodeSnippetArtifact(
                                turnNumber = turnNum,
                                fileName = "turn-$turnNum-artifact-$blockCounter.${lang.lowercase()}",
                                language = lang,
                                code = code,
                                lineCount = lines
                            )
                        )
                    }
                }
            }
            for (src in v.webSources) {
                webSources.add(SessionGroundedWebSource(turnNumber = turnNum, source = src))
            }
        }
    }

    return Triple(aiArtifacts, userUploads, webSources)
}

@Composable
internal fun ParchmentRightFilesSidebar(
    activeSession: ChatSession,
    pendingAttachments: List<PromptAttachment>,
    onInspectAttachment: (PromptAttachment) -> Unit,
    onInspectGeneratedImage: (GeneratedMediaItem) -> Unit,
    onReattachAttachment: (PromptAttachment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalParchmentPalette.current
    var selectedFilter by remember(activeSession.id) { mutableStateOf(RightVaultFilter.ALL) }
    val vaultStructuralKey = remember(
        activeSession.id,
        activeSession.messages.size,
        pendingAttachments.size,
        activeSession.messages.lastOrNull()?.activeVariant?.let { v ->
            "${v.toolCalls.size}:${v.mediaItems.size}:${v.webSources.size}:${v.isStreaming}:${v.content.length / 256}"
        }
    ) {
        "${activeSession.id}:${activeSession.messages.size}:${pendingAttachments.size}:" +
            activeSession.messages.sumOf {
                val v = it.activeVariant
                v.toolCalls.size * 17 + v.mediaItems.size * 31 + v.webSources.size * 13 + v.attachments.size * 7 + (v.content.length / 256)
            }
    }
    val (aiArtifacts, userUploads, webSources) = remember(vaultStructuralKey, pendingAttachments) {
        extractSessionVaultItems(activeSession, pendingAttachments)
    }
    val totalCount = aiArtifacts.size + userUploads.size + webSources.size

    Column(
        modifier = modifier
            .background(p.sheetBg)
            .padding(horizontal = Space.s12, vertical = Space.s10),
        verticalArrangement = Arrangement.spacedBy(Space.s8)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f)
            ) {
                KissVectorIcon(icon = KissIcon.FOLDER, tint = p.accent, size = 15.dp)
                BasicText(
                    text = Strings.chatFilesSources(totalCount),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                )
            }
            SepiaButton(
                text = "×",
                contentDescription = Strings.close,
                compact = true,
                style = SepiaButtonStyle.GHOST,
                onClick = onClose
            )
        }

        // Category Filter Pills
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SepiaChip(
                label = Strings.all(totalCount),
                selected = selectedFilter == RightVaultFilter.ALL,
                onClick = { selectedFilter = RightVaultFilter.ALL }
            )
            SepiaChip(
                label = Strings.aiGen(aiArtifacts.size),
                selected = selectedFilter == RightVaultFilter.AI_GENERATED,
                onClick = { selectedFilter = RightVaultFilter.AI_GENERATED }
            )
            SepiaChip(
                label = Strings.uploaded(userUploads.size),
                selected = selectedFilter == RightVaultFilter.USER_UPLOADED,
                onClick = { selectedFilter = RightVaultFilter.USER_UPLOADED }
            )
            SepiaChip(
                label = Strings.sources(webSources.size),
                selected = selectedFilter == RightVaultFilter.WEB_SOURCES,
                onClick = { selectedFilter = RightVaultFilter.WEB_SOURCES }
            )
        }

        SepiaDivider()

        if (totalCount == 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Space.s16),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.s8)
                ) {
                    KissVectorIcon(icon = KissIcon.FOLDER, tint = p.mutedInk, size = 26.dp)
                    BasicText(
                        text = Strings.noFilesOrSourcesYet,
                        style = p.uiHeaderStyle.copy(fontSize = 14.sp, color = p.narrationInk)
                    )
                    BasicText(
                        text = Strings.filesUploadedByYouFiles,
                        style = p.uiLabelStyle.copy(color = p.mutedInk)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.s10)
            ) {
                val showAiSection = (selectedFilter == RightVaultFilter.ALL || selectedFilter == RightVaultFilter.AI_GENERATED) &&
                    aiArtifacts.isNotEmpty()
                val showUploadedSection = (selectedFilter == RightVaultFilter.ALL || selectedFilter == RightVaultFilter.USER_UPLOADED) &&
                    userUploads.isNotEmpty()
                val showSourcesSection = (selectedFilter == RightVaultFilter.ALL || selectedFilter == RightVaultFilter.WEB_SOURCES) &&
                    webSources.isNotEmpty()

                // 1. AI-Generated Files & Media
                if (showAiSection) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.SPARKLES, tint = p.accent, size = 12.dp)
                        BasicText(
                            text = Strings.filesAndMediaFromAi(aiArtifacts.size),
                            style = p.uiLabelStyle.copy(
                                color = p.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    aiArtifacts.forEach { item ->
                        when (item) {
                            is AiGeneratedChatArtifact.WorkspaceFileArtifact -> {
                                val shortName = item.filePath.substringAfterLast('/').substringAfterLast('\\')
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.r8))
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r8))
                                        .clickable(role = Role.Button) {
                                            val wsRoot = activeSession.workspacePath
                                            val liveText = if (wsRoot.isNotBlank()) {
                                                PlatformBridge.workspaceRead(wsRoot, item.filePath, 1, 2000).getOrNull()?.let { raw ->
                                                    raw.lines().drop(1).joinToString("\n") { it.replaceFirst(VAULT_LINE_NUMBER_STRIP_REGEX, "") }
                                                }
                                            } else null
                                            val fullLocalPath = if (wsRoot.isNotBlank()) {
                                                "${wsRoot.trimEnd('/', '\\')}/${item.filePath.trimStart('/', '\\')}"
                                            } else ""
                                            onInspectAttachment(
                                                PromptAttachment(
                                                    id = "ai-ws-${item.turnNumber}-${item.filePath}",
                                                    fileName = item.filePath,
                                                    mimeType = "text/plain",
                                                    textContent = liveText?.takeIf { it.isNotBlank() } ?: item.content,
                                                    localFilePath = fullLocalPath
                                                )
                                            )
                                        }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Radius.r4))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = Space.s6, vertical = Space.s2)
                                        ) {
                                            val parsedBadge = remember(item.actionBadge) { parseKissLabelIcons(item.actionBadge) }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                            ) {
                                                KissVectorIcon(
                                                    icon = parsedBadge.leadingIcon ?: KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = IconSize.xs
                                                )
                                                BasicText(
                                                    text = parsedBadge.cleanText.ifEmpty { item.actionBadge },
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = Strings.turnLines(item.turnNumber, item.lineCount),
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.DIFF, tint = p.accent, size = IconSize.md)
                                        BasicText(
                                            text = shortName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                    }
                                    BasicText(
                                        text = Strings.clickToInspect(item.filePath),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                }
                            }

                            is AiGeneratedChatArtifact.MediaArtifact -> {
                                val media = item.media
                                val isVideo = media.kind == MediaKind.VIDEO
                                val fileLabel = media.localFilePath.substringAfterLast('/').substringAfterLast('\\')
                                    .ifBlank { if (isVideo) Strings.generatedVideoMp4 else Strings.generatedImage2 }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.r8))
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r8))
                                        .clickable(role = Role.Button) {
                                            if (isVideo) {
                                                val target = media.localFilePath.ifBlank { media.remoteUrl }
                                                if (target.isNotBlank()) PlatformBridge.openOrPlayMedia(target)
                                            } else {
                                                onInspectGeneratedImage(media)
                                            }
                                        }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Radius.r4))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = Space.s6, vertical = Space.s2)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                            ) {
                                                KissVectorIcon(
                                                    icon = KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = IconSize.xs
                                                )
                                                BasicText(
                                                    text = if (isVideo) "AI video" else "AI image",
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = Strings.turn(item.turnNumber, if (media.aspectRatio.isNotBlank()) " • ${media.aspectRatio}" else ""),
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(
                                            icon = if (isVideo) KissIcon.VIDEO else KissIcon.IMAGE,
                                            tint = p.accent,
                                            size = IconSize.md
                                        )
                                        BasicText(
                                            text = fileLabel,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                    }
                                    BasicText(
                                        text = if (isVideo) Strings.clickToPlayMp4Video else Strings.clickToZoomImage,
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.accent)
                                    )
                                }
                            }

                            is AiGeneratedChatArtifact.CodeSnippetArtifact -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.r8))
                                        .background(p.cardBg)
                                        .border(LineWidth.hairline, p.accent.copy(alpha = 0.65f), RoundedCornerShape(Radius.r8))
                                        .clickable(role = Role.Button) {
                                            onInspectAttachment(
                                                PromptAttachment(
                                                    id = "ai-code-${item.turnNumber}-${item.fileName}",
                                                    fileName = item.fileName,
                                                    mimeType = "text/plain",
                                                    textContent = item.code
                                                )
                                            )
                                        }
                                        .padding(Space.s10),
                                    verticalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(Radius.r4))
                                                .background(p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = Space.s6, vertical = Space.s2)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                            ) {
                                                KissVectorIcon(
                                                    icon = KissIcon.SPARKLES,
                                                    tint = p.accent,
                                                    size = IconSize.xs
                                                )
                                                BasicText(
                                                    text = Strings.ai(item.language),
                                                    style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                )
                                            }
                                        }
                                        BasicText(
                                            text = Strings.turnLines(item.turnNumber, item.lineCount),
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                    ) {
                                        KissVectorIcon(icon = KissIcon.CODE, tint = p.accent, size = IconSize.md)
                                        BasicText(
                                            text = item.fileName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        )
                                    }
                                    BasicText(
                                        text = Strings.clickToViewFullCode,
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. User-Uploaded Documents & Files
                if (showUploadedSection) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.UPLOAD, tint = p.narrationInk, size = 12.dp)
                        BasicText(
                            text = Strings.yourUploads(userUploads.size),
                            style = p.uiLabelStyle.copy(
                                color = p.narrationInk,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    userUploads.forEach { entry ->
                        val att = entry.attachment
                        val mime = att.mimeType.lowercase()
                        val lowerName = att.fileName.lowercase()
                        val isImg = mime.startsWith("image/") || lowerName.endsWith(".png") ||
                            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")
                        val isPdf = mime == "application/pdf" || lowerName.endsWith(".pdf")
                        val isVid = mime.startsWith("video/") || lowerName.endsWith(".mp4")
                        val isAud = mime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav")
                        val fileIcon = when {
                            isImg -> KissIcon.IMAGE
                            isVid -> KissIcon.VIDEO
                            isAud -> KissIcon.AUDIO
                            else -> KissIcon.FILE_TEXT
                        }
                        val kindLabel = when {
                            isPdf -> "PDF Document"
                            isImg -> Strings.image
                            isVid -> Strings.video
                            isAud -> Strings.audio
                            att.textContent.isNotBlank() -> "${att.textContent.lines().size} lines"
                            else -> Strings.file
                        }
                        val turnLabel = if (entry.turnNumber == 0) "Staged" else "Turn #${entry.turnNumber}"

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.r8))
                                .background(p.cardBg)
                                .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r8))
                                .clickable(role = Role.Button, onClickLabel = Strings.openAttachment) { onInspectAttachment(att) }
                                .padding(Space.s10),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.r4))
                                        .background(p.elevatedBg)
                                        .padding(horizontal = Space.s6, vertical = Space.s2)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                    ) {
                                        KissVectorIcon(
                                            icon = KissIcon.UPLOAD,
                                            tint = p.primaryInk,
                                            size = IconSize.xs
                                        )
                                        BasicText(
                                            text = Strings.yourUpload,
                                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        )
                                    }
                                }
                                BasicText(
                                    text = "$turnLabel • $kindLabel",
                                    style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = Space.s6),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6)
                                ) {
                                    KissVectorIcon(icon = fileIcon, tint = p.accent, size = IconSize.md)
                                    BasicText(
                                        text = att.fileName,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    )
                                }
                                if (entry.turnNumber > 0) {
                                    SepiaButton(
                                        text = "@",
                                        contentDescription = Strings.reattachToComposer,
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { onReattachAttachment(att) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Grounded Web Sources
                if (showSourcesSection) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        KissVectorIcon(icon = KissIcon.GLOBE, tint = p.accentSecondary, size = 12.dp)
                        BasicText(
                            text = Strings.webSources(webSources.size),
                            style = p.uiLabelStyle.copy(
                                color = p.accentSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    webSources.forEach { item ->
                        val src = item.source
                        val lineLabel = if (src.groundedLineNumbers.isNotEmpty()) {
                            Strings.line(src.groundedLineNumbers.joinToString(", "))
                        } else Strings.grounded
                        val quoteExcerpt = src.groundedLines.firstOrNull().orEmpty()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.r8))
                                .background(p.cardBg)
                                .border(LineWidth.hairline, p.border, RoundedCornerShape(Radius.r8))
                                .clickable(role = Role.Button, onClickLabel = Strings.openSource) { openWebLink(src.url) }
                                .padding(Space.s10),
                            verticalArrangement = Arrangement.spacedBy(Space.s4)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r4))
                                            .background(p.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = "[${src.index}]",
                                            style = p.monoStyle.copy(color = p.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        )
                                    }
                                    BasicText(
                                        text = src.domain.ifBlank { src.url },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.mutedInk)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    BasicText(
                                        text = Strings.turn2(item.turnNumber, lineLabel),
                                        style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    )
                                    KissVectorIcon(
                                        icon = KissIcon.EXTERNAL_LINK,
                                        tint = p.accent,
                                        size = IconSize.xs
                                    )
                                }
                            }
                            BasicText(
                                text = src.title.ifBlank { src.url },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = p.uiBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            )
                            if (quoteExcerpt.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s4)
                                ) {
                                    KissVectorIcon(
                                        icon = KissIcon.CORNER_DOWN_RIGHT,
                                        tint = p.narrationInk,
                                        size = IconSize.xs
                                    )
                                    BasicText(
                                        text = "$lineLabel: \"$quoteExcerpt\"",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiLabelStyle.copy(color = p.narrationInk, fontSize = 11.sp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

