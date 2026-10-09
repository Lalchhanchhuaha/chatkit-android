package com.chatkit.compose

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen gallery review matching camera capture review:
 * large preview, optional horizontal strip for multiple picks, caption + send.
 */
@Composable
internal fun GalleryMediaReviewDialog(
    theme: ChatTheme,
    items: List<ChatMediaAttachment>,
    onDismiss: () -> Unit,
    onSend: (caption: String, media: List<ChatMediaAttachment>) -> Unit,
) {
    if (items.isEmpty()) return

    var visible by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("") }
    var selectedIndex by remember(items.map { it.id }) { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }

    if (!visible) return

    Dialog(
        onDismissRequest = {
            visible = false
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler {
            visible = false
            onDismiss()
        }
        GalleryMediaReviewScreen(
            theme = theme,
            items = items,
            selectedIndex = selectedIndex.coerceIn(0, items.lastIndex),
            onSelectIndex = { selectedIndex = it },
            caption = caption,
            onCaptionChange = { caption = it },
            sendEnabled = !sending,
            onClose = {
                visible = false
                onDismiss()
            },
            onSend = {
                if (sending) return@GalleryMediaReviewScreen
                sending = true
                val media = items.toList()
                val text = caption
                visible = false
                onSend(text, media)
            },
        )
    }
}

@Composable
private fun GalleryMediaReviewScreen(
    theme: ChatTheme,
    items: List<ChatMediaAttachment>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    caption: String,
    onCaptionChange: (String) -> Unit,
    sendEnabled: Boolean,
    onClose: () -> Unit,
    onSend: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val selected = items[selectedIndex]
    val stripState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (items.size > 1) {
            stripState.animateScrollToItem(selectedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (items.size > 1) {
                Text(
                    text = "${selectedIndex + 1}/${items.size}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (selected.mediaType) {
                MediaType.Photo -> GalleryPhotoPreview(uri = selected.localUri)
                MediaType.Video -> GalleryVideoPreview(uri = selected.localUri)
            }
        }

        if (items.size > 1) {
            LazyRow(
                state = stripState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    GalleryStripThumb(
                        item = item,
                        selected = index == selectedIndex,
                        theme = theme,
                        onClick = { onSelectIndex(index) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF2C2C2E))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = caption,
                    onValueChange = onCaptionChange,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(theme.accentColor),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (caption.isEmpty()) {
                            Text("Write a message…", color = Color.White.copy(alpha = 0.55f))
                        }
                        inner()
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (sendEnabled) theme.accentColor else theme.accentColor.copy(alpha = 0.45f),
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "Send"
                    }
                    .clickable(enabled = sendEnabled, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = theme.accentContentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun GalleryPhotoPreview(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeBitmapRespectingExif(context, uri, maxSide = 2048) }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Selected photo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E)),
        )
    }
}

@Composable
private fun GalleryVideoPreview(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        update = { it.player = player },
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun GalleryStripThumb(
    item: ChatMediaAttachment,
    selected: Boolean,
    theme: ChatTheme,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, item.id, item.mediaType) {
        value = withContext(Dispatchers.IO) {
            loadGalleryThumb(context, item)
        }
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C2C2E))
            .then(
                if (selected) {
                    Modifier.border(2.dp, theme.accentColor, RoundedCornerShape(10.dp))
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                },
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (item.mediaType == MediaType.Video) {
                    "Select video"
                } else {
                    "Select photo"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (item.mediaType == MediaType.Video) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.Center),
            )
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(12.dp),
            )
        }
    }
}

private fun loadGalleryThumb(
    context: android.content.Context,
    item: ChatMediaAttachment,
): Bitmap? = runCatching {
    when (item.mediaType) {
        MediaType.Photo -> decodeBitmapRespectingExif(context, item.localUri, maxSide = 256)
        MediaType.Video -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(
                    item.localUri,
                    Size(256, 256),
                    null,
                )
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.localUri)
                    retriever.frameAtTime
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }
}.getOrNull()
