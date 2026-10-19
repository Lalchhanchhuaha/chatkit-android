package com.chatkit.compose

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GalleryStripChipShape = RoundedCornerShape(10.dp)
/** Portrait strip chips matching iOS gallery review (9:16). */
private val GalleryStripChipHeight = 96.dp

/**
 * Full-screen gallery review matching camera capture review:
 * large preview, horizontal strip for picks, caption + send.
 */
@Composable
internal fun GalleryMediaReviewDialog(
    theme: ChatTheme,
    items: List<ChatMediaAttachment>,
    onDismiss: () -> Unit,
    onRemove: (ChatMediaAttachment) -> Unit,
    onSend: (caption: String, media: List<ChatMediaAttachment>) -> Unit,
) {
    if (items.isEmpty()) return

    var visible by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(items.map { it.id }) {
        if (items.isEmpty()) return@LaunchedEffect
        if (selectedIndex > items.lastIndex) {
            selectedIndex = items.lastIndex
        }
    }

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
            onRemove = { item ->
                val removingSelected = items.getOrNull(selectedIndex)?.id == item.id
                onRemove(item)
                if (removingSelected && selectedIndex > 0) {
                    selectedIndex -= 1
                }
            },
            caption = caption,
            onCaptionChange = { caption = it },
            sendEnabled = !sending && items.isNotEmpty(),
            onClose = {
                visible = false
                onDismiss()
            },
            onSend = {
                if (sending || items.isEmpty()) return@GalleryMediaReviewScreen
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
    onRemove: (ChatMediaAttachment) -> Unit,
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

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(selectedIndex) {
        stripState.animateScrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
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
            // Do not put a parent clickable here — it steals play/pause taps from video.
            when (selected.mediaType) {
                MediaType.Photo -> GalleryPhotoPreview(uri = selected.localUri)
                MediaType.Video -> GalleryVideoPreview(uri = selected.localUri)
            }
        }

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
                    onClick = {
                        dismissKeyboard()
                        onSelectIndex(index)
                    },
                    onRemove = {
                        dismissKeyboard()
                        onRemove(item)
                    },
                )
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
    var isPlaying by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
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
            update = { view ->
                view.player = player
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Compose overlay (not PlayerView controls) so taps aren't eaten by parent chrome.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .semantics {
                    role = Role.Button
                    contentDescription = if (isPlaying) "Pause video" else "Play video"
                }
                .clickable {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0)
                        }
                        player.play()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun GalleryStripThumb(
    item: ChatMediaAttachment,
    selected: Boolean,
    theme: ChatTheme,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, item.id, item.mediaType, item.localUri) {
        value = withContext(Dispatchers.IO) {
            loadGalleryThumb(context, item)
        }
    }
    Box(
        modifier = Modifier
            .height(GalleryStripChipHeight)
            .aspectRatio(9f / 16f)
            .clip(GalleryStripChipShape)
            .background(Color(0xFF2C2C2E))
            .then(
                if (selected) {
                    Modifier.border(2.dp, theme.accentColor, GalleryStripChipShape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), GalleryStripChipShape)
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
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f))
                .semantics {
                    role = Role.Button
                    contentDescription = "Remove"
                }
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
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
        // Photo Picker video URIs often reject ContentResolver.loadThumbnail.
        // Frame decode via MediaMetadataRetriever works with the temporary grant.
        MediaType.Video -> decodeVideoFrameRespectingRotation(
            context = context,
            uri = item.localUri,
            maxSide = 256,
        )
    }
}.getOrNull()
