package com.chatkit.compose

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Date
import java.time.Instant
import kotlin.math.roundToInt

/** Matches iOS UITableView insert cadence (~ease-in-out slide). */
private val MessageInsertAnimation = tween<Float>(
    durationMillis = 320,
    easing = FastOutSlowInEasing,
)
private val MessagePlacementAnimation = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = 320,
    easing = FastOutSlowInEasing,
)

@Composable
internal fun MessageList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    showsSender: Boolean,
    theme: ChatTheme,
    isTyping: Boolean,
    typingIndicatorText: String,
    isViewingNewest: Boolean,
    unreadIncomingCount: Int,
    onTranscriptTap: () -> Unit,
    onMessageRetry: (String) -> Unit,
    modificationWindowMillis: Long,
    onEditMessage: ((String, String) -> Unit)?,
    onDeleteMessage: ((String) -> Unit)?,
    onReplyMessage: ((ChatMessage) -> Unit)?,
    onLoadPreviousMessages: (() -> Unit)?,
    loadPreviousThreshold: Int,
    onUnreadIncomingCountChanged: (Int) -> Unit,
    onNewestVisibilityChanged: (Boolean) -> Unit,
    scrollToNewestRequest: Int,
    onJumpToNewest: () -> Unit,
    attachmentContent: @Composable (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep index 0 as the newest row. reverseLayout anchors that row to the
    // visual bottom without rotating the list (which would also invert gestures).
    // Stable keys let animateItem lift existing rows when a new row is inserted.
    val messageSnapshot = messages.toList()
    val chronologicalItems = remember(messageSnapshot) { buildTranscriptItems(messageSnapshot) }
    val invertedItems = remember(chronologicalItems) { chronologicalItems.asReversed().toList() }
    var trackedLastId by remember { mutableStateOf(messageSnapshot.lastOrNull()?.id) }

    LaunchedEffect(
        listState,
        messageSnapshot.size,
        onLoadPreviousMessages,
        loadPreviousThreshold,
    ) {
        if (onLoadPreviousMessages == null || messageSnapshot.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            val triggerIndex = (listState.layoutInfo.totalItemsCount - loadPreviousThreshold)
                .coerceAtLeast(0)
            lastVisibleIndex >= triggerIndex
        }
            .distinctUntilChanged()
            .first { it }
        onLoadPreviousMessages()
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val firstVisible = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            firstVisible <= 1 && firstOffset <= 48
        }
            .distinctUntilChanged()
            .collect(onNewestVisibilityChanged)
    }

    LaunchedEffect(messageSnapshot.lastOrNull()?.id, invertedItems.size) {
        val last = messageSnapshot.lastOrNull()
        val lastId = last?.id
        if (lastId == null || lastId == trackedLastId) return@LaunchedEffect
        trackedLastId = lastId
        if (invertedItems.isEmpty()) return@LaunchedEffect
        if (isViewingNewest || last.direction == MessageDirection.Outgoing) {
            // Stable keys keep the previously visible row anchored when index 0
            // is inserted. Wait until that row is laid out, then scroll so the
            // new bottom row moves into view while existing rows slide upward.
            val renderedItemCount = invertedItems.size + if (isTyping && isViewingNewest) 1 else 0
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it == renderedItemCount }
            listState.animateScrollToItem(0)
            onUnreadIncomingCountChanged(0)
        } else if (last.isIncoming) {
            onUnreadIncomingCountChanged(unreadIncomingCount + 1)
        }
    }

    LaunchedEffect(scrollToNewestRequest) {
        if (scrollToNewestRequest > 0 && invertedItems.isNotEmpty()) {
            listState.animateScrollToItem(0)
            onUnreadIncomingCountChanged(0)
            onNewestVisibilityChanged(true)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTranscriptTap() })
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            if (isTyping && isViewingNewest) {
                item(key = "chatkit-typing") {
                    AnimatedListRow {
                        TypingIndicator(typingIndicatorText, theme)
                    }
                }
            }
            items(
                items = invertedItems,
                key = { it.stableKey() },
            ) { item ->
                AnimatedListRow {
                    when (item) {
                        is TranscriptItem.DaySeparator -> DateSeparator(item.label, theme)
                        is TranscriptItem.Message -> MessageBubble(
                            message = item.message,
                            showsSender = showsSender,
                            theme = theme,
                            onRetry = { onMessageRetry(item.message.id) },
                            modificationWindowMillis = modificationWindowMillis,
                            onEditMessage = onEditMessage,
                            onDeleteMessage = onDeleteMessage,
                            onReply = onReplyMessage?.let { reply ->
                                { reply(item.message) }
                            },
                            attachmentContent = attachmentContent,
                        )
                    }
                }
            }
        }

        if (unreadIncomingCount > 0) {
            UnreadJumpButton(
                count = unreadIncomingCount,
                theme = theme,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp),
                onClick = onJumpToNewest,
            )
        }
    }
}

private fun TranscriptItem.stableKey(): String = when (this) {
    is TranscriptItem.DaySeparator -> id
    is TranscriptItem.Message -> message.id
}

/**
 * Placement animation on [animateItem] is what creates the iOS-style slide-up.
 */
@Composable
private fun LazyItemScope.AnimatedListRow(
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .animateItem(
                fadeInSpec = MessageInsertAnimation,
                fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                placementSpec = MessagePlacementAnimation,
            ),
    ) {
        content()
    }
}

@Composable
internal fun UnreadJumpButton(
    count: Int,
    theme: ChatTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .shadow(5.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(theme.accentColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .height(32.dp)
            .semantics {
                contentDescription = "$count new message${if (count == 1) "" else "s"}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("↓", color = theme.accentContentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("$count", color = theme.accentContentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    showsSender: Boolean,
    theme: ChatTheme,
    onRetry: () -> Unit,
    modificationWindowMillis: Long,
    onEditMessage: ((String, String) -> Unit)?,
    onDeleteMessage: ((String) -> Unit)?,
    onReply: (() -> Unit)?,
    attachmentContent: @Composable (ChatAttachment) -> Unit,
) {
    val incoming = message.isIncoming
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editedText by remember(message.id, message.text) { mutableStateOf(message.text) }
    val canEdit = onEditMessage != null && message.canEdit(Instant.now(), modificationWindowMillis)
    val maximumSwipe = with(LocalDensity.current) { 76.dp.toPx() }
    val replyThreshold = with(LocalDensity.current) { 52.dp.toPx() }
    var swipeTarget by remember(message.id) { mutableFloatStateOf(0f) }
    val swipeOffset by animateFloatAsState(swipeTarget, tween(120), label = "reply-swipe")
    val swipeModifier = if (onReply == null) {
        Modifier
    } else {
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                swipeTarget = (swipeTarget + delta).coerceIn(0f, maximumSwipe)
            },
            onDragStopped = {
                if (swipeTarget >= replyThreshold) onReply()
                swipeTarget = 0f
            },
        )
    }
    Box(Modifier.fillMaxWidth()) {
        if (swipeOffset > 4f) {
            Text(
                text = "↩",
                color = theme.accentColor,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .then(swipeModifier),
            horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
        ) {
        Box {
            Column(
                modifier = Modifier
                .then(
                    if (theme.messageMaximumWidth == Dp.Unspecified) Modifier.fillMaxWidth(0.78f)
                    else Modifier.widthIn(max = theme.messageMaximumWidth),
                )
                .background(
                    if (incoming) theme.incomingBubbleColor else theme.outgoingBubbleColor,
                    RoundedCornerShape(theme.bubbleCornerRadius),
                )
                .then(
                    if (incoming) {
                        Modifier.border(0.5.dp, theme.incomingBubbleBorderColor, RoundedCornerShape(theme.bubbleCornerRadius))
                    } else {
                        Modifier
                    },
                )
                .pointerInput(message.id, canEdit, onDeleteMessage, onReply) {
                    detectTapGestures(
                        onTap = { if (!incoming && message.deliveryStatus == DeliveryStatus.Failed) onRetry() },
                        onLongPress = {
                            if (onReply != null || canEdit || onDeleteMessage != null) {
                                showActions = true
                            }
                        },
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
            if (message.replyToMessageId != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = message.replyToSenderName?.takeIf { it.isNotBlank() } ?: "Reply",
                        color = theme.accentColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = message.replyToMessageText?.ifBlank { "Attachment" } ?: "Message",
                        color = if (incoming) theme.incomingTextColor else theme.outgoingTextColor,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (showsSender && incoming && !message.senderName.isNullOrBlank()) {
                Text(message.senderName, color = theme.accentColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
            }
            message.attachments.forEach { attachment ->
                attachmentContent(attachment)
                if (message.text.isNotBlank()) Spacer(Modifier.height(6.dp))
            }
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    color = if (incoming) theme.incomingTextColor else theme.outgoingTextColor,
                    fontSize = 16.sp,
                )
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    remember(message.timestampMillis, context) {
                        android.text.format.DateFormat.getTimeFormat(context).format(Date(message.timestampMillis))
                    },
                    color = if (incoming) theme.incomingTimestampColor else theme.outgoingTimestampColor,
                    fontSize = 10.sp,
                )
                if (message.isEdited) {
                    Text(" · edited", color = if (incoming) theme.incomingTimestampColor else theme.outgoingTimestampColor, fontSize = 10.sp)
                }
                if (!incoming && theme.showsDeliveryStatus) {
                    Spacer(Modifier.widthIn(min = 4.dp))
                    DeliveryStatus(message.deliveryStatus, theme, onRetry)
                }
            }
            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                if (onReply != null) {
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        onClick = { showActions = false; onReply() },
                    )
                }
                if (canEdit) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { showActions = false; showEditDialog = true },
                    )
                }
                if (onDeleteMessage != null) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showActions = false; onDeleteMessage(message.id) },
                    )
                }
            }
        }
    }
    }
    }
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit message") },
            text = { OutlinedTextField(editedText, { editedText = it }, maxLines = 5) },
            confirmButton = {
                TextButton(
                    enabled = editedText.trim().isNotEmpty(),
                    onClick = {
                        onEditMessage?.invoke(message.id, editedText.trim())
                        showEditDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun DefaultAttachment(
    attachment: ChatAttachment,
    theme: ChatTheme,
    automaticallyLoadsImages: Boolean = true,
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    onCancelUpload: (ChatAttachment) -> Unit = {},
    audioPlayer: AudioPlayerController,
) {
    val context = LocalContext.current
    val resolvedUri by produceState<android.net.Uri?>(attachment.localUri, attachment.id, automaticallyLoadsImages) {
        val local = attachment.localUri
        val available = runCatching { attachmentResolver.isAvailableLocally(attachment) }.getOrDefault(false)
        val provided = if (automaticallyLoadsImages || available || !attachment.isImage) {
            runCatching { attachmentResolver.resolveContent(attachment) }.getOrNull()
        } else null
        value = when {
            local != null -> local
            provided != null && (automaticallyLoadsImages || available || !attachment.isImage) -> provided
            else -> null
        }
    }
    val posterUri by produceState<android.net.Uri?>(attachment.posterUri, attachment.id) {
        value = if (attachment.isVideo) runCatching { attachmentResolver.resolvePoster(attachment) }.getOrNull() else null
    }
    val displayUri = resolvedUri ?: posterUri
    val bitmap by produceState<ImageBitmap?>(null, displayUri) {
        value = if (displayUri != null && (attachment.isImage || attachment.isVideo)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(displayUri)
                        .use { BitmapFactory.decodeStream(it) }
                        ?.asImageBitmap()
                }.getOrNull()
            }
        } else {
            null
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (bitmap != null) 180.dp else 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.thumbnailPlaceholderBackgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap!!, attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (attachment.isVideo) {
                Text("▶", color = Color.White, fontSize = 28.sp)
            }
        } else {
            val marker = when {
                attachment.isVideo -> "▶"
                attachment.isAudio -> "♪"
                attachment.isImage -> "▧"
                else -> "▤"
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(marker, color = theme.accentColor, fontSize = 22.sp)
                Text(
                    attachment.fileName,
                    Modifier.padding(start = 10.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.incomingTextColor,
                )
            }
        }
        if (attachment.isAudio && resolvedUri != null) {
            val playing = audioPlayer.activeAttachmentId == attachment.id && audioPlayer.isPlaying
            Text(
                if (playing) "❚❚" else "▶",
                color = theme.accentColor,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .clickable { audioPlayer.toggle(attachment.id, resolvedUri!!) }
                    .semantics { contentDescription = if (playing) "Pause voice message" else "Play voice message" },
            )
        }
        when (val transfer = attachment.transferState) {
            is TransferState.Uploading -> Box(Modifier.clickable { onCancelUpload(attachment) }) {
                CircularProgressIndicator(
                    progress = { transfer.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(34.dp).semantics { contentDescription = "Cancel upload" },
                    color = theme.accentColor,
                )
            }
            TransferState.Failed ->
                Text("Upload failed", color = Color(0xFFB3261E), fontSize = 12.sp)
            TransferState.Uploaded -> Unit
        }
    }
}

@Composable
private fun DeliveryStatus(status: DeliveryStatus, theme: ChatTheme, onRetry: () -> Unit) {
    when (status) {
        DeliveryStatus.None -> Unit
        DeliveryStatus.Pending, DeliveryStatus.Sent ->
            Text("✓", color = theme.outgoingTimestampColor, fontSize = 11.sp)
        DeliveryStatus.Failed ->
            Text("!", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onRetry))
        DeliveryStatus.Delivered ->
            Text("✓✓", color = theme.outgoingTimestampColor, fontSize = 11.sp)
        DeliveryStatus.Read ->
            Text("✓✓", color = theme.readReceiptColor, fontSize = 11.sp)
    }
}

@Composable
internal fun DateSeparator(label: String, theme: ChatTheme) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = theme.dateSeparatorTextColor,
            fontSize = 12.sp,
            modifier = Modifier
                .background(theme.dateSeparatorBackground, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
internal fun TypingIndicator(label: String, theme: ChatTheme) {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        0.35f,
        1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "typing-alpha",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            "•••",
            color = theme.typingIndicatorTextColor.copy(alpha = alpha),
            fontSize = 20.sp,
            modifier = Modifier
                .background(theme.typingIndicatorBubbleColor, RoundedCornerShape(theme.bubbleCornerRadius))
                .border(1.dp, theme.incomingBubbleBorderColor, RoundedCornerShape(theme.bubbleCornerRadius))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
