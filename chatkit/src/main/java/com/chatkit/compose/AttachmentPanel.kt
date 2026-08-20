package com.chatkit.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lightweight launcher surface; actual selection is delegated to system contracts. */
@Composable
internal fun AttachmentPanel(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    showsDocumentAttachments: Boolean,
    showsCamera: Boolean,
    @Suppress("UNUSED_PARAMETER") maximumMediaSelection: Int,
    documentSelectionCount: Int,
    selectedMedia: List<ChatMediaAttachment>,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onMediaSelectionChanged: (List<ChatMediaAttachment>) -> Unit,
    onDocumentPickerRequested: () -> Unit,
    onCameraRequested: () -> Unit,
    onFallbackPickMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.attachmentPanelBackgroundColor)
            .padding(bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Cancel", color = theme.accentColor) }
            Text(
                "Attachments",
                modifier = Modifier.weight(1f),
                color = theme.incomingTextColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AttachmentAction(
                label = if (showsVideoAttachments) "Photos & videos" else "Photos",
                symbol = "▧",
                badge = selectedMedia.size,
                theme = theme,
                onClick = onFallbackPickMedia,
            )
            if (showsDocumentAttachments) {
                AttachmentAction(
                    label = "Documents",
                    symbol = "▤",
                    badge = documentSelectionCount,
                    theme = theme,
                    onClick = onDocumentPickerRequested,
                )
            }
            if (showsCamera) {
                AttachmentAction(
                    label = "Camera",
                    symbol = "●",
                    badge = 0,
                    theme = theme,
                    onClick = onCameraRequested,
                )
            }
        }
    }
}

@Composable
private fun AttachmentAction(
    label: String,
    symbol: String,
    badge: Int,
    theme: ChatTheme,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(52.dp).clip(CircleShape).background(theme.accentColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = theme.accentContentColor, fontSize = 22.sp)
            if (badge > 0) {
                Text(
                    badge.toString(),
                    color = theme.accentContentColor,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        Text(label, color = theme.incomingTextColor, fontSize = 12.sp)
    }
}
