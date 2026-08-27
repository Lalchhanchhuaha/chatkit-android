package com.chatkit.compose

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

private val MediaTileShape = RoundedCornerShape(8.dp)
private val SingleMediaHeight = 210.dp
private val MediaGridSpacing = 4.dp
private const val WaveformBarCount = 28
private val WaveformBarSpacing = 2.dp

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
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    onCancelUpload: (ChatAttachment) -> Unit = {},
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
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
        )
        attachment.isVideo -> MediaAttachmentTile(
            attachment = attachment,
            theme = theme,
            width = null,
            height = SingleMediaHeight,
            isVideo = true,
            compact = false,
            automaticallyLoadsImages = automaticallyLoadsImages,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
        )
        attachment.isAudio -> VoiceMessageRow(
            attachment = attachment,
            theme = theme,
            isIncoming = isIncoming,
            automaticallyLoadsImages = automaticallyLoadsImages,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
            audioPlayer = audioPlayer,
        )
        else -> DocumentAttachmentRow(
            attachment = attachment,
            theme = theme,
            onCancelUpload = onCancelUpload,
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
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    audioPlayer: AudioPlayerController,
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
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
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
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
        )
    }
    if (audios.isNotEmpty() || documents.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(
                    top = if (images.isEmpty() && videos.isEmpty()) 0.dp else 5.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            audios.forEach { attachment ->
                VoiceMessageRow(
                    attachment = attachment,
                    theme = theme,
                    isIncoming = message.isIncoming,
                    automaticallyLoadsImages = automaticallyLoadsImages || !message.isIncoming,
                    attachmentResolver = attachmentResolver,
                    onCancelUpload = onCancelUpload,
                    audioPlayer = audioPlayer,
                )
            }
            documents.forEach { attachment ->
                DocumentAttachmentRow(
                    attachment = attachment,
                    theme = theme,
                    onCancelUpload = onCancelUpload,
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
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
) {
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
                width = mediaWidth,
                height = SingleMediaHeight,
                isVideo = isVideo,
                compact = false,
                automaticallyLoadsImages = automaticallyLoadsImages,
                attachmentResolver = attachmentResolver,
                onCancelUpload = onCancelUpload,
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
                                    attachmentResolver = attachmentResolver,
                                    onCancelUpload = onCancelUpload,
                                )
                            }
                        }
                    }
                }
            }
        }
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
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
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
            automaticallyLoadsImages = automaticallyLoadsImages,
            attachmentResolver = attachmentResolver,
            onCancelUpload = onCancelUpload,
        )
        if (isOverflowTile) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MediaTileShape)
                    .background(Color.Black.copy(alpha = 0.58f)),
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
    automaticallyLoadsImages: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
) {
    val context = LocalContext.current
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var retryToken by remember(attachment.id) { mutableStateOf(0) }
    val resolvedUri by produceState<Uri?>(
        attachment.localUri,
        attachment.id,
        automaticallyLoadsImages,
        retryToken,
    ) {
        var result: Uri? = attachment.localUri
        // Keep trying while the bubble is on screen. Media prefetch can lag behind
        // the first compose (or fail once during a timeout) and previously left a
        // permanent empty placeholder.
        var attempt = 0
        while (result == null && attempt < 12) {
            val available = runCatching { attachmentResolver.isAvailableLocally(attachment) }.getOrDefault(false)
            if (automaticallyLoadsImages || available || !attachment.isImage) {
                result = runCatching { attachmentResolver.resolveContent(attachment) }.getOrNull()
            }
            if (result != null) break
            attempt += 1
            delay((750L * attempt).coerceAtMost(5_000L))
        }
        value = result
    }
    val posterUri by produceState<Uri?>(attachment.posterUri, attachment.id, retryToken) {
        value = if (isVideo) {
            attachment.posterUri
                ?: runCatching { attachmentResolver.resolvePoster(attachment) }.getOrNull()
        } else {
            null
        }
    }
    val displayUri = if (isVideo) (posterUri ?: resolvedUri) else resolvedUri
    val bitmap by produceState<ImageBitmap?>(
        null,
        displayUri,
        resolvedUri,
        posterUri,
        isVideo,
    ) {
        value = if (displayUri != null || (isVideo && resolvedUri != null)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val decoded = if (isVideo) {
                        // Prefer a frame from the real video so METADATA_KEY_VIDEO_ROTATION
                        // is applied. Host posters are often raw sensor JPEGs with no EXIF.
                        val fromVideo = resolvedUri?.let {
                            decodeVideoFrameRespectingRotation(context, it, maxSide = 1024)
                        }
                        fromVideo
                            ?: posterUri?.let {
                                decodeBitmapRespectingExif(context, it, maxSide = 1024)
                                    ?: decodeVideoFrameRespectingRotation(context, it, maxSide = 1024)
                            }
                    } else {
                        decodeBitmapRespectingExif(context, displayUri!!)
                    }
                    decoded?.asImageBitmap()
                }.getOrNull()
            }
        } else {
            null
        }
    }
    val playSize = if (compact) 36.dp else 50.dp
    val playIconSize = if (compact) 14.dp else 20.dp
    val durationPadH = if (compact) 5.dp else 7.dp
    val durationPadV = if (compact) 3.dp else 4.dp
    val durationInset = if (compact) 6.dp else 8.dp
    val isUploading = attachment.transferState is TransferState.Uploading
    // Spinner while downloading OR while pixels are still decoding. ChatKit 1.5+ used
    // ImageDecoder which fails on extension-less FileProvider URIs and previously left
    // a permanent blank tile once resolvedUri was non-null.
    val waitingForMedia = !isUploading &&
        bitmap == null &&
        attachment.transferState != TransferState.Failed

    Box(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(MediaTileShape)
            .then(if (isBlurred) Modifier.blur(9.dp).scale(1.08f) else Modifier)
            .background(if (isVideo) Color.Black.copy(alpha = 0.78f) else theme.thumbnailPlaceholderBackgroundColor)
            .clickable(enabled = !isUploading && attachment.transferState != TransferState.Failed) {
                val openUri = resolvedUri ?: posterUri
                if (openUri == null) {
                    // Tap empty placeholder to force another download attempt.
                    retryToken += 1
                    return@clickable
                }
                if (isVideo) {
                    openAttachment(context, openUri, attachment.mimeType)
                } else {
                    previewUri = openUri
                }
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
        } else if (waitingForMedia) {
            CircularProgressIndicator(
                color = if (isVideo) Color.White else theme.accentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        } else if (isVideo) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(34.dp),
            )
        }

        if (showsPlayControl && isVideo && !isUploading) {
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

        AttachmentTransferOverlay(
            transferState = attachment.transferState,
            theme = theme,
            onCancel = { onCancelUpload(attachment) },
        )
    }

    previewUri?.let { uri ->
        FullScreenImagePreview(
            uri = uri,
            fileName = attachment.fileName,
            onDismiss = { previewUri = null },
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
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeBitmapRespectingExif(context, uri, maxSide = 2048)?.asImageBitmap()
            }.getOrNull()
        }
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

@Composable
internal fun VoiceMessageRow(
    attachment: ChatAttachment,
    theme: ChatTheme,
    isIncoming: Boolean,
    automaticallyLoadsImages: Boolean,
    attachmentResolver: AttachmentResolver,
    onCancelUpload: (ChatAttachment) -> Unit,
    audioPlayer: AudioPlayerController,
) {
    val resolvedUri by produceState<Uri?>(attachment.localUri, attachment.id, automaticallyLoadsImages) {
        val local = attachment.localUri
        if (local != null) {
            value = local
            return@produceState
        }
        value = runCatching { attachmentResolver.resolveContent(attachment) }.getOrNull()
    }
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
    // White control fill keeps play/pause readable on both incoming and outgoing bubbles.
    val playFill = theme.accentContentColor.copy(alpha = 0.92f)
    val playIcon = theme.accentColor
    val waveActive = if (isIncoming) theme.accentColor else theme.accentContentColor
    val waveInactive = if (isIncoming) {
        theme.incomingTimestampColor.copy(alpha = 0.35f)
    } else {
        theme.outgoingTimestampColor.copy(alpha = 0.45f)
    }
    val timestampColor = if (isIncoming) theme.incomingTimestampColor else theme.outgoingTimestampColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 220.dp)
            .padding(top = 4.dp)
            .semantics {
                contentDescription = "Voice message, ${formatAttachmentDuration(knownDuration)}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(playFill)
                .clickable {
                    when (val transfer = attachment.transferState) {
                        is TransferState.Uploading -> onCancelUpload(attachment)
                        TransferState.Failed -> onCancelUpload(attachment)
                        TransferState.Uploaded -> {
                            val uri = resolvedUri ?: return@clickable
                            audioPlayer.toggle(attachment.id, uri, knownDuration)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val transfer = attachment.transferState) {
                is TransferState.Uploading -> {
                    CircularProgressIndicator(
                        progress = { transfer.progress.coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(28.dp),
                        color = playIcon,
                        strokeWidth = 2.5.dp,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel upload",
                        tint = playIcon,
                        modifier = Modifier.size(12.dp),
                    )
                }
                TransferState.Failed -> Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry upload",
                    tint = playIcon,
                    modifier = Modifier.size(16.dp),
                )
                TransferState.Uploaded -> Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                    tint = playIcon,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AudioWaveform(
            progress = progress,
            activeColor = waveActive,
            inactiveColor = waveInactive,
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )

        Text(
            text = formatAttachmentDuration(displayedMillis),
            color = timestampColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
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
    onCancelUpload: (ChatAttachment) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.thumbnailPlaceholderBackgroundColor),
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
        AttachmentTransferOverlay(
            transferState = attachment.transferState,
            theme = theme,
            onCancel = { onCancelUpload(attachment) },
            compact = true,
        )
    }
}

@Composable
private fun AttachmentTransferOverlay(
    transferState: TransferState,
    theme: ChatTheme,
    onCancel: () -> Unit,
    compact: Boolean = false,
) {
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
                    CircularProgressIndicator(
                        progress = { transferState.progress.coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(if (compact) 28.dp else 46.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel upload",
                        tint = Color.White,
                        modifier = Modifier.size(if (compact) 12.dp else 14.dp),
                    )
                }
            }
        }
        TransferState.Failed -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Upload failed",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        TransferState.Uploaded -> Unit
    }
}

internal fun formatAttachmentDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun openAttachment(context: android.content.Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
