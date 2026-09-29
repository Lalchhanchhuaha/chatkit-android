package com.chatkit.compose

import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val MediaTileShape = RoundedCornerShape(8.dp)
private val SingleMediaHeight = 210.dp
private val SingleMediaMinimumHeight = 160.dp
private val SingleMediaMaximumHeight = 320.dp
private val SinglePortraitMediaMaximumHeight = 340.dp
private val MediaGridSpacing = 4.dp
private const val WaveformBarCount = 28
private val WaveformBarSpacing = 2.dp
private const val AttachmentPreviewCacheKilobytes = 24 * 1024
private val AttachmentPreviewCache = object : LruCache<String, ImageBitmap>(
    AttachmentPreviewCacheKilobytes,
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width.toLong() * value.height.toLong() * 4L / 1024L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}

private fun attachmentPreviewCacheKey(uri: Uri, preferVideo: Boolean, maxSide: Int): String =
    "$uri|$preferVideo|$maxSide"

internal fun cachedAttachmentPreviewOrNull(
    uri: Uri,
    preferVideo: Boolean,
    maxSide: Int,
): ImageBitmap? = synchronized(AttachmentPreviewCache) {
    AttachmentPreviewCache.get(attachmentPreviewCacheKey(uri, preferVideo, maxSide))
}

/** Must be called off the main thread: a cache miss performs bitmap/video decoding. */
internal fun decodeAndCacheAttachmentPreview(
    context: android.content.Context,
    uri: Uri,
    preferVideo: Boolean,
    maxSide: Int,
): ImageBitmap? {
    val key = attachmentPreviewCacheKey(uri, preferVideo, maxSide)
    cachedAttachmentPreviewOrNull(uri, preferVideo, maxSide)?.let { return it }
    val decoded = decodeAttachmentPreview(
        context = context,
        uri = uri,
        preferVideo = preferVideo,
        maxSide = maxSide,
    )?.asImageBitmap() ?: return null
    synchronized(AttachmentPreviewCache) {
        AttachmentPreviewCache.put(key, decoded)
    }
    return decoded
}

/**
 * Default host attachment renderer (single attachment). Prefer
 * [MessageAttachmentsContent] from [MessageBubble] for iOS-parity grids.
 */
@Composable
internal fun DefaultAttachment(
    attachment: ChatAttachment,
    theme: ChatTheme,
    isIncoming: Boolean = true,
    automaticallyLoadsImages: Boolean = true,
    automaticallySavesDownloadedMediaToPhotos: Boolean = false,
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    onCancelUpload: (ChatAttachment) -> Unit = {},
    onCancelDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachment: (ChatAttachment) -> Unit = {},
    audioPlayer: AudioPlayerController,
) {
    when {
        attachment.isImage -> MediaAttachmentTile(
            attachment = attachment,
            theme = theme,
            width = null,
            height = SingleMediaHeight,
            isVideo = false,
            compact = false,
            automaticallyLoadsImages = automaticallyLoadsImages,
            automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
        )
        attachment.isVideo -> MediaAttachmentTile(
            attachment = attachment,
            theme = theme,
            width = null,
            height = SingleMediaHeight,
            isVideo = true,
            compact = false,
            automaticallyLoadsImages = automaticallyLoadsImages,
            automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
        )
        attachment.isAudio -> VoiceMessageRow(
            attachment = attachment,
            theme = theme,
            isIncoming = isIncoming,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
            audioPlayer = audioPlayer,
        )
        else -> DocumentAttachmentRow(
            attachment = attachment,
            theme = theme,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
        )
    }
}

/** iOS MessageBubble media layout: image grid → video grid → voice/docs. */
@Composable
internal fun MessageAttachmentsContent(
    message: ChatMessage,
    theme: ChatTheme,
    maxBubbleWidth: Dp,
    automaticallyLoadsImages: Boolean,
    automaticallySavesDownloadedMediaToPhotos: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    onCancelDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachment: (ChatAttachment) -> Unit = {},
    audioPlayer: AudioPlayerController,
    compactVoiceLayout: Boolean = false,
    onSingleImageBubbleWidthChanged: (Dp?) -> Unit = {},
) {
    val images = message.attachments.filter { it.isImage }
    val videos = message.attachments.filter { it.isVideo }
    val audios = message.attachments.filter { it.isAudio }
    val documents = message.attachments.filter { !it.isImage && !it.isVideo && !it.isAudio }
    val mediaWidth = (maxBubbleWidth - 8.dp).coerceAtLeast(72.dp)

    if (images.isNotEmpty()) {
        MediaAttachmentGrid(
            attachments = images,
            theme = theme,
            mediaWidth = mediaWidth,
            isVideo = false,
            topPadding = 4.dp,
            automaticallyLoadsImages = automaticallyLoadsImages,
            automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
            onSingleImageBubbleWidthChanged = onSingleImageBubbleWidthChanged,
        )
    }
    if (videos.isNotEmpty()) {
        MediaAttachmentGrid(
            attachments = videos,
            theme = theme,
            mediaWidth = mediaWidth,
            isVideo = true,
            topPadding = if (images.isEmpty()) 4.dp else 0.dp,
            automaticallyLoadsImages = automaticallyLoadsImages,
            automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
        )
    }
    if (audios.isNotEmpty() || documents.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(
                    top = when {
                        compactVoiceLayout && message.replyToMessageId == null -> 6.dp
                        compactVoiceLayout -> 0.dp
                        images.isEmpty() && videos.isEmpty() -> 0.dp
                        else -> 5.dp
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            audios.forEach { attachment ->
                VoiceMessageRow(
                    attachment = attachment,
                    theme = theme,
                    isIncoming = message.isIncoming,
                    attachmentResolver = attachmentResolver,
                    onCancelUpload = onCancelUpload,
                    onCancelDownload = onCancelDownload,
                    onRetryAttachment = onRetryAttachment,
                    audioPlayer = audioPlayer,
                )
            }
            documents.forEach { attachment ->
                DocumentAttachmentRow(
                    attachment = attachment,
                    theme = theme,
                    attachmentResolver = attachmentResolver,
                    onCancelUpload = onCancelUpload,
                    onCancelDownload = onCancelDownload,
                    onRetryAttachment = onRetryAttachment,
                )
            }
        }
    }
}

@Composable
private fun MediaAttachmentGrid(
    attachments: List<ChatAttachment>,
    theme: ChatTheme,
    mediaWidth: Dp,
    isVideo: Boolean,
    topPadding: Dp,
    automaticallyLoadsImages: Boolean,
    automaticallySavesDownloadedMediaToPhotos: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    onCancelDownload: (ChatAttachment) -> Unit,
    onRetryAttachment: (ChatAttachment) -> Unit,
    onSingleImageBubbleWidthChanged: (Dp?) -> Unit = {},
) {
    var showAlbumGallery by remember { mutableStateOf(false) }
    // Layout metadata is immutable for the lifetime of a row. Never derive the tile
    // size from an asynchronously decoded bitmap: doing so visibly resizes the bubble.
    val singleImageAspectRatio = attachments.firstOrNull()
        ?.aspectRatio
        ?.takeIf { it.isFinite() && it > 0f }
    val singleTileSize = if (attachments.size == 1) {
        singleImageAspectRatio?.let { aspect ->
            val safeAspect = aspect.coerceAtLeast(0.05f)
            if (safeAspect < 0.9f) {
                val height = minOf(SinglePortraitMediaMaximumHeight, mediaWidth / safeAspect)
                minOf(mediaWidth, height * safeAspect) to height
            } else {
                mediaWidth to minOf(
                    SingleMediaMaximumHeight,
                    maxOf(SingleMediaMinimumHeight, mediaWidth / safeAspect),
                )
            }
        }
    } else {
        null
    }
    LaunchedEffect(singleTileSize, attachments.size, isVideo) {
        onSingleImageBubbleWidthChanged(singleTileSize?.first?.plus(8.dp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(top = topPadding),
    ) {
        if (attachments.size == 1) {
            MediaAttachmentTile(
                attachment = attachments.first(),
                theme = theme,
                width = singleTileSize?.first ?: mediaWidth,
                height = singleTileSize?.second ?: SingleMediaHeight,
                isVideo = isVideo,
                compact = false,
                automaticallyLoadsImages = automaticallyLoadsImages,
                automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
                attachmentResolver = attachmentResolver,
                onCancelUpload = onCancelUpload,
                onCancelDownload = onCancelDownload,
                onRetryAttachment = onRetryAttachment,
                onPreviewAspectRatio = null,
            )
        } else {
            val tileSize = (mediaWidth - MediaGridSpacing) / 2
            val visible = attachments.take(4)
            val overflowCount = (attachments.size - 4).coerceAtLeast(0)
            Column(verticalArrangement = Arrangement.spacedBy(MediaGridSpacing)) {
                for (rowIndex in 0 until ((visible.size + 1) / 2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MediaGridSpacing)) {
                        for (colIndex in 0 until 2) {
                            val index = rowIndex * 2 + colIndex
                            if (index >= visible.size) {
                                Spacer(
                                    Modifier
                                        .width(tileSize)
                                        .height(tileSize),
                                )
                            } else {
                                MediaGridCell(
                                    attachment = visible[index],
                                    theme = theme,
                                    tileSize = tileSize,
                                    isVideo = isVideo,
                                    isOverflowTile = index == 3 && overflowCount > 0,
                                    overflowCount = overflowCount,
                                    automaticallyLoadsImages = automaticallyLoadsImages,
                                    automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
                                    attachmentResolver = attachmentResolver,
                                    onCancelUpload = onCancelUpload,
                                    onCancelDownload = onCancelDownload,
                                    onRetryAttachment = onRetryAttachment,
                                    onOverflowTap = { showAlbumGallery = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAlbumGallery) {
        MediaAlbumGallery(
            attachments = attachments,
            isVideo = isVideo,
            automaticallyLoadsImages = automaticallyLoadsImages,
            automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
            attachmentResolver = attachmentResolver,
            onDismiss = { showAlbumGallery = false },
        )
    }
}

@Composable
private fun MediaGridCell(
    attachment: ChatAttachment,
    theme: ChatTheme,
    tileSize: Dp,
    isVideo: Boolean,
    isOverflowTile: Boolean,
    overflowCount: Int,
    automaticallyLoadsImages: Boolean,
    automaticallySavesDownloadedMediaToPhotos: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    onCancelDownload: (ChatAttachment) -> Unit,
    onRetryAttachment: (ChatAttachment) -> Unit,
    onOverflowTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(tileSize)
            .height(tileSize),
    ) {
        MediaAttachmentTile(
            attachment = attachment,
            theme = theme,
            width = tileSize,
            height = tileSize,
            isVideo = isVideo,
            compact = true,
            isBlurred = isOverflowTile,
            showsPlayControl = isVideo && !isOverflowTile,
            openPreviewOnTap = !isOverflowTile,
            automaticallyLoadsImages = automaticallyLoadsImages,
            automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            onCancelDownload = onCancelDownload,
            onRetryAttachment = onRetryAttachment,
        )
        if (isOverflowTile) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MediaTileShape)
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOverflowTap,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** WhatsApp-style vertical album when the grid +N tile is tapped. */
@Composable
private fun MediaAlbumGallery(
    attachments: List<ChatAttachment>,
    isVideo: Boolean,
    automaticallyLoadsImages: Boolean,
    automaticallySavesDownloadedMediaToPhotos: Boolean,
    attachmentResolver: AttachmentResolver,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }
    var previewImageName by remember { mutableStateOf<String?>(null) }
    var previewVideoUri by remember { mutableStateOf<Uri?>(null) }
    var previewVideoDuration by remember { mutableStateOf<Long?>(null) }
    val title = if (isVideo) {
        "${attachments.size} videos"
    } else {
        "${attachments.size} photos"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B141A)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(attachments, key = { it.id }) { attachment ->
                    MediaAlbumGalleryRow(
                        attachment = attachment,
                        isVideo = isVideo,
                        automaticallyLoadsImages = automaticallyLoadsImages,
                        automaticallySavesDownloadedMediaToPhotos = automaticallySavesDownloadedMediaToPhotos,
                        attachmentResolver = attachmentResolver,
                        onOpen = { uri ->
                            if (isVideo) {
                                previewVideoUri = uri
                                previewVideoDuration = attachment.durationMillis
                            } else {
                                previewImageUri = uri
                                previewImageName = attachment.fileName
                            }
                        },
                    )
                }
            }
        }
    }

    previewImageUri?.let { uri ->
        FullScreenImagePreview(
            uri = uri,
            fileName = previewImageName,
            onDismiss = { previewImageUri = null },
        )
    }
    previewVideoUri?.let { uri ->
        FullScreenVideoPreview(
            uri = uri,
            fallbackDurationMillis = previewVideoDuration,
            onDismiss = { previewVideoUri = null },
        )
    }
}

@Composable
private fun MediaAlbumGalleryRow(
    attachment: ChatAttachment,
    isVideo: Boolean,
    automaticallyLoadsImages: Boolean,
    automaticallySavesDownloadedMediaToPhotos: Boolean,
    attachmentResolver: AttachmentResolver,
    onOpen: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val resolvedUri by produceState<Uri?>(attachment.localUri, attachment.id, automaticallyLoadsImages) {
        value = attachment.localUri
            ?: runCatching { attachmentResolver.resolveContent(attachment) }.getOrNull()
    }
    val posterUri by produceState<Uri?>(attachment.posterUri, attachment.id) {
        value = attachment.posterUri
            ?: runCatching { attachmentResolver.resolvePoster(attachment) }.getOrNull()
    }
    val displayUri = resolvedUri ?: posterUri
    val initialBitmap = remember(displayUri, resolvedUri, posterUri, isVideo) {
        val initialUri = posterUri ?: displayUri
        initialUri?.let { uri ->
            cachedAttachmentPreviewOrNull(
                uri = uri,
                preferVideo = isVideo && posterUri == null && resolvedUri != null,
                // Decode only what the gallery tile can display on the first frame.
                // The sharper 1600px preview is produced below on Dispatchers.IO.
                maxSide = if (posterUri != null) 384 else 640,
            ) ?: posterUri?.let { localPoster ->
                // Posters are deliberately tiny local JPEGs. Decode one synchronously on
                // first use so a cached message row never flashes a loading placeholder.
                decodeAndCacheAttachmentPreview(
                    context = context,
                    uri = localPoster,
                    preferVideo = false,
                    maxSide = 384,
                )
            }
        }
    }
    val bitmap by produceState<ImageBitmap?>(initialBitmap, displayUri, resolvedUri, isVideo) {
        value = displayUri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    decodeAndCacheAttachmentPreview(
                        context = context,
                        uri = uri,
                        preferVideo = isVideo && resolvedUri != null,
                        maxSide = 1600,
                    )
                }.getOrNull()
            }
        }
    }
    val canOpen = resolvedUri != null
    // Reserve one stable row size before decode. Legacy attachments without stored
    // dimensions use a deterministic fallback rather than changing size later.
    val aspectRatio = attachment.aspectRatio
        ?.takeIf { it.isFinite() && it > 0f }
        ?: if (isVideo) 16f / 9f else 4f / 3f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = canOpen && displayUri != null) {
                resolvedUri?.let(onOpen)
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = attachment.fileName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio),
                    contentScale = ContentScale.Crop,
                )
            }
            displayUri != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        if (isVideo && canOpen && bitmap != null) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaAttachmentTile(

    attachment: ChatAttachment,
    theme: ChatTheme,
    width: Dp?,
    height: Dp,
    isVideo: Boolean,
    compact: Boolean,
    isBlurred: Boolean = false,
    showsPlayControl: Boolean = true,
    openPreviewOnTap: Boolean = true,
    automaticallyLoadsImages: Boolean,
    automaticallySavesDownloadedMediaToPhotos: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    onCancelDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachment: (ChatAttachment) -> Unit = {},
    onPreviewAspectRatio: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }
    var previewVideoUri by remember { mutableStateOf<Uri?>(null) }
    var retryToken by remember(attachment.id) { mutableStateOf(0) }
    var manualDownloadRequested by remember(attachment.id) { mutableStateOf(false) }
    var isResolving by remember(attachment.id) { mutableStateOf(false) }
    var resolveProgress by remember(attachment.id) { mutableFloatStateOf(0f) }
    var resolveCancelled by remember(attachment.id, attachment.transferState, attachment.localUri) {
        mutableStateOf(attachment.transferState is TransferState.DownloadFailed)
    }
    var downloadCancelled by remember(attachment.id, attachment.transferState, attachment.localUri) {
        mutableStateOf(attachment.transferState is TransferState.DownloadFailed)
    }
    var hasLocalContent by remember(
        attachment.id,
        attachment.localUri,
    ) {
        mutableStateOf(attachment.localUri != null)
    }
    var resolveExhausted by remember(attachment.id, retryToken) { mutableStateOf(false) }
    val resolvedUri by produceState<Uri?>(
        attachment.localUri,
        attachment.posterUri,
        attachment.transferState,
        attachment.id,
        automaticallyLoadsImages,
        manualDownloadRequested,
        retryToken,
        resolveCancelled,
        downloadCancelled,
    ) {
        if (resolveCancelled || downloadCancelled) {
            resolveExhausted = true
            isResolving = false
            value = null
            return@produceState
        }
        if (attachment.transferState is TransferState.DownloadFailed) {
            downloadCancelled = true
            resolveExhausted = true
            isResolving = false
            value = null
            return@produceState
        }
        resolveExhausted = false
        val local = attachment.localUri
        if (local != null) {
            hasLocalContent = true
            isResolving = false
            value = local
            return@produceState
        }
        val available = runCatching {
            attachmentResolver.isAvailableLocally(attachment)
        }.getOrDefault(false)
        hasLocalContent = available
        if (!automaticallyLoadsImages && !available && !manualDownloadRequested) {
            isResolving = false
            value = null
            return@produceState
        }
        isResolving = true
        resolveProgress = 0f
        val result = runCatching {
            attachmentResolver.resolveContent(attachment) { progress ->
                resolveProgress = progress.coerceIn(0f, 1f)
            }
        }.getOrNull()
        isResolving = false
        hasLocalContent = result != null
        resolveExhausted = result == null
        value = result
    }
    val posterUri by produceState<Uri?>(attachment.posterUri, attachment.id, retryToken) {
        // WhatsApp / iOS: lightweight encrypted poster for image and video bubbles
        // before (or without) the full media blob.
        value = attachment.posterUri
            ?: runCatching { attachmentResolver.resolvePoster(attachment) }.getOrNull()
    }
    // Prefer full media when present; fall back to poster so the bubble is never blank
    // while the host is still downloading/decrypting the full file.
    val displayUri = resolvedUri ?: posterUri
    val initialBitmap = remember(displayUri, resolvedUri, posterUri, isVideo) {
        val initialUri = posterUri ?: displayUri
        initialUri?.let { uri ->
            cachedAttachmentPreviewOrNull(
                uri = uri,
                preferVideo = isVideo && posterUri == null && resolvedUri != null,
                // Avoid decoding a full 1024px bitmap on the composition thread.
                // A small local preview removes the placeholder; IO replaces it below.
                maxSide = if (posterUri != null) 320 else 512,
            ) ?: posterUri?.let { localPoster ->
                // A local encrypted-message poster is bounded to a small JPEG by the host.
                // Decoding it here gives the first Compose frame real pixels and dimensions;
                // the larger background decode below only improves sharpness.
                decodeAndCacheAttachmentPreview(
                    context = context,
                    uri = localPoster,
                    preferVideo = false,
                    maxSide = 320,
                )
            }
        }
    }
    val bitmap by produceState<ImageBitmap?>(
        initialBitmap,
        displayUri,
        resolvedUri,
        posterUri,
        isVideo,
    ) {
        value = if (displayUri != null || (isVideo && resolvedUri != null) || posterUri != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val decoded = if (isVideo) {
                        // Prefer a frame from the real video so METADATA_KEY_VIDEO_ROTATION
                        // is applied. Host posters are often raw sensor JPEGs with no EXIF.
                        // Fall back across image↔video like iOS UIImage / AVFoundation.
                        val fromVideo = resolvedUri?.let {
                            decodeAttachmentPreview(
                                context,
                                it,
                                preferVideo = true,
                                maxSide = 1024,
                            )
                        }
                        fromVideo
                            ?: posterUri?.let {
                                decodeAttachmentPreview(
                                    context,
                                    it,
                                    preferVideo = false,
                                    maxSide = 1024,
                                )
                            }
                    } else {
                        // Full image first (sharper); poster JPEG while full decrypt lags.
                        val primary = resolvedUri ?: posterUri ?: displayUri
                        primary?.let {
                            decodeAttachmentPreview(
                                context,
                                it,
                                preferVideo = false,
                                maxSide = 1024,
                            )
                        } ?: posterUri?.let {
                            decodeAttachmentPreview(
                                context,
                                it,
                                preferVideo = false,
                                maxSide = 1024,
                            )
                        }
                    }
                    decoded?.asImageBitmap()?.also { preview ->
                        val uri = displayUri ?: return@also
                        val key = "${uri}|${isVideo && resolvedUri != null}|1024"
                        synchronized(AttachmentPreviewCache) {
                            AttachmentPreviewCache.put(key, preview)
                        }
                    }
                }.getOrNull()
            }
        } else {
            null
        }
    }
    LaunchedEffect(bitmap) {
        val preview = bitmap ?: return@LaunchedEffect
        onPreviewAspectRatio?.invoke(
            preview.width.toFloat() / preview.height.toFloat().coerceAtLeast(1f),
        )
    }
    val playSize = if (compact) 44.dp else 58.dp
    val playIconSize = if (compact) 26.dp else 34.dp
    val durationPadH = if (compact) 5.dp else 7.dp
    val durationPadV = if (compact) 3.dp else 4.dp
    val durationInset = if (compact) 6.dp else 8.dp
    val transfer = attachment.transferState
    val waitingForManualDownload = transfer is TransferState.Uploaded &&
        resolvedUri == null &&
        !hasLocalContent &&
        !automaticallyLoadsImages &&
        !manualDownloadRequested &&
        !isResolving &&
        !resolveExhausted &&
        !resolveCancelled &&
        !downloadCancelled
    val effectiveTransfer = when {
        transfer is TransferState.Failed || transfer is TransferState.DownloadFailed -> transfer
        resolveCancelled -> TransferState.DownloadFailed
        downloadCancelled -> TransferState.DownloadFailed
        transfer is TransferState.Uploading -> transfer
        transfer is TransferState.Downloading -> transfer
        isResolving -> TransferState.Downloading(resolveProgress)
        resolveExhausted -> TransferState.DownloadFailed
        else -> transfer
    }
    val isTransferring = effectiveTransfer.isTransferring
    val transferFailed = effectiveTransfer.isFailedTransfer

    Box(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(MediaTileShape)
            .then(if (isBlurred) Modifier.blur(9.dp).scale(1.08f) else Modifier)
            .background(if (isVideo) Color.Black.copy(alpha = 0.78f) else theme.thumbnailPlaceholderBackgroundColor)
            .clickable(enabled = openPreviewOnTap && !isTransferring && !transferFailed) {
                if (waitingForManualDownload) {
                    manualDownloadRequested = true
                    return@clickable
                }
                if (isVideo) {
                    val videoUri = resolvedUri
                    if (videoUri == null) {
                        // Poster-only: retry full download instead of opening externally.
                        manualDownloadRequested = true
                        retryToken += 1
                        return@clickable
                    }
                    previewVideoUri = videoUri
                    return@clickable
                }
                val openUri = resolvedUri ?: posterUri
                if (openUri == null) {
                    // Tap empty placeholder to force another download attempt.
                    manualDownloadRequested = true
                    retryToken += 1
                    return@clickable
                }
                previewImageUri = openUri
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = attachment.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (isVideo && !isTransferring && !transferFailed && !waitingForManualDownload) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(34.dp),
            )
        }

        if (showsPlayControl && isVideo && !isTransferring && !transferFailed &&
            !waitingForManualDownload && bitmap != null
        ) {
            Box(
                modifier = Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(playIconSize),
                )
            }
        }

        val duration = attachment.durationMillis
        if (showsPlayControl && isVideo && duration != null && duration > 0L) {
            Text(
                text = formatAttachmentDuration(duration),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(durationInset)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = durationPadH, vertical = durationPadV),
            )
        }

        if (waitingForManualDownload) {
            AttachmentDownloadBadge(
                progress = null,
                size = if (compact) 36.dp else 46.dp,
                iconSize = if (compact) 16.dp else 20.dp,
                onClick = { manualDownloadRequested = true },
            )
        }

        AttachmentTransferOverlay(
            transferState = effectiveTransfer,
            theme = theme,
            onCancel = {
                when (effectiveTransfer) {
                    is TransferState.Uploading -> onCancelUpload(attachment)
                    is TransferState.Downloading -> {
                        if (transfer is TransferState.Downloading) {
                            downloadCancelled = true
                            onCancelDownload(attachment)
                        } else {
                            resolveCancelled = true
                        }
                    }
                    else -> Unit
                }
            },
            onRetry = {
                resolveCancelled = false
                downloadCancelled = false
                manualDownloadRequested = true
                onRetryAttachment(attachment)
                if (effectiveTransfer !is TransferState.Failed) {
                    retryToken += 1
                }
            },
        )
    }

    LaunchedEffect(
        resolvedUri,
        manualDownloadRequested,
        automaticallySavesDownloadedMediaToPhotos,
        attachment.id,
    ) {
        val uri = resolvedUri ?: return@LaunchedEffect
        if (!automaticallySavesDownloadedMediaToPhotos || !manualDownloadRequested) return@LaunchedEffect
        ReceivedAttachmentMediaStoreSaver.saveIfNeeded(
            context = context,
            attachment = attachment,
            sourceUri = uri,
        )
    }

    previewImageUri?.let { uri ->
        FullScreenImagePreview(
            uri = uri,
            fileName = attachment.fileName,
            onDismiss = { previewImageUri = null },
        )
    }
    previewVideoUri?.let { uri ->
        FullScreenVideoPreview(
            uri = uri,
            fallbackDurationMillis = attachment.durationMillis,
            onDismiss = { previewVideoUri = null },
        )
    }
}

/** In-app image viewer with pinch-zoom and an X close control (iOS ZoomableFullScreenImage). */
@Composable
private fun FullScreenImagePreview(
    uri: Uri,
    fileName: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    // Reuse the preview decoded for the message bubble on the very first frame.
    // A sharper decode can replace it afterward without showing a spinner or
    // changing the fitted geometry when the viewer opens.
    val initialBitmap = remember(uri) {
        cachedAttachmentPreviewOrNull(uri, preferVideo = false, maxSide = 1024)
            ?: cachedAttachmentPreviewOrNull(uri, preferVideo = false, maxSide = 512)
            ?: cachedAttachmentPreviewOrNull(uri, preferVideo = false, maxSide = 320)
    }
    val bitmap by produceState<ImageBitmap?>(initialBitmap, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeAndCacheAttachmentPreview(
                    context = context,
                    uri = uri,
                    preferVideo = false,
                    maxSide = 2048,
                )
            }.getOrNull()
        } ?: initialBitmap
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale <= 1.01f) Offset.Zero else offset + pan
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 50.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = if (scale > 1f) 1f else 2.5f
                                    if (scale <= 1.01f) offset = Offset.Zero
                                },
                            )
                        },
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 18.dp, end = 18.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = "Close full screen image" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** In-app video viewer with WhatsApp-style chrome (tap controls, scrubber, back). */
@Composable
private fun FullScreenVideoPreview(
    uri: Uri,
    fallbackDurationMillis: Long?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    var isPlaying by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember {
        mutableLongStateOf(fallbackDurationMillis?.coerceAtLeast(0L) ?: 0L)
    }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) controlsVisible = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val readyDuration = player.duration
                    if (readyDuration > 0L) durationMs = readyDuration
                }
                if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    controlsVisible = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(isPlaying, controlsVisible) {
        if (isPlaying && controlsVisible) {
            delay(2_500)
            if (isPlaying) controlsVisible = false
        }
    }

    LaunchedEffect(player, isScrubbing, isPlaying) {
        while (isActive) {
            if (!isScrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val liveDuration = player.duration
                if (liveDuration > 0L) durationMs = liveDuration
            }
            delay(200)
        }
    }

    val progress = when {
        isScrubbing -> scrubProgress
        durationMs > 0L -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    fun togglePlayback() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
        controlsVisible = true
    }

    fun seekToProgress(fraction: Float) {
        if (durationMs <= 0L) return
        val target = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
        player.seekTo(target)
        positionMs = target
    }

    Dialog(
        onDismissRequest = {
            player.pause()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            controlsVisible = !controlsVisible
                        }
                    },
            )

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.22f)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.72f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                player.pause()
                                onDismiss()
                            }
                            .semantics { contentDescription = "Back" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = ::togglePlayback)
                        .semantics { contentDescription = "Play video" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(onClick = ::togglePlayback)
                            .semantics { contentDescription = "Pause video" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.78f),
                                ),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatVideoClock(positionMs),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = formatVideoClock(durationMs),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    WhatsAppVideoScrubber(
                        progress = progress,
                        onScrubStart = {
                            isScrubbing = true
                            scrubProgress = progress
                        },
                        onScrubChange = { fraction ->
                            scrubProgress = fraction
                        },
                        onScrubEnd = { fraction ->
                            isScrubbing = false
                            seekToProgress(fraction)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsAppVideoScrubber(
    progress: Float,
    onScrubStart: () -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = Color.White.copy(alpha = 0.35f)
    val activeColor = Color.White
    val thumbColor = Color.White
    var dragging by remember { mutableStateOf(false) }
    var localProgress by remember { mutableFloatStateOf(progress) }

    LaunchedEffect(progress, dragging) {
        if (!dragging) localProgress = progress
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onScrubStart()
                    dragging = true
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    localProgress = fraction
                    onScrubChange(fraction)
                    dragging = false
                    onScrubEnd(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        onScrubStart()
                        dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        onScrubEnd(localProgress)
                    },
                    onDragCancel = {
                        dragging = false
                        onScrubEnd(localProgress)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        localProgress = (localProgress + dragAmount / width).coerceIn(0f, 1f)
                        onScrubChange(localProgress)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = 3.dp.toPx()
            val centerY = size.height / 2f
            val trackWidth = size.width
            val clamped = localProgress.coerceIn(0f, 1f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(trackWidth, trackHeight),
                cornerRadius = CornerRadius(trackHeight),
            )
            val activeWidth = trackWidth * clamped
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight),
                )
            }
            val thumbRadius = if (dragging) 7.dp.toPx() else 5.dp.toPx()
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(
                    activeWidth.coerceIn(thumbRadius, trackWidth - thumbRadius),
                    centerY,
                ),
            )
        }
    }
}

private fun formatVideoClock(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
internal fun VoiceMessageRow(
    attachment: ChatAttachment,
    theme: ChatTheme,
    isIncoming: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    onCancelDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachment: (ChatAttachment) -> Unit = {},
    audioPlayer: AudioPlayerController,
) {
    var downloadCancelled by remember(attachment.id) { mutableStateOf(false) }
    var resolvedUri by remember(attachment.id, attachment.localUri) {
        mutableStateOf(attachment.localUri)
    }
    var isLoading by remember(attachment.id) { mutableStateOf(false) }
    var downloadProgress by remember(attachment.id) { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val isActive = audioPlayer.activeAttachmentId == attachment.id
    val isPlaying = isActive && audioPlayer.isPlaying
    val progress = if (isActive) audioPlayer.progress else 0f
    val knownDuration = attachment.durationMillis?.takeIf { it > 0L } ?: 0L
    val displayedMillis = when {
        isActive && isPlaying -> audioPlayer.positionMillis
        isActive && audioPlayer.durationMillis > 0L -> audioPlayer.durationMillis
        knownDuration > 0L -> knownDuration
        isActive -> audioPlayer.durationMillis
        else -> knownDuration
    }
    // iOS uses the accent control on incoming bubbles and reverses it on outgoing ones.
    val playFill = if (isIncoming) theme.accentColor else theme.accentContentColor.copy(alpha = 0.92f)
    val playIcon = if (isIncoming) theme.accentContentColor else theme.accentColor
    val waveActive = if (isIncoming) theme.accentColor else theme.accentContentColor
    val waveInactive = if (isIncoming) {
        theme.incomingTimestampColor.copy(alpha = 0.35f)
    } else {
        theme.outgoingTimestampColor.copy(alpha = 0.45f)
    }
    val timestampColor = if (isIncoming) theme.incomingTimestampColor else theme.outgoingTimestampColor
    val displayTransfer = when {
        downloadCancelled && attachment.transferState is TransferState.Downloading ->
            TransferState.DownloadFailed
        else -> attachment.transferState
    }
    val playOrResolve = {
        val uri = resolvedUri
        if (uri != null) {
            audioPlayer.toggle(attachment.id, uri, knownDuration)
        } else if (!isLoading) {
            coroutineScope.launch {
                isLoading = true
                downloadProgress = 0f
                val result = runCatching {
                    attachmentResolver.resolveContent(attachment) { progress ->
                        downloadProgress = progress.coerceIn(0f, 1f)
                    }
                }.getOrNull()
                resolvedUri = result
                isLoading = false
                if (result != null) {
                    audioPlayer.toggle(attachment.id, result, knownDuration)
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .semantics {
                contentDescription = "Voice message, ${formatAttachmentDuration(knownDuration)}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(playFill)
                .clickable {
                    when (val transfer = displayTransfer) {
                        is TransferState.Uploading -> onCancelUpload(attachment)
                        is TransferState.Downloading -> {
                            downloadCancelled = true
                            onCancelDownload(attachment)
                        }
                        TransferState.Failed -> onRetryAttachment(attachment)
                        TransferState.DownloadFailed -> {
                            downloadCancelled = false
                            onRetryAttachment(attachment)
                        }
                        TransferState.Uploaded -> playOrResolve()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val transfer = displayTransfer) {
                is TransferState.Uploading -> {
                    CircularProgressIndicator(
                        progress = { transfer.progress.coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(32.dp),
                        color = playIcon,
                        strokeWidth = 2.5.dp,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel upload",
                        tint = playIcon,
                        modifier = Modifier.size(16.dp),
                    )
                }
                is TransferState.Downloading -> {
                    CircularProgressIndicator(
                        progress = { transfer.progress.coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(32.dp),
                        color = playIcon,
                        strokeWidth = 2.5.dp,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel download",
                        tint = playIcon,
                        modifier = Modifier.size(16.dp),
                    )
                }
                TransferState.Failed -> Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = "Retry upload",
                    tint = playIcon,
                    modifier = Modifier.size(16.dp),
                )
                TransferState.DownloadFailed -> Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Retry download",
                    tint = playIcon,
                    modifier = Modifier.size(16.dp),
                )
                TransferState.Uploaded -> {
                    if (isLoading) {
                        CircularProgressIndicator(
                            progress = { downloadProgress.coerceIn(0.04f, 1f) },
                            modifier = Modifier.size(32.dp),
                            color = playIcon,
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                            tint = playIcon,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        AudioWaveform(
            progress = progress,
            activeColor = waveActive,
            inactiveColor = waveInactive,
            modifier = Modifier
                .weight(1f)
                .height(22.dp),
        )

        Text(
            text = formatAttachmentDuration(displayedMillis),
            color = timestampColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 32.dp),
        )
    }
}

@Composable
private fun AudioWaveform(
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        val spacing = WaveformBarSpacing.toPx()
        val barWidth = max(1f, (size.width - (WaveformBarCount - 1) * spacing) / WaveformBarCount)
        val midY = size.height / 2f
        for (index in 0 until WaveformBarCount) {
            val barHeight = (7f + ((index * 11) % 15)).dp.toPx()
            val filled = (index + 1).toFloat() / WaveformBarCount <= progress
            val left = index * (barWidth + spacing)
            drawRoundRect(
                color = if (filled) activeColor else inactiveColor,
                topLeft = Offset(left, midY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun DocumentAttachmentRow(
    attachment: ChatAttachment,
    theme: ChatTheme,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    onCancelDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachment: (ChatAttachment) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolvedUri by remember(attachment.id, attachment.localUri) {
        mutableStateOf(attachment.localUri)
    }
    var isLoading by remember(attachment.id) { mutableStateOf(false) }
    var progress by remember(attachment.id) { mutableFloatStateOf(0f) }
    var downloadCancelled by remember(attachment.id) { mutableStateOf(false) }
    val displayTransfer = when {
        downloadCancelled && attachment.transferState is TransferState.Downloading ->
            TransferState.DownloadFailed
        else -> attachment.transferState
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.thumbnailPlaceholderBackgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = theme.accentColor,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = attachment.fileName,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = theme.incomingTextColor,
                fontSize = 14.sp,
            )
        }
        val openOrDownload: () -> Unit = {
            val uri = resolvedUri
            if (uri != null) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, attachment.mimeType ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            } else if (!isLoading) {
                scope.launch {
                    isLoading = true
                    progress = 0f
                    resolvedUri = runCatching {
                        attachmentResolver.resolveContent(attachment) {
                            progress = it.coerceIn(0f, 1f)
                        }
                    }.getOrNull()
                    isLoading = false
                }
            }
            Unit
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    enabled = attachment.transferState !is TransferState.Uploading &&
                        attachment.transferState !is TransferState.Downloading,
                    onClick = openOrDownload,
                ),
        )
        if (isLoading) {
            AttachmentDownloadBadge(
                progress = progress,
                size = 46.dp,
                iconSize = 20.dp,
            )
        } else if (resolvedUri == null && attachment.transferState is TransferState.Uploaded) {
            AttachmentDownloadBadge(
                progress = null,
                size = 36.dp,
                iconSize = 18.dp,
            )
        }
        AttachmentTransferOverlay(
            transferState = displayTransfer,
            theme = theme,
            onCancel = {
                when (displayTransfer) {
                    is TransferState.Uploading -> onCancelUpload(attachment)
                    is TransferState.Downloading -> {
                        downloadCancelled = true
                        onCancelDownload(attachment)
                    }
                    else -> Unit
                }
            },
            onRetry = {
                downloadCancelled = false
                when (displayTransfer) {
                    TransferState.Failed -> onRetryAttachment(attachment)
                    TransferState.DownloadFailed -> onRetryAttachment(attachment)
                    else -> onRetryAttachment(attachment)
                }
            },
        )
    }
}

/** iOS-style centered download affordance: arrow before download, ring while loading. */
@Composable
private fun AttachmentDownloadBadge(
    progress: Float?,
    size: Dp,
    iconSize: Dp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.58f))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            AttachmentProgressIndicator(
                progress = progress,
                modifier = Modifier.size(size - 10.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download attachment",
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun AttachmentProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0.04f, 1f),
        animationSpec = tween(250),
        label = "attachment-progress",
    )
    CircularProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier,
        color = Color.White,
        strokeWidth = 3.dp,
    )
}

@Composable
private fun AttachmentTransferOverlay(
    transferState: TransferState,
    theme: ChatTheme,
    onCancel: () -> Unit,
    onRetry: () -> Unit = {},
) {
    val controlSize = 46.dp
    val iconSize = 20.dp
    val cancelIconSize = 14.dp
    when (transferState) {
        is TransferState.Uploading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                    Box(contentAlignment = Alignment.Center) {
                        AttachmentProgressIndicator(
                            progress = transferState.progress,
                            modifier = Modifier.size(46.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel upload",
                        tint = Color.White,
                        modifier = Modifier.size(cancelIconSize),
                    )
                }
            }
        }
        is TransferState.Downloading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AttachmentDownloadBadge(
                        progress = transferState.progress.coerceAtLeast(0.04f),
                        size = controlSize,
                        iconSize = iconSize,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel download",
                        tint = Color.White,
                        modifier = Modifier.size(cancelIconSize),
                    )
                }
            }
        }
        TransferState.Failed -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(controlSize)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "Upload failed. Tap to retry.",
                        tint = Color.White,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
        TransferState.DownloadFailed -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(controlSize)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download failed. Tap to retry.",
                        tint = Color.White,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
        TransferState.Uploaded -> Unit
    }
}

internal fun formatAttachmentDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
