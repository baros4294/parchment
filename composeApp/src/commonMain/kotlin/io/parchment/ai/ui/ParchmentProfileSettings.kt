package io.parchment.ai.ui

import androidx.compose.foundation.Image
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ThemeConfig
import io.parchment.ai.platform.PlatformPickers
import io.parchment.ai.platform.PlatformRuntime
import io.parchment.ai.platform.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A chat with its own name keeps it; otherwise the profile name applies, including to {{user}}. */
internal fun ChatSession.withProfileName(preferences: PreferencesConfig): ChatSession =
    if (preferences.profileName.isBlank() || (userName.isNotBlank() && userName != "User")) this
    else copy(userName = preferences.profileName)

@Composable
internal fun ProfilePicture(picture: ImageBitmap?, size: Dp) {
    val p = LocalParchmentPalette.current
    if (picture != null) {
        Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(p.elevatedBg).border(LineWidth.hairline, p.border, CircleShape),
            contentAlignment = Alignment.Center
        ) { KissVectorIcon(KissIcon.USER, tint = p.narrationInk, size = size * 0.56f) }
    }
}

@Composable
internal fun ProfileCard(prefs: PreferencesConfig, onUpdatePreferences: (PreferencesConfig) -> Unit, onNotify: (String) -> Unit) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val picture = rememberFileImage(prefs.profilePicturePath).bitmap
    SepiaSectionCard(title = Strings.yourProfile) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(picture, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                SepiaTextField(
                    value = prefs.profileName,
                    onValueChange = { onUpdatePreferences(prefs.copy(profileName = it, showUserName = prefs.showUserName || it.isNotBlank())) },
                    placeholder = Strings.yourName,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                    SepiaButton(
                        text = if (picture == null) "Choose picture" else "Change picture",
                        compact = true,
                        onClick = {
                            scope.launch {
                                val picked = PlatformPickers.pickFileForImport(listOf("png", "jpg", "jpeg", "webp")) ?: return@launch
                                if (picked.oversized) {
                                    onNotify(Strings.thatPictureIsTooLarge)
                                    return@launch
                                }
                                val extension = picked.fileName.substringAfterLast('.', "png").lowercase()
                                val saved = PlatformStorage.saveGeneratedMediaFile("profile-picture-${PlatformRuntime.currentTimeMillis()}.$extension", picked.bytes)
                                if (withContext(Dispatchers.Default) { PlatformStorage.loadImageBitmapFromFile(saved) } == null) {
                                    onNotify(Strings.thatFileCouldNotBeRead)
                                    return@launch
                                }
                                onUpdatePreferences(prefs.copy(profilePicturePath = saved, showUserAvatar = true))
                            }
                        }
                    )
                    if (picture != null) {
                        SepiaButton(
                            text = Strings.remove,
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdatePreferences(prefs.copy(profilePicturePath = "")) }
                        )
                    }
                }
            }
        }
        BasicText(Strings.shownOnYourMessagesModelsSee, style = p.uiLabelStyle)
    }
}

@Composable
internal fun SpendLimitCard(prefs: PreferencesConfig, onUpdatePreferences: (PreferencesConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    var limit by remember(prefs.chatSpendLimitUsd) {
        mutableStateOf(if (prefs.chatSpendLimitUsd > 0.0) prefs.chatSpendLimitUsd.toString() else "")
    }
    SepiaSectionCard(title = Strings.spendLimit) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            SepiaTextField(
                value = limit,
                onValueChange = { typed ->
                    limit = typed
                    val amount = if (typed.isBlank()) 0.0 else typed.trim().toDoubleOrNull()
                    if (amount != null && amount >= 0.0) onUpdatePreferences(prefs.copy(chatSpendLimitUsd = amount))
                },
                placeholder = Strings.noLimitUsdPerChat,
                modifier = Modifier.fillMaxWidth()
            )
            BasicText(Strings.stopsAChatBeforeItsNext, style = p.uiLabelStyle)
        }
    }
}

@Composable
internal fun ThemePresetChoices(theme: ThemeConfig, onUpdateTheme: (ThemeConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        BasicText(Strings.colorModePreset, style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium))
        // Small previews instead of a list of names, so themes can be compared at a glance.
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s8)
        ) {
            ThemeConfig.PRESETS.forEach { preset ->
                ThemePreviewCard(preset, selected = theme.name == preset.name, onClick = { onUpdateTheme(preset) })
            }
        }
    }
}

/** A miniature of the app in [preset]: sidebar, page, a line of text and the accent rule. */
@Composable
private fun ThemePreviewCard(preset: ThemeConfig, selected: Boolean, onClick: () -> Unit) {
    val p = LocalParchmentPalette.current
    val shape = RoundedCornerShape(Radius.r10)
    val pageShape = RoundedCornerShape(Radius.r6)
    Column(
        Modifier
            .textScaledWidth(132.dp)
            .kissClickable(role = Role.RadioButton, selected = selected, pressScale = 0.97f, hoverScale = 1.01f, onClick = onClick)
            .clip(shape)
            .border(if (selected) LineWidth.selected else LineWidth.hairline, if (selected) p.accent else p.border, shape)
            .padding(Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s6)
    ) {
        // The miniature is decoration: screen readers hear only the theme's name.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(pageShape).background(parseHexColor(preset.canvasBgHex)).clearAndSetSemantics {}) {
            Box(Modifier.width(Space.s24).fillMaxHeight().background(parseHexColor(preset.sheetBgHex)))
            Column(Modifier.padding(Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                BasicText(Strings.aa, style = p.uiBodyStyle.copy(color = parseHexColor(preset.primaryInkHex), fontWeight = FontWeight.SemiBold))
                Box(Modifier.width(40.dp).height(Space.s4).clip(RoundedCornerShape(Radius.pill)).background(parseHexColor(preset.accentHex)))
            }
        }
        BasicText(
            preset.name.substringBefore(" ("),
            style = p.uiLabelStyle.copy(color = if (selected) p.accent else p.primaryInk, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
