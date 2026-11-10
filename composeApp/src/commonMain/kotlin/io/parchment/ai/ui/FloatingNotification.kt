package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FloatingNotification(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalParchmentPalette.current
    var lastMessage by remember { mutableStateOf(message) }
    SideEffect { if (message.isNotBlank()) lastMessage = message }
    val visibleMessage = message.ifBlank { lastMessage }
    val shape = RoundedCornerShape(12.dp)
    AnimatedVisibility(
        visible = message.isNotBlank(),
        modifier = modifier.widthIn(max = 560.dp),
        enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { -it / 3 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 3 }
    ) {
        Row(
            modifier = Modifier
                .shadow(8.dp, shape)
                .clip(shape)
                .background(palette.elevatedBg)
                .border(0.75.dp, palette.border.copy(alpha = 0.7f), shape)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KissVectorIcon(KissIcon.CIRCLE_DOT, palette.accent, size = 14.dp)
            BasicText(
                text = visibleMessage,
                style = palette.uiBodyStyle.copy(fontSize = 14.sp, color = palette.primaryInk, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Dismiss notification" },
                contentAlignment = Alignment.Center
            ) { KissVectorIcon(KissIcon.CLOSE, palette.mutedInk, size = 16.dp) }
        }
    }
}
