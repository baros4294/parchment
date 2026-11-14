package io.parchment.ai.ui

import androidx.compose.foundation.background
import io.parchment.ai.strings.Strings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Full-window overlays shown over the app: dropping files, and the confirm dialogs people can turn off.

/** Shown while files are dragged over the window. */
@Composable
internal fun FileDropOverlay() {
    val p = LocalParchmentPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.canvasBg.copy(alpha = 0.88f))
            .border(LineWidth.focus, p.accent, RoundedCornerShape(Radius.r12))
            .padding(Space.s24),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s10)) {
            KissVectorIcon(icon = KissIcon.UPLOAD, tint = p.accent, size = 34.dp)
            BasicText(Strings.dropImagesVideosOrFilesTo, style = p.uiHeaderStyle.copy(fontSize = 20.sp, color = p.accent))
            BasicText(
                Strings.supportsPngJpgWebpGifMp4,
                style = p.uiLabelStyle.copy(color = p.narrationInk)
            )
        }
    }
}

/** A dimmed backdrop that closes on a click outside, with a confirmation card in the middle. */
@Composable
private fun ConfirmBackdrop(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).dismissOnOutsideTap(onDismiss),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Asks before deleting a chat, every chat of an assistant, or a chat's messages. Deleted chats can still be undone. */
@Composable
internal fun DeleteConfirmDialog(
    request: DeleteConfirmRequest,
    askBeforeDeleting: Boolean,
    onAskBeforeDeletingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val (title, body, confirm) = when (request) {
        is DeleteConfirmRequest.SingleSession -> Triple(
            Strings.deleteChat2, Strings.deleteYouCanUndoThisFor(request.title), Strings.delete)
        is DeleteConfirmRequest.ClearAllSessions -> Triple(
            Strings.deleteAllChats,
            "Delete all ${plural(request.count, "chat")} for '${request.assistantName}'? The assistant and its system prompt stay. You can undo this for a few seconds afterwards.",
            Strings.deleteAll)
        is DeleteConfirmRequest.ClearCurrentMessages -> Triple(
            Strings.clearMessages, Strings.clearAllMessagesIn(request.title), Strings.clear)
    }
    ConfirmBackdrop(onDismiss) {
        SepiaConfirmationCard(
            title = title,
            body = body,
            options = {
                SepiaToggleRow(title = Strings.askForConfirmationBeforeDeleting, checked = askBeforeDeleting, onCheckedChange = onAskBeforeDeletingChange)
            }
        ) {
            SepiaButton(text = Strings.cancel, compact = true, style = SepiaButtonStyle.OUTLINE, onClick = onDismiss)
            SepiaButton(text = confirm, compact = true, style = SepiaButtonStyle.DANGER, onClick = onConfirm)
        }
    }
}

/** Asks before regenerating a reply. */
@Composable
internal fun RegenerateConfirmDialog(
    replacesReply: Boolean,
    askBeforeRegenerating: Boolean,
    onAskBeforeRegeneratingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ConfirmBackdrop(onDismiss) {
        SepiaConfirmationCard(
            title = Strings.regenerateReply,
            body = if (replacesReply) Strings.thisWillReplaceTheCurrentReply else Strings.thisWillGenerateANewVersion,
            options = {
                SepiaToggleRow(title = Strings.askForConfirmationBeforeRegenerating, checked = askBeforeRegenerating, onCheckedChange = onAskBeforeRegeneratingChange)
            }
        ) {
            SepiaButton(text = Strings.cancel, compact = true, style = SepiaButtonStyle.OUTLINE, onClick = onDismiss)
            SepiaButton(text = Strings.regenerate, compact = true, style = SepiaButtonStyle.PRIMARY, onClick = onConfirm)
        }
    }
}
