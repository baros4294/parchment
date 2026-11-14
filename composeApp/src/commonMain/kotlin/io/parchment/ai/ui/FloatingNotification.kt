package io.parchment.ai.ui

import androidx.compose.animation.AnimatedVisibility
import io.parchment.ai.strings.Strings
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
internal fun FloatingNotification(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** An action offered with the message, such as "Undo"; null shows none. */
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    val palette = LocalParchmentPalette.current
    var lastMessage by remember { mutableStateOf(message) }
    SideEffect { if (message.isNotBlank()) lastMessage = message }
    val visibleMessage = message.ifBlank { lastMessage }
    val shape = RoundedCornerShape(Radius.r12)
    AnimatedVisibility(
        visible = message.isNotBlank(),
        modifier = modifier.widthIn(max = 560.dp),
        enter = fadeIn(motionTween(Motion.STANDARD)) + slideInVertically(motionTween(Motion.MEDIUM)) { -it / 3 },
        exit = fadeOut(motionTween(Motion.STANDARD)) + slideOutVertically(motionTween(Motion.STANDARD)) { -it / 3 }
    ) {
        Row(
            modifier = Modifier
                .overlayShadow(shape)
                .clip(shape)
                .background(palette.elevatedBg)
                .border(LineWidth.hairline, palette.border.copy(alpha = 0.7f), shape)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(start = Space.s16, end = Space.s4, top = Space.s4, bottom = Space.s4)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(Space.s10),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KissVectorIcon(KissIcon.CIRCLE_DOT, palette.accent, size = 14.dp)
            BasicText(
                text = visibleMessage,
                style = palette.uiBodyStyle.copy(fontSize = 14.sp, color = palette.primaryInk, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            )
            if (actionLabel != null) {
                SepiaButton(text = actionLabel, compact = true, style = SepiaButtonStyle.OUTLINE, onClick = onAction)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = Strings.dismissNotification },
                contentAlignment = Alignment.Center
            ) { KissVectorIcon(KissIcon.CLOSE, palette.mutedInk, size = IconSize.lg) }
        }
    }
}
