package com.chatkit.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelTopShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

/**
 * Lightweight attachment chooser that launches the **Android Photo Picker**
 * (or the system document picker) instead of browsing the full MediaStore library.
 *
 * Google Play's Photo and Video Permissions policy requires apps that only need
 * occasional photo/video access to use the system photo picker and avoid
 * `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`.
 */
@Composable
internal fun AttachmentPanel(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    showsDocumentAttachments: Boolean,
    onClose: () -> Unit,
    onPhotoLibraryRequested: () -> Unit,
    onDocumentPickerRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelTopShape)
            .background(theme.attachmentPanelBackgroundColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = "Cancel",
                color = theme.accentColor,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Text(
                text = "Attach",
                modifier = Modifier.align(Alignment.Center),
                color = theme.incomingTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }

        HorizontalDivider(color = theme.incomingTimestampColor.copy(alpha = 0.18f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            AttachmentActionTile(
                theme = theme,
                icon = Icons.Default.PhotoLibrary,
                label = if (showsVideoAttachments) "Photos & videos" else "Photos",
                onClick = onPhotoLibraryRequested,
                modifier = Modifier.weight(1f),
            )
            if (showsDocumentAttachments) {
                AttachmentActionTile(
                    theme = theme,
                    icon = Icons.Default.InsertDriveFile,
                    label = "Document",
                    onClick = onDocumentPickerRequested,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AttachmentActionTile(
    theme: ChatTheme,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(theme.attachmentTileBackgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(theme.accentColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = theme.accentContentColor,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            color = theme.incomingTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
