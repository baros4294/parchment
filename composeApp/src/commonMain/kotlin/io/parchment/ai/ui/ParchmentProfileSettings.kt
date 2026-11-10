package io.parchment.ai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.parchment.ai.platform.PlatformBridge
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
            Modifier.size(size).clip(CircleShape).background(p.elevatedBg).border(0.75.dp, p.border, CircleShape),
            contentAlignment = Alignment.Center
        ) { KissVectorIcon(KissIcon.USER, tint = p.narrationInk, size = size * 0.56f) }
    }
}

@Composable
internal fun ProfileCard(prefs: PreferencesConfig, onUpdatePreferences: (PreferencesConfig) -> Unit, onNotify: (String) -> Unit) {
    val p = LocalParchmentPalette.current
    val scope = rememberCoroutineScope()
    val picture = rememberFileImage(prefs.profilePicturePath).bitmap
    SepiaSectionCard(title = "Your profile") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(picture, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SepiaTextField(
                    value = prefs.profileName,
                    onValueChange = { onUpdatePreferences(prefs.copy(profileName = it, showUserName = prefs.showUserName || it.isNotBlank())) },
                    placeholder = "Your name",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SepiaButton(
                        text = if (picture == null) "Choose picture" else "Change picture",
                        compact = true,
                        onClick = {
                            scope.launch {
                                val picked = PlatformBridge.pickFileForImport(listOf("png", "jpg", "jpeg", "webp")) ?: return@launch
                                if (picked.oversized) {
                                    onNotify("That picture is too large.")
                                    return@launch
                                }
                                val extension = picked.fileName.substringAfterLast('.', "png").lowercase()
                                val saved = PlatformBridge.saveGeneratedMediaFile("profile-picture-${PlatformBridge.currentTimeMillis()}.$extension", picked.bytes)
                                if (withContext(Dispatchers.Default) { PlatformBridge.loadImageBitmapFromFile(saved) } == null) {
                                    onNotify("That file could not be read as a picture.")
                                    return@launch
                                }
                                onUpdatePreferences(prefs.copy(profilePicturePath = saved, showUserAvatar = true))
                            }
                        }
                    )
                    if (picture != null) {
                        SepiaButton(
                            text = "Remove",
                            compact = true,
                            style = SepiaButtonStyle.GHOST,
                            onClick = { onUpdatePreferences(prefs.copy(profilePicturePath = "")) }
                        )
                    }
                }
            }
        }
        BasicText("Shown on your messages. Models see your name in chats that don't set their own.", style = p.uiLabelStyle)
    }
}

@Composable
internal fun SpendLimitCard(prefs: PreferencesConfig, onUpdatePreferences: (PreferencesConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    var limit by remember(prefs.chatSpendLimitUsd) {
        mutableStateOf(if (prefs.chatSpendLimitUsd > 0.0) prefs.chatSpendLimitUsd.toString() else "")
    }
    SepiaSectionCard(title = "Spend limit") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SepiaTextField(
                value = limit,
                onValueChange = { typed ->
                    limit = typed
                    val amount = if (typed.isBlank()) 0.0 else typed.trim().toDoubleOrNull()
                    if (amount != null && amount >= 0.0) onUpdatePreferences(prefs.copy(chatSpendLimitUsd = amount))
                },
                placeholder = "No limit (USD per chat)",
                modifier = Modifier.fillMaxWidth()
            )
            BasicText("Stops a chat before its next request once it reaches this amount. Your messages stay. Uses the estimated cost, so models without prices are not limited.", style = p.uiLabelStyle)
        }
    }
}

@Composable
internal fun ThemePresetChoices(theme: ThemeConfig, onUpdateTheme: (ThemeConfig) -> Unit) {
    val p = LocalParchmentPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Color mode & preset", style = p.uiBodyStyle.copy(fontWeight = FontWeight.Medium))
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            ThemeConfig.PRESETS.forEach { preset ->
                SepiaChoiceRow(preset.name.substringBefore(" ("), theme.name == preset.name,
                    onClick = { onUpdateTheme(preset) },
                    leading = { ThemeSwatch(parseHexColor(preset.canvasBgHex), parseHexColor(preset.accentHex)) })
            }
        }
    }
}
