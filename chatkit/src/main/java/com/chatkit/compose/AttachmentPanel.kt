package com.chatkit.compose

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mediaType: MediaType,
    val dateAdded: Long,
    val durationMillis: Long? = null,
    /** Upright width / height from MediaStore metadata when available. */
    val aspectRatio: Float? = null,
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
    val gridState = rememberLazyGridState()
    LaunchedEffect(hasPermission, activeType) {
        if (!hasPermission) {
            mediaItems = emptyList()
            return@LaunchedEffect
        }
        // Match iOS PhotoLibraryStore: fetch newest-first up to fetchLimit,
        // but publish the first page immediately so the grid isn't empty while
        // the rest of the library arrives on a background thread.
        mediaItems = emptyList()
        var offset = 0
        while (offset < MediaQueryLimit) {
            val page = withContext(Dispatchers.IO) {
                loadMediaItems(
                    cr = context.contentResolver,
                    type = activeType,
                    limit = MediaPageSize,
                    offset = offset,
                )
            }
            if (page.isEmpty()) break
            mediaItems = mediaItems + page
            offset += page.size
            if (page.size < MediaPageSize) break
            yield()
        }
    }

    val selectedIds = remember(selectedMedia) {
        selectedMedia.mapTo(HashSet()) { it.id }
    }

    Column(
        // Height comes from ChatView (iOS attachmentPickerHeight, clamped 150…340).
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
                    segmentModifier = Modifier
                        .align(Alignment.Center)
                        .width(210.dp),
                )
            } else {
                Text(
                    text = "Photos",
                    modifier = Modifier.align(Alignment.Center),
                    color = theme.incomingTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
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
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                    items(
                        items = mediaItems,
                        key = { it.id },
                        contentType = { "media" },
                    ) { item ->
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
                                    // Prefer MediaStore metadata only — never decode the
                                    // full asset on the UI thread when selecting.
                                    updated += ChatMediaAttachment(
                                        id = idStr,
                                        mediaType = item.mediaType,
                                        localUri = item.uri,
                                        durationMillis = item.durationMillis,
                                        aspectRatio = item.aspectRatio,
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
    segmentModifier: Modifier = Modifier,
) {
    val tabs = MediaTab.entries
    val trackShape = RoundedCornerShape(10.dp)
    val thumbShape = RoundedCornerShape(8.dp)

    Row(
        modifier = segmentModifier
            .height(36.dp)
            .clip(trackShape)
            .background(theme.composerButtonBackgroundColor)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in tabs) {
            val active = tab == selected
            // Selected thumb stays white in light and dark mode (iOS segmented control).
            // Using attachmentPanelBackgroundColor made the tab black when hosts theme
            // the panel with their dark primary surface.
            val thumbColor = animateColorAsState(
                targetValue = if (active) Color.White else Color.Transparent,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "media-tab-thumb-$tab",
            ).value
            val labelColor = animateColorAsState(
                targetValue = if (active) theme.accentColor else theme.incomingTimestampColor,
                animationSpec = tween(180),
                label = "media-tab-label-$tab",
            ).value

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (active) {
                            Modifier.shadow(elevation = 2.dp, shape = thumbShape, clip = false)
                        } else {
                            Modifier
                        },
                    )
                    .clip(thumbShape)
                    .background(thumbColor)
                    .then(
                        if (active) {
                            Modifier.border(
                                width = 0.5.dp,
                                color = Color.Black.copy(alpha = 0.06f),
                                shape = thumbShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        role = Role.Tab
                        this.selected = active
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(tab) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (tab) {
                        MediaTab.Photos -> "Photos"
                        MediaTab.Videos -> "Videos"
                    },
                    color = labelColor,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
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
    val cancellation = remember(item.id, item.mediaType) { CancellationSignal() }
    DisposableEffect(item.id, item.mediaType) {
        onDispose { cancellation.cancel() }
    }
    val cached = remember(item.id, item.mediaType) {
        peekCachedThumbnail(item)
    }
    val thumbnail by produceState(cached, item.id, item.mediaType) {
        if (value != null) return@produceState
        value = withContext(ThumbnailLoadDispatcher) {
            ThumbnailLoadSemaphore.withPermit {
                if (cancellation.isCanceled) return@withPermit null
                runCatching {
                    loadCachedThumbnail(context.contentResolver, item, cancellation)
                }.getOrNull()
            }
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

private const val MediaThumbnailCacheKilobytes = 16 * 1024
/** Grid cells are ~¼ screen; keep thumbs light so fling stays smooth. */
private const val ThumbnailPixelSize = 128
/** Match iOS `PHFetchOptions.fetchLimit`. */
private const val MediaQueryLimit = 300
/** First paint + append pages (iOS loads off-main; we stream pages for snappier UI). */
private const val MediaPageSize = 60
/** Cap concurrent MediaStore thumbnail decodes so scrolling doesn't thrash disk. */
private val ThumbnailLoadSemaphore = Semaphore(permits = 6)
private val ThumbnailLoadDispatcher =
    Executors.newFixedThreadPool(6).asCoroutineDispatcher()

private val MediaThumbnailCache = object : LruCache<String, ImageBitmap>(
    MediaThumbnailCacheKilobytes,
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width.toLong() * value.height.toLong() * 4L / 1024L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}

private fun thumbnailCacheKey(item: MediaItem): String =
    "${item.mediaType}:${item.id}:$ThumbnailPixelSize"

private fun peekCachedThumbnail(item: MediaItem): ImageBitmap? =
    synchronized(MediaThumbnailCache) {
        MediaThumbnailCache.get(thumbnailCacheKey(item))
    }

private fun loadCachedThumbnail(
    cr: ContentResolver,
    item: MediaItem,
    cancellation: CancellationSignal? = null,
): ImageBitmap? {
    val key = thumbnailCacheKey(item)
    synchronized(MediaThumbnailCache) {
        MediaThumbnailCache.get(key)?.let { return it }
    }
    if (cancellation?.isCanceled == true) return null
    val thumbnail = loadThumbnail(cr, item, cancellation) ?: return null
    if (cancellation?.isCanceled == true) return null
    synchronized(MediaThumbnailCache) { MediaThumbnailCache.put(key, thumbnail) }
    return thumbnail
}

private fun loadMediaItems(
    cr: ContentResolver,
    type: MediaType,
    limit: Int = MediaQueryLimit,
    offset: Int = 0,
): List<MediaItem> {
    val items = ArrayList<MediaItem>(limit.coerceAtMost(MediaPageSize))
    // On API 30+ MediaStore applies OFFSET; older APIs skip rows in the cursor.
    val manualSkip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 0 else offset
    when (type) {
        MediaType.Photo -> {
            queryMediaStore(
                cr = cr,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.ORIENTATION,
                ),
                sortColumn = MediaStore.Images.Media.DATE_ADDED,
                limit = limit,
                offset = offset,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val orientationCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
                var skipped = 0
                while (skipped < manualSkip && cursor.moveToNext()) skipped++
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idCol)
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    val orientation = if (orientationCol >= 0) cursor.getInt(orientationCol) else 0
                    items += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        mediaType = MediaType.Photo,
                        dateAdded = cursor.getLong(dateCol),
                        aspectRatio = uprightDisplaySize(width, height, orientation)
                            ?.let { (w, h) -> aspectRatioFromSize(w, h) },
                    )
                    count++
                }
            }
        }
        MediaType.Video -> {
            queryMediaStore(
                cr = cr,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                ),
                sortColumn = MediaStore.Video.Media.DATE_ADDED,
                limit = limit,
                offset = offset,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                var skipped = 0
                while (skipped < manualSkip && cursor.moveToNext()) skipped++
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idCol)
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    // MediaStore video WIDTH/HEIGHT are usually display-oriented already.
                    items += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        mediaType = MediaType.Video,
                        dateAdded = cursor.getLong(dateCol),
                        durationMillis = cursor.getLong(durationCol),
                        aspectRatio = aspectRatioFromSize(width, height),
                    )
                    count++
                }
            }
        }
    }
    return items
}

private fun queryMediaStore(
    cr: ContentResolver,
    collection: Uri,
    projection: Array<String>,
    sortColumn: String,
    limit: Int,
    offset: Int,
) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val args = Bundle().apply {
        // OFFSET is API 30+; older platforms need limit = offset + page so we can skip.
        val queryLimit =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) limit else offset + limit
        putInt(ContentResolver.QUERY_ARG_LIMIT, queryLimit)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && offset > 0) {
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
        putInt(
            ContentResolver.QUERY_ARG_SORT_DIRECTION,
            ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
        )
    }
    cr.query(collection, projection, args, null)
} else {
    cr.query(collection, projection, null, null, "$sortColumn DESC")
}

private fun loadThumbnail(
    cr: ContentResolver,
    item: MediaItem,
    cancellation: CancellationSignal? = null,
): ImageBitmap? {
    if (cancellation?.isCanceled == true) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cr.loadThumbnail(
            item.uri,
            Size(ThumbnailPixelSize, ThumbnailPixelSize),
            cancellation,
        ).asImageBitmap()
    } else {
        when (item.mediaType) {
            MediaType.Photo -> {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    cr,
                    item.id,
                    MediaStore.Images.Thumbnails.MICRO_KIND,
                    null,
                )?.asImageBitmap()
                    ?: decodeSampledBitmap(cr, item.uri, ThumbnailPixelSize, ThumbnailPixelSize)
            }
            MediaType.Video -> {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    cr,
                    item.id,
                    MediaStore.Video.Thumbnails.MICRO_KIND,
                    null,
                )?.asImageBitmap()
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
    val orientation = cr.openInputStream(uri)?.use { stream ->
        android.media.ExifInterface(stream).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: android.media.ExifInterface.ORIENTATION_NORMAL
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
    val raw = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    return applyExifOrientation(raw, orientation).asImageBitmap()
}
