package com.chatkit.compose

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mediaType: MediaType,
    val dateAdded: Long,
    val durationMillis: Long? = null,
)

private enum class MediaTab { Photos, Videos }

private val PanelTopShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

/**
 * In-chat attachment panel matching iOS `AttachmentPickerPanel`:
 * Cancel + Photos/Videos segmented control, 4-column MediaStore grid,
 * optional Document tile, numbered selection badges.
 */
@Composable
internal fun AttachmentPanel(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    showsDocumentAttachments: Boolean,
    maximumMediaSelection: Int,
    documentSelectionCount: Int,
    selectedMedia: List<ChatMediaAttachment>,
    onClose: () -> Unit,
    onMediaSelectionChanged: (List<ChatMediaAttachment>) -> Unit,
    onDocumentPickerRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MediaTab.Photos) }
    val activeType = if (selectedTab == MediaTab.Videos && showsVideoAttachments) {
        MediaType.Video
    } else {
        MediaType.Photo
    }

    val readPermissions: Array<String> = remember(activeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (activeType) {
                MediaType.Photo -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
                MediaType.Video -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var hasPermission by remember(activeType) {
        mutableStateOf(
            readPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermission = grants.values.any { it }
    }

    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(hasPermission, activeType) {
        if (!hasPermission) {
            mediaItems = emptyList()
            return@LaunchedEffect
        }
        mediaItems = withContext(Dispatchers.IO) {
            loadMediaItems(context.contentResolver, activeType)
        }
    }

    val selectedIds = remember(selectedMedia) {
        selectedMedia.mapTo(HashSet()) { it.id }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .clip(PanelTopShape)
            .background(theme.attachmentPanelBackgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose, modifier = Modifier.width(72.dp)) {
                Text("Cancel", color = theme.accentColor, maxLines = 1)
            }
            if (showsVideoAttachments) {
                MediaTabSegmented(
                    selected = selectedTab,
                    theme = theme,
                    onSelect = { tab ->
                        if (tab != selectedTab) {
                            selectedTab = tab
                            onMediaSelectionChanged(emptyList())
                            val needed = readPermissionsFor(tab)
                            hasPermission = needed.all {
                                ContextCompat.checkSelfPermission(context, it) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = "Photos",
                    modifier = Modifier.weight(1f),
                    color = theme.incomingTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.width(72.dp))
        }

        HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))

        when {
            !hasPermission -> {
                PermissionGate(
                    theme = theme,
                    showsVideo = activeType == MediaType.Video,
                    onAllow = { permissionLauncher.launch(readPermissions) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            },
                        )
                    },
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 288.dp),
                    contentPadding = PaddingValues(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showsDocumentAttachments) {
                        item(key = "documents") {
                            DocumentTile(
                                theme = theme,
                                selectionCount = documentSelectionCount,
                                onClick = onDocumentPickerRequested,
                            )
                        }
                    }
                    items(mediaItems, key = { it.id }) { item ->
                        val idStr = item.uri.toString()
                        val isSelected = idStr in selectedIds
                        val selectionIndex =
                            if (isSelected) selectedMedia.indexOfFirst { it.id == idStr } else -1
                        MediaThumbnailCell(
                            item = item,
                            selectionIndex = selectionIndex.takeIf { it >= 0 },
                            theme = theme,
                            onClick = {
                                val updated = selectedMedia.toMutableList()
                                if (isSelected) {
                                    updated.removeAll { it.id == idStr }
                                } else if (updated.size < maximumMediaSelection.coerceAtLeast(1)) {
                                    updated += ChatMediaAttachment(
                                        id = idStr,
                                        mediaType = item.mediaType,
                                        localUri = item.uri,
                                        durationMillis = item.durationMillis,
                                    )
                                }
                                onMediaSelectionChanged(updated)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun readPermissionsFor(tab: MediaTab): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (tab) {
            MediaTab.Photos -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            MediaTab.Videos -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        }
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@Composable
private fun MediaTabSegmented(
    selected: MediaTab,
    theme: ChatTheme,
    onSelect: (MediaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF787880).copy(alpha = 0.12f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        MediaTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.name,
                    color = theme.incomingTextColor,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(
    theme: ChatTheme,
    showsVideo: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (showsVideo) {
                "Allow photo access in Settings to choose images and videos."
            } else {
                "Allow photo access in Settings to choose images."
            },
            color = theme.incomingTimestampColor,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(theme.accentColor)
                    .clickable(onClick = onAllow)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text("Allow Access", color = theme.accentContentColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(theme.attachmentTileBackgroundColor)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text("Open Settings", color = theme.incomingTextColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DocumentTile(
    theme: ChatTheme,
    selectionCount: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(theme.attachmentTileBackgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(theme.accentColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = theme.accentContentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Document",
                color = theme.incomingTextColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (selectionCount > 0) {
            SelectionBadge(
                index = selectionCount - 1,
                selected = true,
                theme = theme,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun MediaThumbnailCell(
    item: MediaItem,
    selectionIndex: Int?,
    theme: ChatTheme,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadThumbnail(context.contentResolver, item) }.getOrNull()
        }
    }
    val selected = selectionIndex != null

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(theme.thumbnailPlaceholderBackgroundColor)
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(3.dp, theme.accentColor) else Modifier,
            ),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        SelectionBadge(
            index = selectionIndex,
            selected = selected,
            theme = theme,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp),
        )
        if (item.mediaType == MediaType.Video) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = theme.accentContentColor,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = formatMediaDuration(item.durationMillis ?: 0L),
                    color = theme.accentContentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SelectionBadge(
    index: Int?,
    selected: Boolean,
    theme: ChatTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) theme.accentColor else Color.Black.copy(alpha = 0.28f))
            .border(2.dp, theme.accentContentColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && index != null) {
            Text(
                text = (index + 1).toString(),
                color = theme.accentContentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun formatMediaDuration(durationMillis: Long): String {
    val totalSeconds = ((durationMillis + 500) / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun loadMediaItems(
    cr: android.content.ContentResolver,
    type: MediaType,
): List<MediaItem> {
    val items = ArrayList<MediaItem>(200)
    when (type) {
        MediaType.Photo -> {
            cr.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                var count = 0
                while (cursor.moveToNext() && count < 300) {
                    val id = cursor.getLong(idCol)
                    items += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        mediaType = MediaType.Photo,
                        dateAdded = cursor.getLong(dateCol),
                    )
                    count++
                }
            }
        }
        MediaType.Video -> {
            cr.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DURATION,
                ),
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                var count = 0
                while (cursor.moveToNext() && count < 300) {
                    val id = cursor.getLong(idCol)
                    items += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        mediaType = MediaType.Video,
                        dateAdded = cursor.getLong(dateCol),
                        durationMillis = cursor.getLong(durationCol),
                    )
                    count++
                }
            }
        }
    }
    return items
}

private fun loadThumbnail(
    cr: android.content.ContentResolver,
    item: MediaItem,
): ImageBitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cr.loadThumbnail(item.uri, Size(256, 256), null).asImageBitmap()
    } else {
        when (item.mediaType) {
            MediaType.Photo -> {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    cr,
                    item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )?.asImageBitmap() ?: decodeSampledBitmap(cr, item.uri, 256, 256)
            }
            MediaType.Video -> {
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    r.setDataSource(null as android.content.Context?, item.uri)
                    val bmp = r.getFrameAtTime(0)
                    r.release()
                    bmp?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
}

private fun decodeSampledBitmap(
    cr: android.content.ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
): ImageBitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    opts.inSampleSize = run {
        var size = 1
        var halfW = opts.outWidth / 2
        var halfH = opts.outHeight / 2
        while (halfW / size >= reqWidth && halfH / size >= reqHeight) size *= 2
        size
    }
    opts.inJustDecodeBounds = false
    return cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
    }
}
