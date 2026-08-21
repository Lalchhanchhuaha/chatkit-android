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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)? = null,
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
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            if (isTyping && isViewingNewest) {
                item(key = "chatkit-typing") {
                    AnimatedListRow {
                        TypingIndicatorBubble(typingIndicatorText, theme)
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
                            deliveryStatusContent = deliveryStatusContent,
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
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
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
    val isImeAnimating = isImeAnimating()
    Box(
        Modifier
            .fillMaxWidth()
            .animateItem(
                fadeInSpec = MessageInsertAnimation,
                fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                // A changing IME viewport already moves every row. Running a placement
                // animation at the same time creates the familiar message-list "jump".
                placementSpec = if (isImeAnimating) null else MessagePlacementAnimation,
            ),
    ) {
        content()
    }
}

/** True while Compose's IME animation source and target insets differ. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun isImeAnimating(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.imeAnimationSource.getBottom(density) !=
        WindowInsets.imeAnimationTarget.getBottom(density)
}

@Composable
internal fun UnreadJumpButton(
    count: Int,
    theme: ChatTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (count == 1) "New message" else "$count new messages"
    Row(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(theme.accentColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                contentDescription = label
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = theme.accentContentColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
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
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)? = null,
) {
    val incoming = message.isIncoming
    val corner = if (theme.bubbleCornerRadius == Dp.Unspecified) 14.dp else theme.bubbleCornerRadius
    val bubbleShape = messageBubbleShape(incoming, corner)
    var showActions by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editedText by remember(message.id, message.text) { mutableStateOf(message.text) }
    val canEdit = onEditMessage != null && message.canEdit(Instant.now(), modificationWindowMillis)
    val maximumSwipe = with(LocalDensity.current) { 76.dp.toPx() }
    val replyThreshold = with(LocalDensity.current) { 52.dp.toPx() }
    var swipeTarget by remember(message.id) { mutableFloatStateOf(0f) }
    val swipeOffset by animateFloatAsState(swipeTarget, tween(120), label = "reply-swipe")
    val hasMedia = message.attachments.any { it.isImage || it.isVideo || it.isAudio }
    val sideInset = if (hasMedia) 20.dp else 56.dp
    val trimmedText = message.text.trim()
    val hasText = trimmedText.isNotEmpty()
    val timeText = remember(message.timestampMillis) {
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(message.timestampMillis))
    }
    val showsDelivery = !incoming &&
        theme.showsDeliveryStatus &&
        message.deliveryStatus != DeliveryStatus.None
    val timestampColor = if (incoming) theme.incomingTimestampColor else theme.outgoingTimestampColor
    val textColor = if (incoming) theme.incomingTextColor else theme.outgoingTextColor
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

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

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = ChatBubbleMetrics.maxBubbleWidth(maxWidth, theme.messageMaximumWidth)
        // Match iOS content insets (leading 10, trailing 12).
        val horizontalPadding = 22.dp
        val captionLayout = remember(
            trimmedText,
            hasText,
            hasMedia,
            maxBubble,
            timeText,
            message.isEdited,
            showsDelivery,
            message.deliveryStatus,
            density,
            textMeasurer,
        ) {
            if (!hasText) {
                null
            } else {
                measureBubbleCaption(
                    text = trimmedText,
                    timeText = timeText,
                    isEdited = message.isEdited,
                    showsDelivery = showsDelivery,
                    deliveryStatus = message.deliveryStatus,
                    maxBubbleWidth = maxBubble,
                    horizontalPadding = horizontalPadding,
                    density = density,
                    textMeasurer = textMeasurer,
                )
            }
        }
        // Explicit width like iOS bubbleWidthConstraint — same for sent and received.
        val bubbleWidth = when {
            captionLayout != null -> captionLayout.bubbleWidth
            hasMedia -> maxBubble
            else -> ChatBubbleMetrics.MinimumWidth
        }

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
        ) {
            // iOS HStack: flexible spacer pushes outgoing trailing / incoming leading.
            if (!incoming) {
                Spacer(Modifier.widthIn(min = sideInset).weight(1f))
            }
            Column(horizontalAlignment = Alignment.Start) {
                if (showsSender && incoming && !message.senderName.isNullOrBlank()) {
                    Text(
                        text = message.senderName,
                        color = theme.accentColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 10.dp, bottom = 5.dp, top = 2.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .width(bubbleWidth)
                        .widthIn(max = maxBubble)
                        .background(
                            if (incoming) theme.incomingBubbleColor else theme.outgoingBubbleColor,
                            bubbleShape,
                        )
                        .then(
                            if (incoming) {
                                Modifier.border(1.dp, theme.incomingBubbleBorderColor, bubbleShape)
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(message.id, canEdit, onDeleteMessage, onReply) {
                            detectTapGestures(
                                onTap = {
                                    if (!incoming && message.deliveryStatus == DeliveryStatus.Failed) onRetry()
                                },
                                onLongPress = {
                                    if (onReply != null || canEdit || onDeleteMessage != null) {
                                        showActions = true
                                    }
                                },
                            )
                        }
                        .padding(
                            start = 10.dp,
                            end = 12.dp,
                            top = if (hasMedia && !hasText) 4.dp else 8.dp,
                            bottom = 6.dp,
                        ),
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
                                color = textColor,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    message.attachments.forEach { attachment ->
                        attachmentContent(attachment)
                        if (hasText) Spacer(Modifier.height(6.dp))
                    }
                    if (hasText && captionLayout != null) {
                        InlineTimestampCaption(
                            text = trimmedText,
                            spacer = captionLayout.spacer,
                            timeText = timeText,
                            isEdited = message.isEdited,
                            showsDelivery = showsDelivery,
                            deliveryStatus = message.deliveryStatus,
                            textColor = textColor,
                            timestampColor = timestampColor,
                            theme = theme,
                            onRetry = onRetry,
                            deliveryStatusContent = deliveryStatusContent,
                        )
                    } else if (!hasText) {
                        MessageTimestampFooter(
                            timeText = timeText,
                            isEdited = message.isEdited,
                            showsDelivery = showsDelivery,
                            deliveryStatus = message.deliveryStatus,
                            timestampColor = timestampColor,
                            theme = theme,
                            onRetry = onRetry,
                            deliveryStatusContent = deliveryStatusContent,
                            modifier = Modifier.align(Alignment.End),
                        )
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
            if (incoming) {
                Spacer(Modifier.widthIn(min = sideInset).weight(1f))
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

private data class MeasuredBubbleCaption(
    val bubbleWidth: Dp,
    val spacer: String,
)

private fun measureBubbleCaption(
    text: String,
    timeText: String,
    isEdited: Boolean,
    showsDelivery: Boolean,
    deliveryStatus: DeliveryStatus,
    maxBubbleWidth: Dp,
    horizontalPadding: Dp,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): MeasuredBubbleCaption = with(density) {
    val messageStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val footerStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
    val maxTextWidth = (maxBubbleWidth - horizontalPadding).coerceAtLeast(40.dp)
    val maxTextWidthPx = maxTextWidth.toPx()

    var footerWidthPx = textMeasurer.measure(timeText, footerStyle).size.width.toFloat()
    if (isEdited) {
        footerWidthPx += textMeasurer.measure("Edited", footerStyle).size.width + 4.dp.toPx()
    }
    if (showsDelivery) {
        footerWidthPx += when (deliveryStatus) {
            DeliveryStatus.Delivered, DeliveryStatus.Read -> 18.dp.toPx()
            DeliveryStatus.Failed -> 16.dp.toPx()
            else -> 14.dp.toPx()
        } + 4.dp.toPx()
    }
    footerWidthPx += 6.dp.toPx()

    val unconstrained = textMeasurer.measure(
        text = text,
        style = messageStyle,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(maxWidth = Constraints.Infinity),
    )
    val wrapped = textMeasurer.measure(
        text = text,
        style = messageStyle,
        softWrap = true,
        constraints = Constraints(maxWidth = maxTextWidthPx.toInt().coerceAtLeast(1)),
    )
    val lastLine = (wrapped.lineCount - 1).coerceAtLeast(0)
    val lastLineWidth = if (wrapped.lineCount > 0) {
        wrapped.getLineRight(lastLine) - wrapped.getLineLeft(lastLine)
    } else {
        0f
    }
    val layout = WhatsAppBubbleTextLayoutCalculator.measure(
        naturalTextWidthPx = unconstrained.size.width.toFloat(),
        wrappedTextWidthPx = wrapped.size.width.toFloat(),
        lastLineWidthPx = lastLineWidth,
        textHeightPx = wrapped.size.height.toFloat(),
        lineHeightPx = 22.sp.toPx(),
        maxTextWidthPx = maxTextWidthPx,
        footerWidthPx = footerWidthPx,
    )
    val figureWidthPx = textMeasurer.measure("\u2007", footerStyle).size.width.toFloat().coerceAtLeast(1f)
    val run = WhatsAppBubbleTextLayoutCalculator.footerSpacerString(layout.spacerWidthPx, figureWidthPx)
    val spacer = if (layout.footerOnNewLine) "\n$run" else run
    // Same formula as iOS measuredBubbleWidth (content + padding + slack).
    val bubbleWidth = (layout.contentWidthPx + horizontalPadding.toPx() + 4.dp.toPx())
        .toDp()
        .coerceAtMost(maxBubbleWidth)
        .coerceAtLeast(72.dp)
    MeasuredBubbleCaption(bubbleWidth = bubbleWidth, spacer = spacer)
}

/**
 * iOS/WhatsApp-style caption: message body with a clear footer spacer and the real
 * timestamp/ticks overlaid on the last line (or a dedicated footer line when needed).
 */
@Composable
private fun InlineTimestampCaption(
    text: String,
    spacer: String,
    timeText: String,
    isEdited: Boolean,
    showsDelivery: Boolean,
    deliveryStatus: DeliveryStatus,
    textColor: Color,
    timestampColor: Color,
    theme: ChatTheme,
    onRetry: () -> Unit,
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)?,
) {
    val messageStyle = androidx.compose.ui.text.TextStyle(
        color = textColor,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                append(text)
                withStyle(SpanStyle(color = Color.Transparent, fontSize = 11.sp)) {
                    append(spacer)
                }
            },
            style = messageStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        MessageTimestampFooter(
            timeText = timeText,
            isEdited = isEdited,
            showsDelivery = showsDelivery,
            deliveryStatus = deliveryStatus,
            timestampColor = timestampColor,
            theme = theme,
            onRetry = onRetry,
            deliveryStatusContent = deliveryStatusContent,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun MessageTimestampFooter(
    timeText: String,
    isEdited: Boolean,
    showsDelivery: Boolean,
    deliveryStatus: DeliveryStatus,
    timestampColor: Color,
    theme: ChatTheme,
    onRetry: () -> Unit,
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isEdited) {
            Text("Edited", color = timestampColor, fontSize = 11.sp)
        }
        Text(timeText, color = timestampColor, fontSize = 11.sp)
        if (showsDelivery) {
            if (deliveryStatusContent != null) {
                deliveryStatusContent(deliveryStatus, onRetry)
            } else {
                DeliveryStatus(deliveryStatus, theme, onRetry)
            }
        }
    }
}

/** Smart VC / WhatsApp-style bubble with one sharp tail corner. */
private fun messageBubbleShape(incoming: Boolean, radius: Dp): RoundedCornerShape =
    RoundedCornerShape(
        topStart = radius,
        topEnd = radius,
        bottomStart = if (incoming) 0.dp else radius,
        bottomEnd = if (incoming) radius else 0.dp,
    )

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
                Icon(Icons.Default.PlayCircleFilled, contentDescription = "Play Video", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            val markerIcon = when {
                attachment.isVideo -> Icons.Default.PlayArrow
                attachment.isAudio -> Icons.Default.Audiotrack
                attachment.isImage -> Icons.Default.Image
                else -> Icons.Default.InsertDriveFile
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(markerIcon, contentDescription = "Attachment type", tint = theme.accentColor, modifier = Modifier.size(24.dp))
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
            val audioIcon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow
            Icon(
                audioIcon,
                contentDescription = if (playing) "Pause voice message" else "Play voice message",
                tint = theme.accentContentColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(theme.accentColor)
                    .clickable { audioPlayer.toggle(attachment.id, resolvedUri!!) }
                    .padding(6.dp)
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
            Icon(Icons.Default.Check, contentDescription = "Sent", tint = theme.outgoingTimestampColor, modifier = Modifier.size(14.dp))
        DeliveryStatus.Failed ->
            Icon(Icons.Default.Error, contentDescription = "Failed", tint = Color(0xFFFF8A80), modifier = Modifier.size(14.dp).clickable(onClick = onRetry))
        DeliveryStatus.Delivered ->
            Icon(Icons.Default.DoneAll, contentDescription = "Delivered", tint = theme.outgoingTimestampColor, modifier = Modifier.size(14.dp))
        DeliveryStatus.Read ->
            Icon(Icons.Default.DoneAll, contentDescription = "Read", tint = theme.readReceiptColor, modifier = Modifier.size(14.dp))
    }
}

@Composable
internal fun DateSeparator(label: String, theme: ChatTheme) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = theme.dateSeparatorTextColor,
            fontSize = 12.sp,
            modifier = Modifier
                .background(theme.dateSeparatorBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * WhatsApp/iMessage-style typing indicator: an incoming bubble with the same tail shape
 * as a regular incoming message, containing three bouncing dots.
 */
@Composable
internal fun TypingIndicatorBubble(label: String, theme: ChatTheme) {
    val bubbleShape = RoundedCornerShape(
        topStart = theme.bubbleCornerRadius,
        topEnd = theme.bubbleCornerRadius,
        bottomStart = 0.dp, // tail — matches incoming message shape
        bottomEnd = theme.bubbleCornerRadius,
    )
    val transition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 160, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )
    val dot1Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1y"
    )
    val dot2Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 160, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2y"
    )
    val dot3Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3y"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .background(theme.typingIndicatorBubbleColor, bubbleShape)
                .border(0.5.dp, theme.incomingBubbleBorderColor, bubbleShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .offset(y = dot1Offset.dp)
                    .size(8.dp)
                    .background(
                        theme.typingIndicatorTextColor.copy(alpha = dot1Alpha),
                        CircleShape,
                    )
            )
            Box(
                Modifier
                    .offset(y = dot2Offset.dp)
                    .size(8.dp)
                    .background(
                        theme.typingIndicatorTextColor.copy(alpha = dot2Alpha),
                        CircleShape,
                    )
            )
            Box(
                Modifier
                    .offset(y = dot3Offset.dp)
                    .size(8.dp)
                    .background(
                        theme.typingIndicatorTextColor.copy(alpha = dot3Alpha),
                        CircleShape,
                    )
            )
        }
    }
}
