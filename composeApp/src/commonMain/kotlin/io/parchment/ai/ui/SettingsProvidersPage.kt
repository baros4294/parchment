package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.AuthHeaderMode
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.ModelPricingSpec
import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.CUSTOM_DIALECTS
import io.parchment.ai.model.ProviderProtocol
import io.parchment.ai.model.customDialectLabel
import io.parchment.ai.model.keepsProviderState
import io.parchment.ai.network.AiProviderEngine
import io.parchment.ai.network.ModelsDevClient
import io.parchment.ai.network.fetchAvailableModels
import io.parchment.ai.platform.PlatformBridge
import kotlinx.coroutines.launch

// ============================================================================================
// Settings page 2: Providers (the list, a provider's details and a model's page).
// ============================================================================================
@Composable
internal fun ProvidersSubpage(
    appState: AppStateSnapshot,
    activeProfile: ProviderProfile,
    activeSession: ChatSession,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onUpdateProfileById: (String, (ProviderProfile) -> ProviderProfile) -> Unit,
    onAddProfile: (ProviderProtocol) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onUpdateSession: ((ChatSession) -> ChatSession) -> Unit,
    onNotify: (String) -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    var providerSearch by remember { mutableStateOf("") }
    var modelSearch by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showAddProviderMenu by remember { mutableStateOf(false) }
    var showProtocolSettingsSubpage by remember(activeProfile.id) { mutableStateOf(false) }
    var selectedModelForConfigSubpage by remember(activeProfile.id) { mutableStateOf<String?>(null) }
    var manualModelToAdd by remember { mutableStateOf("") }
    var showAddModelBox by remember { mutableStateOf(false) }
    var isTestingOrFetching by remember { mutableStateOf(false) }
    var mobileEditingProfileId by remember { mutableStateOf<String?>(null) }

    val filteredProfiles = remember(appState.profiles, providerSearch) {
        val q = providerSearch.trim()
        if (q.isEmpty()) appState.profiles
        else appState.profiles.filter { it.name.contains(q, ignoreCase = true) || it.baseUrl.contains(q, ignoreCase = true) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 680.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // Middle Column (on Desktop): Provider List
            if (isWide) {
                Column(
                    modifier = Modifier
                        .width(235.dp)
                        .fillMaxHeight()
                        .background(p.sheetBg.copy(alpha = 0.35f))
                        .padding(Space.s12),
                    verticalArrangement = Arrangement.spacedBy(Space.s8)
                ) {
                    SepiaTextField(
                        value = providerSearch,
                        onValueChange = { providerSearch = it },
                        placeholder = "🔍 Search providers…",
                        minHeight = 34.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        filteredProfiles.forEach { prof ->
                            val isSel = prof.id == activeProfile.id
                            val shape = RoundedCornerShape(Radius.r10)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(if (isSel) p.cardBg else Color.Transparent)
                                    .border(LineWidth.hairline, if (isSel) p.border else Color.Transparent, shape)
                                    .clickable(role = Role.Button) { onSelectProfile(prof.id) }
                                    .semantics { selected = isSel }
                                    .padding(horizontal = Space.s10, vertical = Space.s8),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(if (isSel) p.accent.copy(alpha = 0.16f) else p.elevatedBg)
                                            .border(LineWidth.hairline, if (isSel) p.accent else p.border, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ModelLabSvgIcon(
                                            modelOrProvider = prof.defaultModel.ifBlank { prof.name },
                                            tint = if (isSel) p.accent else p.narrationInk,
                                            size = 14.dp,
                                            fallbackProtocolName = prof.protocol.displayName
                                        )
                                    }
                                    BasicText(
                                        text = prof.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                                val statusOn = prof.enabled && (prof.apiKey.isNotBlank() || prof.baseUrl != prof.protocol.defaultBaseUrl)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.r6))
                                        .background(if (statusOn) p.success.copy(alpha = 0.14f) else p.accentSecondary.copy(alpha = 0.14f))
                                        .padding(horizontal = Space.s6, vertical = Space.s2)
                                ) {
                                    BasicText(
                                        text = if (statusOn) "On" else "Off",
                                        style = p.uiLabelStyle.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (statusOn) p.success else p.accentSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    SepiaButton(
                        text = "+ Add provider",
                        style = SepiaButtonStyle.OUTLINE,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showAddProviderMenu = !showAddProviderMenu }
                    )

                    AnimatedVisibility(visible = showAddProviderMenu) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                            ProviderProtocol.entries.forEach { proto ->
                                SepiaButton(
                                    text = "+ ${proto.displayName.substringBefore(" API")}",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), wrapText = true,
                                    onClick = {
                                        onAddProfile(proto)
                                        showAddProviderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.width(LineWidth.hairline).fillMaxHeight().background(p.border.copy(alpha = 0.6f)))
            }

            // Right Pane: Provider Detail OR Per-Model Config Subpage
            val win = LocalParchmentWindowSize.current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(if (win.isCompact) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isWide) {
                    // Mobile Hierarchical Provider List -> Provider Detail Subpage Drill-Down
                    if (mobileEditingProfileId == null) {
                        BasicText(
                            text = "Select a provider profile to configure",
                            style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                        )
                        appState.profiles.forEach { prof ->
                            val isSel = prof.id == activeProfile.id
                            val statusOn = prof.enabled && (prof.apiKey.isNotBlank() || prof.baseUrl != prof.protocol.defaultBaseUrl)
                            val shape = RoundedCornerShape(Radius.r12)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(p.cardBg)
                                    .border(
                                        width = if (isSel) 1.dp else LineWidth.hairline,
                                        color = if (isSel) p.accent else p.border,
                                        shape = shape
                                    )
                                    .kissClickable(pressScale = 0.985f, selected = isSel) {
                                        onSelectProfile(prof.id)
                                        mobileEditingProfileId = prof.id
                                    }
                                    .padding(horizontal = 14.dp, vertical = Space.s12),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = Space.s10),
                                    horizontalArrangement = Arrangement.spacedBy(Space.s12),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(if (isSel) p.accent.copy(alpha = 0.16f) else p.elevatedBg)
                                            .border(LineWidth.hairline, if (isSel) p.accent else p.border, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ModelLabSvgIcon(
                                            modelOrProvider = prof.defaultModel.ifBlank { prof.name },
                                            tint = if (isSel) p.accent else p.narrationInk,
                                            size = 17.dp,
                                            fallbackProtocolName = prof.protocol.displayName
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(Space.s2)
                                    ) {
                                        BasicText(
                                            text = prof.name,
                                            style = p.uiHeaderStyle.copy(fontSize = 15.sp)
                                        )
                                        val modelCountInfo = if (prof.savedModels.isNotEmpty()) "${prof.savedModels.size} models" else prof.defaultModel.ifBlank { "No models yet" }
                                        BasicText(
                                            text = "${prof.protocol.displayName.substringBefore(" API")} • $modelCountInfo",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, color = p.narrationInk)
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s8)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r6))
                                            .background(if (statusOn) p.success.copy(alpha = 0.14f) else p.accentSecondary.copy(alpha = 0.14f))
                                            .padding(horizontal = Space.s6, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = if (statusOn) "On" else "Off",
                                            style = p.uiLabelStyle.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (statusOn) p.success else p.accentSecondary
                                            )
                                        )
                                    }
                                    KissVectorIcon(icon = KissIcon.CHEVRON_RIGHT, tint = p.mutedInk, size = 15.dp)
                                }
                            }
                        }

                        SepiaButton(
                            text = "+ Add provider profile",
                            style = SepiaButtonStyle.OUTLINE,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showAddProviderMenu = !showAddProviderMenu }
                        )
                        AnimatedVisibility(visible = showAddProviderMenu) {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s6)) {
                                ProviderProtocol.entries.forEach { proto ->
                                    SepiaButton(
                                        text = "+ ${proto.displayName.substringBefore(" API")}",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), wrapText = true,
                                        onClick = {
                                            onAddProfile(proto)
                                            showAddProviderMenu = false
                                            mobileEditingProfileId = "newly-added"
                                        }
                                    )
                                }
                            }
                        }
                        return@Column
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SepiaButton(
                                text = "← All providers",
                                compact = true,
                                style = SepiaButtonStyle.OUTLINE,
                                onClick = { mobileEditingProfileId = null }
                            )
                            BasicText(
                                text = activeProfile.name,
                                style = p.uiLabelStyle.copy(color = p.accent, fontWeight = FontWeight.SemiBold)
                            )
                        }
                        SepiaDivider()
                    }
                }
                // With a model's page open, show its token limits and pricing.
                val configModelId = selectedModelForConfigSubpage
                if (configModelId != null) {
                    ModelConfigSubpage(
                        profile = activeProfile,
                        modelId = configModelId,
                        onUpdateProfile = onUpdateProfile,
                        onNotify = onNotify,
                        onBack = { selectedModelForConfigSubpage = null }
                    )
                    return@Column
                }

                // If Protocol & Request Overrides Subpage is open
                if (showProtocolSettingsSubpage) {
                    ProviderProtocolSubpage(
                        profile = activeProfile,
                        onUpdateProfile = onUpdateProfile,
                        onDeleteProfile = { onDeleteProfile(activeProfile.id) },
                        onBack = { showProtocolSettingsSubpage = false }
                    )
                    return@Column
                }

                // Main Provider Detail Header with Inline Editable Name + Enable Switch
                var isRenamingProvider by remember(activeProfile.id) { mutableStateOf(false) }
                var providerNameBuffer by remember(activeProfile.id, activeProfile.name) { mutableStateOf(activeProfile.name) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.weight(1f).padding(end = Space.s8),
                        horizontalArrangement = Arrangement.spacedBy(Space.s8),
                        verticalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        if (isRenamingProvider) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Space.s6),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SepiaTextField(
                                    value = providerNameBuffer,
                                    onValueChange = { providerNameBuffer = it },
                                    placeholder = "Provider Name…",
                                    minHeight = 32.dp,
                                    modifier = Modifier.width(210.dp)
                                )
                                SepiaButton(
                                    text = "Save",
                                    compact = true,
                                    style = SepiaButtonStyle.PRIMARY,
                                    onClick = {
                                        val cleanName = providerNameBuffer.trim().ifBlank { "Custom Provider" }
                                        onUpdateProfileById(activeProfile.id) { it.copy(name = cleanName) }
                                        isRenamingProvider = false
                                    }
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s6),
                                modifier = Modifier
                                    .kissClickable { isRenamingProvider = true }
                                    .padding(top = Space.s2)
                            ) {
                                ModelLabSvgIcon(
                                    modelOrProvider = activeProfile.defaultModel.ifBlank { activeProfile.name },
                                    tint = p.accent,
                                    size = 17.dp,
                                    fallbackProtocolName = activeProfile.protocol.displayName
                                )
                                BasicText(
                                    text = activeProfile.name,
                                    style = p.uiHeaderStyle.copy(fontSize = 18.sp)
                                )
                                KissVectorIcon(
                                    icon = KissIcon.EDIT,
                                    tint = p.mutedInk,
                                    size = 13.dp
                                )
                            }
                        }
                        SepiaButton(
                            text = "⚙ Advanced",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showProtocolSettingsSubpage = true }
                        )
                        if (appState.profiles.size > 1) {
                            SepiaButton(
                                text = "🗑",
                                contentDescription = "Delete provider",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    onDeleteProfile(activeProfile.id)
                                    mobileEditingProfileId = null
                                }
                            )
                        }
                    }
                    SepiaToggleSwitch(
                        checked = activeProfile.enabled,
                        label = "Enable provider ${activeProfile.name}",
                        onCheckedChange = { en -> onUpdateProfileById(activeProfile.id) { it.copy(enabled = en) } }
                    )
                }

                // API Key Row with Show/Hide & `♡ Test` Connection Button
                Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(text = "API key", style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold, color = p.narrationInk))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                            SepiaButton(
                                text = if (showApiKey) "🙈" else "👁",
                                contentDescription = if (showApiKey) "Hide API key" else "Show API key",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = { showApiKey = !showApiKey }
                            )
                            SepiaButton(
                                text = if (isTestingOrFetching) "Testing…" else "♡ Test",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    isTestingOrFetching = true
                                    scope.launch {
                                        val start = PlatformBridge.currentTimeMillis()
                                        AiProviderEngine.fetchAvailableModels(activeProfile).fold(
                                            onSuccess = { list ->
                                                val ms = PlatformBridge.currentTimeMillis() - start
                                                val secStr = formatDurationFromMs(ms)
                                                onNotify("✓ Connection OK ($secStr • ${list.size} models reachable)")
                                            },
                                            onFailure = { err ->
                                                onNotify("✕ Connection Test Failed: ${err.message}")
                                                isTestingOrFetching = false
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                    SepiaTextField(
                        value = if (showApiKey) activeProfile.apiKey else "•".repeat(activeProfile.apiKey.length.coerceAtMost(48)),
                        onValueChange = { newVal ->
                            if (showApiKey || activeProfile.apiKey.isEmpty()) {
                                onUpdateProfileById(activeProfile.id) { it.copy(apiKey = newVal.trim()) }
                            }
                        },
                        placeholder = "🔑 Paste API key (toggle visibility above to edit masked key)…",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // API Base URL (Keyed by activeProfile.id with 1-tap Clear / Reset Default buttons)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "Server URL",
                            style = p.uiLabelStyle.copy(fontWeight = FontWeight.SemiBold, color = p.narrationInk)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                            if (activeProfile.baseUrl.isNotEmpty()) {
                                SepiaButton(
                                    text = "× Clear URL",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = { onUpdateProfileById(activeProfile.id) { it.copy(baseUrl = "") } }
                                )
                            }
                            if (activeProfile.baseUrl != activeProfile.protocol.defaultBaseUrl) {
                                SepiaButton(
                                    text = "↺ Default URL",
                                    compact = true,
                                    style = SepiaButtonStyle.GHOST,
                                    onClick = {
                                        onUpdateProfileById(activeProfile.id) { it.copy(baseUrl = activeProfile.protocol.defaultBaseUrl) }
                                    }
                                )
                            }
                        }
                    }
                    SepiaTextField(
                        value = activeProfile.baseUrl,
                        onValueChange = { url -> onUpdateProfileById(activeProfile.id) { it.copy(baseUrl = url) } },
                        placeholder = activeProfile.protocol.defaultBaseUrl,
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (activeProfile.protocol.isCustomWire) {
                        Spacer(Modifier.height(Space.s6))
                        BasicText(
                            text = "API format",
                            style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.SemiBold)
                        )
                        BasicText(
                            text = "Parchment uses the URL above exactly as typed. Choose the format your service uses. Its documentation will say, and most use OpenAI Chat Completions.",
                            style = p.uiLabelStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                        )
                        Spacer(Modifier.height(Space.s4))
                        Column(Modifier.fillMaxWidth().selectableGroup()) {
                            CUSTOM_DIALECTS.forEach { dialect ->
                                SepiaChoiceRow(
                                    label = customDialectLabel(dialect),
                                    selected = activeProfile.customDialect == dialect,
                                    onClick = {
                                        onUpdateProfileById(activeProfile.id) { it.copy(customDialect = dialect) }
                                    }
                                )
                            }
                        }
                        if (activeProfile.customDialect.isBlank()) {
                            Spacer(Modifier.height(Space.s4))
                            BasicText(
                                text = "Choose a format to start chatting.",
                                style = p.uiLabelStyle.copy(color = p.danger, fontSize = 11.sp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Space.s4))

                // Models Section Header (Matching Screenshot 9: `Models (count)` + `🔍` `+` `🗑` `🔄 Fetch`)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            text = "Models",
                            style = p.uiHeaderStyle.copy(fontSize = 16.sp)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(p.sheetBg)
                                .padding(horizontal = Space.s8, vertical = Space.s2)
                        ) {
                            BasicText(
                                text = "${activeProfile.savedModels.size}",
                                style = p.uiLabelStyle.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalAlignment = Alignment.CenterVertically) {
                        SepiaButton(
                            text = "+",
                            contentDescription = "Add model",
                            compact = true,
                            style = SepiaButtonStyle.OUTLINE,
                            onClick = { showAddModelBox = !showAddModelBox }
                        )
                        if (activeProfile.savedModels.isNotEmpty()) {
                            SepiaButton(
                                text = "🗑",
                                contentDescription = "Remove all saved models",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    onUpdateProfile { it.copy(savedModels = emptyList()) }
                                    onNotify("Cleared model list for ${activeProfile.name}.")
                                }
                            )
                        }
                        SepiaButton(
                            text = if (isTestingOrFetching) "Fetching…" else "🔄 Fetch",
                            compact = true,
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = {
                                isTestingOrFetching = true
                                scope.launch {
                                    AiProviderEngine.fetchAvailableModels(activeProfile).fold(
                                        onSuccess = { fetched ->
                                            val merged = (activeProfile.savedModels + fetched).distinct()
                                            // Never replace a default model the user already chose.
                                            // If defaultModel is blank, pick a chat model (skipping deep-research/embedding)
                                            val preferredDefault = activeProfile.defaultModel.ifBlank {
                                                pickBestInitialChatModel(merged)
                                            }
                                            onUpdateProfile {
                                                it.copy(savedModels = merged, defaultModel = preferredDefault)
                                            }
                                            onNotify("Fetched ${fetched.size} models.")
                                            isTestingOrFetching = false
                                        },
                                        onFailure = { err ->
                                            onNotify("Fetch failed: ${err.message}")
                                            isTestingOrFetching = false
                                        }
                                    )
                                }
                            }
                        )
                    }
                }

                // Manual Model Add Drawer
                AnimatedVisibility(visible = showAddModelBox || activeProfile.savedModels.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s8),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = manualModelToAdd,
                            onValueChange = { manualModelToAdd = it },
                            placeholder = "Enter model ID…",
                            monospace = true,
                            modifier = Modifier.weight(1f)
                        )
                        SepiaButton(
                            text = "Add & select",
                            style = SepiaButtonStyle.PRIMARY,
                            onClick = {
                                val clean = manualModelToAdd.trim()
                                if (clean.isNotEmpty()) {
                                    val updated = (listOf(clean) + activeProfile.savedModels).distinct()
                                    onUpdateProfile { it.copy(defaultModel = clean, savedModels = updated) }
                                    onUpdateSession { it.copy(modelOverride = clean, profileId = activeProfile.id) }
                                    manualModelToAdd = ""
                                    showAddModelBox = false
                                    onNotify("Selected model: $clean")
                                }
                            }
                        )
                    }
                }

                var selectedVendorFamily by remember(activeProfile.id) { mutableStateOf("") }
                val vendorFamilies = remember(activeProfile.savedModels) {
                    activeProfile.savedModels
                        .filter { it.contains('/') }
                        .groupBy { it.substringBefore('/').trim() }
                        .map { (vendor, list) -> vendor to list.size }
                        .sortedByDescending { it.second }
                }

                if (activeProfile.savedModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s6),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SepiaTextField(
                            value = modelSearch,
                            onValueChange = { modelSearch = it },
                            placeholder = "🔍 Filter ${activeProfile.savedModels.size} models…",
                            minHeight = 34.dp,
                            modifier = Modifier.weight(1f)
                        )
                        if (modelSearch.isNotEmpty() || selectedVendorFamily.isNotEmpty()) {
                            SepiaButton(
                                text = "×",
                                contentDescription = "Clear search",
                                compact = true,
                                style = SepiaButtonStyle.GHOST,
                                onClick = {
                                    modelSearch = ""
                                    selectedVendorFamily = ""
                                }
                            )
                        }
                    }
                }

                if (vendorFamilies.size >= 2) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        SepiaChip(
                            label = "All Families (${activeProfile.savedModels.size})",
                            selected = selectedVendorFamily.isEmpty(),
                            onClick = { selectedVendorFamily = "" }
                        )
                        vendorFamilies.forEach { (vendor, count) ->
                            SepiaChip(
                                label = "$vendor ($count)",
                                selected = selectedVendorFamily.equals(vendor, ignoreCase = true),
                                onClick = {
                                    selectedVendorFamily = if (selectedVendorFamily.equals(vendor, ignoreCase = true)) "" else vendor
                                }
                            )
                        }
                    }
                }

                // Grouped & Bounded Vertical Model List Card
                val visibleModels = remember(activeProfile.savedModels, modelSearch, selectedVendorFamily) {
                    val tokens = modelSearch.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    activeProfile.savedModels.filter { m ->
                        val vendOk = selectedVendorFamily.isEmpty() ||
                            (m.contains('/') && m.substringBefore('/').trim().equals(selectedVendorFamily, ignoreCase = true))
                        val searchOk = tokens.isEmpty() || tokens.all { tok -> m.lowercase().contains(tok) }
                        vendOk && searchOk
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(Radius.r10))
                        .background(p.cardBg)
                        .border(LineWidth.hairline, p.border.copy(alpha = 0.75f), RoundedCornerShape(Radius.r10))
                        .verticalScroll(rememberScrollState())
                        .padding(Space.s10),
                    verticalArrangement = Arrangement.spacedBy(Space.s6)
                ) {
                    if (visibleModels.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.s6),
                            modifier = Modifier.padding(Space.s8)
                        ) {
                            KissVectorIcon(icon = KissIcon.REFRESH, tint = p.mutedInk, size = 13.dp)
                            BasicText(
                                text = "No models added yet. Type a model ID above or click Fetch.",
                                style = p.uiLabelStyle
                            )
                        }
                    } else {
                        visibleModels.forEach { modelId ->
                            val isSelectedModel = activeProfile.defaultModel == modelId
                            val customSpec = activeProfile.customModelSpecs[modelId]
                            val ctxTokens = customSpec?.contextWindowTokens ?: activeProfile.contextWindowTokens
                            val rowInteraction = remember { MutableInteractionSource() }
                            val rowHovered by rowInteraction.collectIsHoveredAsState()
                            val rowPressed by rowInteraction.collectIsPressedAsState()
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .kissClickable(
                                        pressScale = 0.985f,
                                        hoverScale = 1.008f,
                                        interactionSource = rowInteraction,
                                        selected = isSelectedModel,
                                        onClick = {
                                            onUpdateProfile { it.copy(defaultModel = modelId) }
                                            onUpdateSession { it.copy(modelOverride = modelId, profileId = activeProfile.id) }
                                        }
                                    )
                                    .clip(RoundedCornerShape(Radius.r8))
                                    .background(
                                        when {
                                            isSelectedModel -> p.accent.copy(alpha = 0.14f)
                                            rowPressed -> p.accent.copy(alpha = 0.12f)
                                            rowHovered -> p.accent.copy(alpha = 0.08f)
                                            else -> p.elevatedBg.copy(alpha = 0.35f)
                                        }
                                    )
                                    .border(
                                        LineWidth.hairline,
                                        if (isSelectedModel || rowHovered) p.accent.copy(alpha = if (isSelectedModel) 1f else 0.55f) else Color.Transparent,
                                        RoundedCornerShape(Radius.r8)
                                    )
                                    .padding(horizontal = Space.s12, vertical = Space.s8),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(Space.s6)
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false).padding(end = Space.s8),
                                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ModelLabSvgIcon(
                                        modelOrProvider = modelId,
                                        tint = if (isSelectedModel || rowHovered) p.accent else p.narrationInk,
                                        size = IconSize.md,
                                        fallbackProtocolName = activeProfile.protocol.displayName
                                    )
                                    BasicText(
                                        text = modelId,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = p.uiBodyStyle.copy(
                                            fontFamily = p.codeFontFamily,
                                            fontWeight = if (isSelectedModel) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelectedModel || rowHovered) p.accent else p.primaryInk
                                        )
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Space.s6),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Capability & Context Window Badges
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.r6))
                                            .background(p.sheetBg)
                                            .padding(horizontal = Space.s6, vertical = Space.s2)
                                    ) {
                                        BasicText(
                                            text = if (ctxTokens <= 0) "∞" else formatCompactTokens(ctxTokens),
                                            style = p.uiLabelStyle.copy(fontSize = 11.sp, fontFamily = p.codeFontFamily)
                                        )
                                    }
                                    SepiaButton(
                                        text = "⚙",
                                        contentDescription = "Model settings",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = { selectedModelForConfigSubpage = modelId }
                                    )
                                    SepiaButton(
                                        text = "—",
                                        contentDescription = "Remove model",
                                        compact = true,
                                        style = SepiaButtonStyle.GHOST,
                                        onClick = {
                                            val remaining = activeProfile.savedModels.filterNot { it == modelId }
                                            val nextDef = if (activeProfile.defaultModel == modelId) remaining.firstOrNull().orEmpty() else activeProfile.defaultModel
                                            onUpdateProfile { it.copy(savedModels = remaining, defaultModel = nextDef) }
                                        }
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

/**
 * Dedicated Subpage for Protocol, Auth Header, & Nullable Sampling/Token Parameters.
 * Supports clearing any default value so it is stripped from outgoing API JSON (`null`), or setting `0 = Unlimited`.
 */
@Composable
internal fun ProviderProtocolSubpage(
    profile: ProviderProfile,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onDeleteProfile: () -> Unit,
    onBack: () -> Unit
) {
    val p = LocalParchmentPalette.current
    var tempText by remember(profile.id, profile.temperature) { mutableStateOf(profile.temperature?.toString() ?: "") }
    var topPText by remember(profile.id, profile.topP) { mutableStateOf(profile.topP?.toString() ?: "") }
    var maxOutText by remember(profile.id, profile.maxOutputTokens) { mutableStateOf(profile.maxOutputTokens?.toString() ?: "") }
    var budgetText by remember(profile.id, profile.thinkingBudgetTokens) { mutableStateOf(profile.thinkingBudgetTokens?.toString() ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.s8)
        ) {
            BasicText(
                text = "Advanced settings for ${profile.name}",
                style = p.uiHeaderStyle,
                modifier = Modifier.padding(top = Space.s4, end = Space.s8)
            )
            SepiaButton(text = "← Back to provider", compact = true, style = SepiaButtonStyle.PRIMARY, onClick = onBack)
        }

        SepiaSectionCard(
            title = "Name, format and address"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                SepiaTextField(
                    value = profile.name,
                    onValueChange = { n -> onUpdateProfile { it.copy(name = n) } },
                    label = "Provider display name",
                    modifier = Modifier.fillMaxWidth()
                )
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    ProviderProtocol.entries.forEach { proto ->
                        SepiaChoiceRow(
                            label = proto.displayName,
                            selected = profile.protocol == proto,
                            onClick = { onUpdateProfile { it.copy(protocol = proto, baseUrl = proto.defaultBaseUrl) } }
                        )
                    }
                }
                SepiaTextField(
                    value = profile.endpointPathOverride,
                    onValueChange = { ep -> onUpdateProfile { it.copy(endpointPathOverride = ep.trim()) } },
                    label = "Custom path (optional, default ${profile.protocol.defaultEndpointPath})",
                    placeholder = profile.protocol.defaultEndpointPath,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (profile.protocol.isCustomWire) {
                    BasicText(
                        text = "API format",
                        style = p.uiLabelStyle.copy(color = p.primaryInk, fontWeight = FontWeight.SemiBold)
                    )
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        CUSTOM_DIALECTS.forEach { dialect ->
                            SepiaChoiceRow(
                                label = customDialectLabel(dialect),
                                selected = profile.customDialect == dialect,
                                onClick = { onUpdateProfile { it.copy(customDialect = dialect) } }
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    AuthHeaderMode.entries.forEach { mode ->
                        SepiaChoiceRow(
                            label = mode.label,
                            selected = profile.authHeaderMode == mode,
                            onClick = { onUpdateProfile { it.copy(authHeaderMode = mode) } }
                        )
                    }
                }
                SepiaToggleRow(
                    title = "Auto-Fetch models on startup when saved list is empty",
                    checked = profile.autoFetchModels,
                    onCheckedChange = { af -> onUpdateProfile { it.copy(autoFetchModels = af) } }
                )
                if (profile.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                    SepiaToggleRow(
                        title = PROVIDER_STORAGE_TITLE,
                        subtitle = PROVIDER_STORAGE_SUBTITLE,
                        checked = profile.keepsProviderState,
                        onCheckedChange = { store -> onUpdateProfile { it.copy(storeServerSide = store, useStatefulChaining = it.useStatefulChaining && store) } }
                    )
                    SepiaToggleRow(
                        title = "Let the provider remember this conversation",
                        subtitle = "Off by default: full history is sent locally and nothing is stored provider-side.",
                        checked = profile.useStatefulChaining,
                        onCheckedChange = { sc -> onUpdateProfile { it.copy(useStatefulChaining = sc) } }
                    )
                    SepiaToggleRow(
                        title = "Background poll & stream recovery",
                        subtitle = "Recovers a dropped stream. Needs provider storage.",
                        checked = profile.useBackgroundLongDecode,
                        onCheckedChange = { bg -> onUpdateProfile { it.copy(useBackgroundLongDecode = bg) } }
                    )
                }
                SepiaToggleRow(
                    title = "Try the older API if this one is missing",
                    checked = profile.useLegacyFallbackEndpoint,
                    onCheckedChange = { leg -> onUpdateProfile { it.copy(useLegacyFallbackEndpoint = leg) } }
                )
            }
        }

        SepiaSectionCard(
            title = "Reply settings (blank = provider default, 0 = no limit)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                    SepiaTextField(
                        value = tempText,
                        onValueChange = { v ->
                            tempText = v
                            val trimmed = v.trim()
                            if (trimmed.isEmpty()) {
                                onUpdateProfile { it.copy(temperature = null) }
                            } else {
                                trimmed.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(temperature = num) } }
                            }
                        },
                        label = "Creativity, called temperature (blank = default)",
                        placeholder = "Omitted",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SepiaTextField(
                        value = topPText,
                        onValueChange = { v ->
                            topPText = v
                            val trimmed = v.trim()
                            if (trimmed.isEmpty()) {
                                onUpdateProfile { it.copy(topP = null) }
                            } else {
                                trimmed.toDoubleOrNull()?.let { num -> onUpdateProfile { it.copy(topP = num) } }
                            }
                        },
                        label = "Word variety, called Top-P (blank = default)",
                        placeholder = "Omitted",
                        monospace = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SepiaTextField(
                    value = maxOutText,
                    onValueChange = { v ->
                        maxOutText = v
                        val trimmed = v.trim()
                        if (trimmed.isEmpty()) {
                            onUpdateProfile { it.copy(maxOutputTokens = null) }
                        } else {
                            trimmed.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(maxOutputTokens = num) } }
                        }
                    },
                    label = "Reply limit in tokens (0 or blank = none)",
                    placeholder = "Unlimited",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SepiaTextField(
                    value = budgetText,
                    onValueChange = { v ->
                        budgetText = v
                        val trimmed = v.trim()
                        if (trimmed.isEmpty()) {
                            onUpdateProfile { it.copy(thinkingBudgetTokens = null) }
                        } else {
                            trimmed.toIntOrNull()?.let { num -> onUpdateProfile { it.copy(thinkingBudgetTokens = num) } }
                        }
                    },
                    label = "Legacy thinking budget (pre-4.7 only — blank = adaptive)",
                    placeholder = "Adaptive",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
                BasicText(
                    text = "Deprecated: Claude 4.7+ rejects budget_tokens with HTTP 400. Leave blank to use adaptive thinking steered by effort.",
                    style = p.uiLabelStyle.copy(color = p.mutedInk, fontSize = 11.sp)
                )

                if (profile.protocol == ProviderProtocol.ANTHROPIC_MESSAGES && (profile.maxOutputTokens == null || profile.maxOutputTokens <= 0)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s6)
                    ) {
                        KissVectorIcon(icon = KissIcon.WARNING, tint = p.accentSecondary, size = IconSize.md)
                        BasicText(
                            text = "Anthropic requires a limit, so Parchment sends a very high one when this is blank.",
                            style = p.uiLabelStyle.copy(color = p.accentSecondary),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                SepiaTextField(
                    value = profile.customHeadersRaw,
                    onValueChange = { h -> onUpdateProfile { it.copy(customHeadersRaw = h) } },
                    label = "Custom HTTP headers (one 'Header: Value' per line)",
                    singleLine = false,
                    monospace = true,
                    minHeight = 56.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                SepiaTextField(
                    value = profile.rawJsonExtraBody,
                    onValueChange = { b -> onUpdateProfile { it.copy(rawJsonExtraBody = b) } },
                    label = "Extra request options (JSON, for advanced use)",
                    singleLine = false,
                    monospace = true,
                    minHeight = 64.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Dedicated Subpage for Per-Model Context Window, Max Output Tokens, and `models.dev` Pricing.
 * No forced minimums: `0` means unlimited and blank leaves the field out of the request.
 */
@Composable
private fun ModelConfigSubpage(
    profile: ProviderProfile,
    modelId: String,
    onUpdateProfile: ((ProviderProfile) -> ProviderProfile) -> Unit,
    onNotify: (String) -> Unit,
    onBack: () -> Unit
) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val existingSpec = profile.customModelSpecs[modelId]
    val initialPricing = profile.tokenPricing(modelId)

    var ctxText by remember(modelId, existingSpec) {
        mutableStateOf((existingSpec?.contextWindowTokens ?: profile.contextWindowTokens).toString())
    }
    var maxOutText by remember(modelId, existingSpec) {
        mutableStateOf(existingSpec?.maxOutputTokens?.toString() ?: profile.maxOutputTokens?.toString() ?: "")
    }
    var inPriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.inputPerM?.toString() ?: "")
    }
    var outPriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.outputPerM?.toString() ?: "")
    }
    var cachePriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.cacheReadPerM?.toString() ?: "")
    }
    var thinkPriceText by remember(modelId, existingSpec) {
        mutableStateOf(initialPricing.reasoningPerM?.toString() ?: "")
    }

    var writePriceText by remember(modelId, existingSpec) { mutableStateOf(initialPricing.cacheWritePerM?.toString() ?: "") }
    var longWritePriceText by remember(modelId, existingSpec) { mutableStateOf(initialPricing.longCacheWritePerM?.toString() ?: "") }

    fun saveSpec(syncedSpec: ModelPricingSpec? = null) {
        val ctx = ctxText.trim().toIntOrNull() ?: 0 // 0 = Unlimited!
        val maxOut = maxOutText.trim().toIntOrNull() // null = Stripped!
        val pricing = io.parchment.ai.model.TokenPricing(
            io.parchment.ai.model.parsePriceText(inPriceText), io.parchment.ai.model.parsePriceText(outPriceText),
            io.parchment.ai.model.parsePriceText(cachePriceText), io.parchment.ai.model.parsePriceText(writePriceText),
            io.parchment.ai.model.parsePriceText(thinkPriceText), io.parchment.ai.model.parsePriceText(longWritePriceText))
        val inP = pricing.inputPerM ?: 0.0
        val outP = pricing.outputPerM ?: 0.0
        val cacheP = pricing.cacheReadPerM ?: 0.0
        val thinkP = pricing.reasoningPerM ?: 0.0
        val priceChanged = pricing != initialPricing


        val spec = ModelPricingSpec(
            modelId = modelId,
            displayName = syncedSpec?.displayName ?: existingSpec?.displayName.orEmpty(),
            providerName = syncedSpec?.providerName ?: existingSpec?.providerName.orEmpty(),
            inputPricePerM = inP,
            outputPricePerM = outP,
            cacheReadPricePerM = cacheP,
            cacheWritePricePerM = pricing.cacheWritePerM ?: 0.0,
            knownPricing = pricing,
            contextPricingTiers = syncedSpec?.contextPricingTiers ?: if (priceChanged) emptyList() else existingSpec?.contextPricingTiers.orEmpty(),
            reasoningPricePerM = thinkP,
            contextWindowTokens = ctx,
            maxOutputTokens = maxOut,
            outputModalities = syncedSpec?.outputModalities
                ?: existingSpec?.outputModalities
                ?: ModelsDevClient.getCachedOutputModalities(modelId),
            inputModalities = syncedSpec?.inputModalities
                ?: existingSpec?.inputModalities
                ?: ModelsDevClient.getCachedInputModalities(modelId)
        )
        onUpdateProfile { prof ->
            prof.copy(
                contextWindowTokens = if (prof.defaultModel == modelId) ctx else prof.contextWindowTokens,
                maxOutputTokens = if (prof.defaultModel == modelId) maxOut else prof.maxOutputTokens,
                inputPricePerM = if (prof.defaultModel == modelId) inP else prof.inputPricePerM,
                outputPricePerM = if (prof.defaultModel == modelId) outP else prof.outputPricePerM,
                cacheReadPricePerM = if (prof.defaultModel == modelId) cacheP else prof.cacheReadPricePerM,
                cacheWritePricePerM = if (prof.defaultModel == modelId) (pricing.cacheWritePerM ?: 0.0) else prof.cacheWritePricePerM,
                knownPricing = if (prof.defaultModel == modelId) pricing else prof.knownPricing,
                pricingModelId = if (prof.defaultModel == modelId) modelId else prof.pricingModelId,
                reasoningPricePerM = if (prof.defaultModel == modelId) thinkP else prof.reasoningPricePerM,
                customModelSpecs = prof.customModelSpecs + (modelId to spec)
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.s8)
        ) {
            BasicText(
                text = "Model Settings — $modelId",
                style = p.uiHeaderStyle,
                modifier = Modifier.padding(top = Space.s4, end = Space.s8)
            )
            SepiaButton(
                text = "← Back to models",
                compact = true,
                style = SepiaButtonStyle.PRIMARY,
                onClick = {
                    saveSpec()
                    onBack()
                }
            )
        }

        SepiaSectionCard(
            title = "Conversation and reply limits",
            headerAction = {
                SepiaButton(
                    text = "Fill in from model catalog",
                    compact = true,
                    style = SepiaButtonStyle.SECONDARY,
                    onClick = {
                        scope.launch {
                            ModelsDevClient.fetchModelSpec(modelId).fold(
                                onSuccess = { spec ->
                                    ctxText = spec.contextWindowTokens.toString()
                                    maxOutText = spec.maxOutputTokens?.toString() ?: ""
                                    val syncedPricing = spec.knownPricing ?: io.parchment.ai.model.legacyTokenPricing(spec.inputPricePerM, spec.outputPricePerM, spec.cacheReadPricePerM, spec.cacheWritePricePerM, spec.reasoningPricePerM)
                                    inPriceText = syncedPricing.inputPerM?.toString() ?: ""
                                    outPriceText = syncedPricing.outputPerM?.toString() ?: ""
                                    cachePriceText = syncedPricing.cacheReadPerM?.toString() ?: ""
                                    thinkPriceText = syncedPricing.reasoningPerM?.toString() ?: ""
                                    writePriceText = syncedPricing.cacheWritePerM?.toString() ?: ""
                                    longWritePriceText = syncedPricing.longCacheWritePerM?.toString() ?: ""
                                    saveSpec(spec)
                                    onNotify("Synced $modelId from the model catalog.")
                                },
                                onFailure = { err -> onNotify("Model catalog: ${err.message}") }
                            )
                        }
                    }
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s10)) {
                SepiaTextField(value = ctxText, onValueChange = { ctxText = it; saveSpec() },
                    label = "Conversation limit in tokens (0 = none)", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = maxOutText, onValueChange = { maxOutText = it; saveSpec() },
                    label = "Reply limit in tokens (blank = default)", monospace = true, modifier = Modifier.fillMaxWidth())
                BasicText("Prices are USD per million tokens. Blank means unknown; 0 means free.", style = p.uiLabelStyle)
                SepiaTextField(value = inPriceText, onValueChange = { inPriceText = it; saveSpec() },
                    label = "Input", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = outPriceText, onValueChange = { outPriceText = it; saveSpec() },
                    label = "Output", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = cachePriceText, onValueChange = { cachePriceText = it; saveSpec() },
                    label = "Reused input", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = writePriceText, onValueChange = { writePriceText = it; saveSpec() },
                    label = "Saved for reuse", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = longWritePriceText, onValueChange = { longWritePriceText = it; saveSpec() },
                    label = "Saved longer for reuse", monospace = true, modifier = Modifier.fillMaxWidth())
                SepiaTextField(value = thinkPriceText, onValueChange = { thinkPriceText = it; saveSpec() },
                    label = "Reasoning", monospace = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
