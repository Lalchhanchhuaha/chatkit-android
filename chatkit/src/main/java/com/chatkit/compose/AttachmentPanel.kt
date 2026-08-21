package com.chatkit.compose

import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircleFilled
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Internal data model — never leaves this file
// ---------------------------------------------------------------------------

private data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mediaType: MediaType,
    val dateAdded: Long, // seconds since epoch, used for sorting
)

// ---------------------------------------------------------------------------
// Panel
// ---------------------------------------------------------------------------

/** Lightweight launcher surface; actual selection is delegated to system contracts. */
@Composable
internal fun AttachmentPanel(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    showsDocumentAttachments: Boolean,
    showsCamera: Boolean,
    maximumMediaSelection: Int,
    documentSelectionCount: Int,
    selectedMedia: List<ChatMediaAttachment>,
    onClose: () -> Unit,
    onMediaSelectionChanged: (List<ChatMediaAttachment>) -> Unit,
    onDocumentPickerRequested: () -> Unit,
    onCameraRequested: () -> Unit,
    onFallbackPickMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Permissions required for MediaStore access.
    val readPermissions: Array<String> = remember(showsVideoAttachments) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            buildList {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                if (showsVideoAttachments) add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }.toTypedArray()
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            readPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> hasPermission = grants.values.any { it } }

    // Load all media items from MediaStore (IDs + URIs only — no bitmaps in the list).
    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(hasPermission, showsVideoAttachments) {
        if (!hasPermission) return@LaunchedEffect
        mediaItems = withContext(Dispatchers.IO) {
            loadMediaItems(context.contentResolver, showsVideoAttachments)
        }
    }

    // O(1) set for selection lookup.
    val selectedIds: Set<String> = remember(selectedMedia) {
        selectedMedia.mapTo(HashSet()) { it.id }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.attachmentPanelBackgroundColor),
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text("Cancel", color = theme.accentColor)
            }
            Text(
                text = if (showsVideoAttachments) "Photos & Videos" else "Photos",
                modifier = Modifier.weight(1f),
                color = theme.incomingTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onClose,
                enabled = selectedMedia.isNotEmpty(),
            ) {
                Text(
                    text = if (selectedMedia.isNotEmpty()) "Done (${selectedMedia.size})" else "Done",
                    color = if (selectedMedia.isNotEmpty()) theme.accentColor
                    else theme.incomingTimestampColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))

        // ── Action chip row ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showsCamera) {
                ActionChip(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    theme = theme,
                    onClick = onCameraRequested,
                )
            }
            ActionChip(
                icon = Icons.Default.Image,
                label = "Gallery",
                theme = theme,
                onClick = onFallbackPickMedia,
            )
            if (showsDocumentAttachments) {
                ActionChip(
                    icon = Icons.Default.FolderOpen,
                    label = if (documentSelectionCount > 0) "Docs ($documentSelectionCount)"
                    else "Documents",
                    theme = theme,
                    onClick = onDocumentPickerRequested,
                )
            }
        }

        HorizontalDivider(color = Color.Black.copy(alpha = 0.06f))

        // ── Permission gate / inline photo grid ───────────────────────────
        if (!hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Allow access to your photos to browse and pick them here.",
                        color = theme.incomingTimestampColor,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 36.dp),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(theme.accentColor)
                            .clickable { permissionLauncher.launch(readPermissions) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Allow Access",
                            color = theme.accentContentColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        } else {
            // 3-column photo grid — matches iOS PHPicker column count.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 370.dp),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(mediaItems, key = { it.id }) { item ->
                    val idStr = item.uri.toString()
                    val isSelected = idStr in selectedIds
                    val selectionNumber =
                        if (isSelected) selectedMedia.indexOfFirst { it.id == idStr } + 1 else 0

                    MediaThumbnailCell(
                        item = item,
                        isSelected = isSelected,
                        selectionNumber = selectionNumber,
                        showsNumber = maximumMediaSelection > 1,
                        theme = theme,
                        onClick = {
                            val updated = selectedMedia.toMutableList()
                            if (isSelected) {
                                updated.removeAll { it.id == idStr }
                            } else if (updated.size < maximumMediaSelection) {
                                updated += ChatMediaAttachment(
                                    id = idStr,
                                    mediaType = item.mediaType,
                                    localUri = item.uri,
                                )
                            }
                            onMediaSelectionChanged(updated)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// Internal composables
// ---------------------------------------------------------------------------

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    theme: ChatTheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(theme.attachmentTileBackgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = theme.accentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = theme.incomingTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MediaThumbnailCell(
    item: MediaItem,
    isSelected: Boolean,
    selectionNumber: Int,
    showsNumber: Boolean,
    theme: ChatTheme,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    // Thumbnails are loaded lazily as cells become visible on the IO dispatcher.
    val thumbnail by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadThumbnail(context.contentResolver, item) }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(theme.thumbnailPlaceholderBackgroundColor)
            .clickable(onClick = onClick),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // Video play badge — bottom-left
        if (item.mediaType == MediaType.Video) {
            Icon(
                imageVector = Icons.Default.PlayCircleFilled,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(20.dp),
            )
        }

        // Blue tint overlay when selected
        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(theme.accentColor.copy(alpha = 0.28f))
            )
        }

        // Circular selection badge — top-right
        // Hollow ring when unselected; filled with number (or checkmark) when selected.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) theme.accentColor else Color.Black.copy(alpha = 0.22f)
                )
                .then(
                    if (!isSelected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                if (showsNumber) {
                    Text(
                        text = selectionNumber.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MediaStore helpers — runs on Dispatchers.IO
// ---------------------------------------------------------------------------

private fun loadMediaItems(
    cr: android.content.ContentResolver,
    includeVideos: Boolean,
): List<MediaItem> {
    val items = ArrayList<MediaItem>(200)

    // Photos
    cr.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
        null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            items += MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                mediaType = MediaType.Photo,
                dateAdded = cursor.getLong(dateCol),
            )
        }
    }

    // Videos
    if (includeVideos) {
        cr.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
            null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += MediaItem(
                    id = id + Long.MAX_VALUE / 2, // avoid ID collision with image IDs
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    mediaType = MediaType.Video,
                    dateAdded = cursor.getLong(dateCol),
                )
            }
        }
    }

    // Interleave photos + videos by capture date descending.
    items.sortByDescending { it.dateAdded }
    return items
}

private fun loadThumbnail(
    cr: android.content.ContentResolver,
    item: MediaItem,
): ImageBitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+: OS-cached thumbnail, fastest path.
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
                // MediaMetadataRetriever works without a file path on API 24+.
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

/** Decode a downsampled bitmap from a content URI without loading the full image. */
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
        val (h, w) = opts.outHeight to opts.outWidth
        while (h / (size * 2) >= reqHeight && w / (size * 2) >= reqWidth) size *= 2
        size
    }
    opts.inJustDecodeBounds = false
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?.asImageBitmap()
}


