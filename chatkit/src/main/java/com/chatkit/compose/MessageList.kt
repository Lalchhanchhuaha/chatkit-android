package com.chatkit.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.hypot
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
    conversationId: String?,
    listState: LazyListState,
    showsSender: Boolean,
    theme: ChatTheme,
    isViewingNewest: Boolean,
    unreadIncomingCount: Int,
    onTranscriptTap: () -> Unit,
    onMessageRetry: (String) -> Unit,
    onReplyMessage: ((ChatMessage) -> Unit)?,
    isMessageSelectionMode: Boolean,
    selectedMessageIds: Set<String>,
    onMessageLongPress: ((ChatMessage) -> Unit)?,
    onMessageSelectionTap: (ChatMessage) -> Unit,
    onLoadPreviousMessages: (() -> Unit)?,
    olderMessagesPageSize: Int,
    loadPreviousThreshold: Int,
    onUnreadIncomingCountChanged: (Int) -> Unit,
    onNewestVisibilityChanged: (Boolean) -> Unit,
    scrollToNewestRequest: Int,
    onJumpToNewest: () -> Unit,
    attachmentContent: (@Composable (ChatAttachment) -> Unit)?,
    automaticallyLoadsImages: Boolean = true,
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    onCancelAttachmentUpload: (ChatAttachment) -> Unit = {},
    onCancelAttachmentDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachmentDownload: (ChatAttachment) -> Unit = {},
    audioPlayer: AudioPlayerController? = null,
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Keep index 0 as the newest row. reverseLayout anchors that row to the
    // visual bottom without rotating the list (which would also invert gestures).
    // Stable keys let animateItem lift existing rows when a new row is inserted.
    // ChatView already supplies an immutable snapshot. Avoid copying the full transcript
    // again on every composer/typing recomposition.
    val messageSnapshot = messages
    val chronologicalItems = remember(messageSnapshot) { buildTranscriptItems(messageSnapshot) }
    val invertedItems = remember(chronologicalItems) { chronologicalItems.asReversed() }
    var trackedLastId by remember(conversationId) {
        mutableStateOf(messageSnapshot.lastOrNull()?.id)
    }
    var previousMessageIds by remember(conversationId) { mutableStateOf<Set<String>?>(null) }
    var hasPositionedTranscript by remember(conversationId) { mutableStateOf(false) }
    var requestedForOldestId by remember(conversationId) { mutableStateOf<String?>(null) }
    val currentLoadPreviousMessages by rememberUpdatedState(onLoadPreviousMessages)
    val currentMessageIds = remember(messageSnapshot) {
        messageSnapshot.asSequence().map(ChatMessage::id).toSet()
    }
    val isDifferentTranscript = previousMessageIds?.let { previousIds ->
        previousIds.isNotEmpty() && previousIds.intersect(currentMessageIds).isEmpty()
    } == true
    // Never animate the first population of a transcript. Animating every row on
    // initial composition makes only the bottom few appear immediately while the
    // rest visibly settle into place.
    val animateTranscriptChanges = hasPositionedTranscript && !isDifferentTranscript

    LaunchedEffect(
        listState,
        messageSnapshot.firstOrNull()?.id,
        messageSnapshot.size,
        olderMessagesPageSize,
        loadPreviousThreshold,
    ) {
        val oldestId = messageSnapshot.firstOrNull()?.id ?: return@LaunchedEffect
        if (currentLoadPreviousMessages == null ||
            messageSnapshot.size < olderMessagesPageSize ||
            requestedForOldestId == oldestId
        ) return@LaunchedEffect
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            shouldLoadPreviousMessages(
                totalItemsCount = listState.layoutInfo.totalItemsCount,
                lastVisibleItemIndex = lastVisibleIndex,
                loadPreviousThreshold = loadPreviousThreshold,
            )
        }
            .distinctUntilChanged()
            .first { it }
        requestedForOldestId = oldestId
        currentLoadPreviousMessages?.invoke()
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex < AutoScrollItemThreshold
        }
            .distinctUntilChanged()
            .collect(onNewestVisibilityChanged)
    }

    LaunchedEffect(messageSnapshot) {
        val last = messageSnapshot.lastOrNull()
        val lastId = last?.id
        if (lastId == null || invertedItems.isEmpty()) {
            // A temporarily empty list is how hosts commonly represent a chat
            // switch while the next conversation is loading. The next non-empty
            // snapshot must be positioned as a fresh transcript.
            hasPositionedTranscript = false
            previousMessageIds = null
            trackedLastId = null
            return@LaunchedEffect
        }

        if (!hasPositionedTranscript || isDifferentTranscript) {
            // Establish the iOS-style initial position without an animation. A
            // newly opened chat should render its newest messages immediately,
            // even when this ChatView instance was previously showing history.
            listState.scrollToItem(0)
            onUnreadIncomingCountChanged(0)
            onNewestVisibilityChanged(true)
            trackedLastId = lastId
            previousMessageIds = currentMessageIds
            hasPositionedTranscript = true
            return@LaunchedEffect
        }

        val newlyAddedMessages = messageSnapshot.filter { it.id !in previousMessageIds.orEmpty() }
        previousMessageIds = currentMessageIds
        if (lastId == trackedLastId) return@LaunchedEffect
        trackedLastId = lastId
        if (shouldScrollToNewestOnNewMessage(
                isViewingNewest = isViewingNewest,
                hasOutgoingMessage = newlyAddedMessages.any { it.direction == MessageDirection.Outgoing },
                isScrollInProgress = listState.isScrollInProgress,
            )
        ) {
            // Stable keys keep the previously visible row anchored when index 0
            // is inserted. Wait until that row is laid out, then scroll so the
            // new bottom row moves into view while existing rows slide upward.
            val renderedItemCount = invertedItems.size
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it >= renderedItemCount }
            listState.animateScrollToItem(0)
            onUnreadIncomingCountChanged(0)
        } else if (!isViewingNewest) {
            val newIncomingCount = newlyAddedMessages.count(ChatMessage::isIncoming)
            if (newIncomingCount > 0) {
                onUnreadIncomingCountChanged(unreadIncomingCount + newIncomingCount)
            }
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
                start = 0.dp,
                end = 0.dp,
                top = 16.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.Bottom,
        ) {
            itemsIndexed(
                items = invertedItems,
                key = { _, item -> item.stableKey() },
            ) { index, item ->
                AnimatedListRow(animate = animateTranscriptChanges) {
                    when (item) {
                        is TranscriptItem.DaySeparator -> DateSeparator(item.label, theme)
                        is TranscriptItem.Message -> MessageBubble(
                            message = item.message,
                            rowVerticalInset = messageRowVerticalInset(
                                items = invertedItems,
                                index = index,
                                message = item.message,
                            ),
                            showsSender = showsSender,
                            theme = theme,
                            onRetry = { onMessageRetry(item.message.id) },
                            onReply = onReplyMessage?.let { reply ->
                                { reply(item.message) }
                            },
                            isMessageSelectionMode = isMessageSelectionMode,
                            isSelected = item.message.id in selectedMessageIds,
                            onLongPress = onMessageLongPress?.let { callback ->
                                { callback(item.message) }
                            },
                            onSelectionTap = { onMessageSelectionTap(item.message) },
                            attachmentContent = attachmentContent,
                            automaticallyLoadsImages = automaticallyLoadsImages,
                            attachmentResolver = attachmentResolver,
                            onCancelAttachmentUpload = onCancelAttachmentUpload,
                            onCancelAttachmentDownload = onCancelAttachmentDownload,
                            onRetryAttachmentDownload = onRetryAttachmentDownload,
                            audioPlayer = audioPlayer,
                            deliveryStatusContent = deliveryStatusContent,
                        )
                    }
                }
            }
        }

        if (!isViewingNewest || unreadIncomingCount > 0) {
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

private const val AutoScrollItemThreshold = 3

internal fun shouldScrollToNewestOnNewMessage(
    isViewingNewest: Boolean,
    hasOutgoingMessage: Boolean,
    isScrollInProgress: Boolean,
): Boolean = !isScrollInProgress && (isViewingNewest || hasOutgoingMessage)

internal fun shouldLoadPreviousMessages(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int,
    loadPreviousThreshold: Int,
): Boolean = totalItemsCount > loadPreviousThreshold &&
    lastVisibleItemIndex > totalItemsCount - loadPreviousThreshold - 1

private fun messageRowVerticalInset(
    items: List<TranscriptItem>,
    index: Int,
    message: ChatMessage,
): Dp {
    val hasDirectionChange = listOf(index - 1, index + 1).any { neighborIndex ->
        val neighbor = items.getOrNull(neighborIndex) as? TranscriptItem.Message
        neighbor != null && neighbor.message.direction != message.direction
    }
    return if (hasDirectionChange) 10.dp else 2.dp
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
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    val rowModifier = if (animate) {
        val isImeAnimating = isImeAnimating()
        Modifier
            .fillMaxWidth()
            .animateItem(
                fadeInSpec = MessageInsertAnimation,
                fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                // A changing IME viewport already moves every row. Running a placement
                // animation at the same time creates the familiar message-list "jump".
                placementSpec = if (isImeAnimating) null else MessagePlacementAnimation,
            )
    } else {
        Modifier.fillMaxWidth()
    }
    Box(
        rowModifier,
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
    val label = when (count) {
        0 -> "Jump to newest"
        1 -> "New message"
        else -> "$count new messages"
    }
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
    rowVerticalInset: Dp = 2.dp,
    showsSender: Boolean,
    theme: ChatTheme,
    onRetry: () -> Unit,
    onReply: (() -> Unit)?,
    isMessageSelectionMode: Boolean,
    isSelected: Boolean,
    onLongPress: (() -> Unit)?,
    onSelectionTap: () -> Unit,
    attachmentContent: (@Composable (ChatAttachment) -> Unit)?,
    automaticallyLoadsImages: Boolean = true,
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    onCancelAttachmentUpload: (ChatAttachment) -> Unit = {},
    onCancelAttachmentDownload: (ChatAttachment) -> Unit = {},
    onRetryAttachmentDownload: (ChatAttachment) -> Unit = {},
    audioPlayer: AudioPlayerController? = null,
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)? = null,
) {
    val incoming = message.isIncoming
    val corner = if (theme.bubbleCornerRadius == Dp.Unspecified) 12.dp else theme.bubbleCornerRadius
    val bubbleShape = messageBubbleShape(incoming, corner)
    val replyThreshold = with(LocalDensity.current) { MessageSwipeToReply.ThresholdDp.dp.toPx() }
    val maximumSwipe = with(LocalDensity.current) { MessageSwipeToReply.MaxPullDp.dp.toPx() }
    var swipeRaw by remember(message.id) { mutableFloatStateOf(0f) }
    var isSwipeDragging by remember(message.id) { mutableStateOf(false) }
    var didFireThresholdHaptic by remember(message.id) { mutableStateOf(false) }
    val swipePull = MessageSwipeToReply.resistedPull(swipeRaw, replyThreshold, maximumSwipe)
    val swipeOffset by animateFloatAsState(
        targetValue = if (isSwipeDragging) swipePull else 0f,
        animationSpec = tween(if (isSwipeDragging) 0 else 220),
        label = "reply-swipe",
    )
    val swipeProgress = MessageSwipeToReply.progress(swipeOffset, replyThreshold)
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
        message.deliveryStatus.displayedDeliveryStatus != DeliveryStatus.None
    val timestampColor = if (incoming) theme.incomingTimestampColor else theme.outgoingTimestampColor
    val textColor = if (incoming) theme.incomingTextColor else theme.outgoingTextColor
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val currentLongPress by rememberUpdatedState(onLongPress)
    val rowGestureModifier = Modifier.pointerInput(message.id) {
        val allowedMovement = 20.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val initialPosition = down.position

            if (currentLongPress == null) return@awaitEachGesture

            var movedTooFar = false
            var released = false
            val endedBeforeDeadline = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        released = true
                        break
                    }
                    val delta = change.position - initialPosition
                    if (hypot(delta.x, delta.y) > allowedMovement) {
                        movedTooFar = true
                        break
                    }
                }
                true
            }

            if (endedBeforeDeadline != null || movedTooFar || released) return@awaitEachGesture

            hapticFeedback.performHapticFeedback(
                HapticFeedbackType.LongPress,
            )
            currentLongPress?.invoke()

            // Consume the release so a media/document button cannot open after the
            // long press has already entered selection mode.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }

    val swipeModifier = if (onReply == null || isMessageSelectionMode) {
        Modifier
    } else {
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                isSwipeDragging = true
                swipeRaw = (swipeRaw + delta).coerceAtLeast(0f)
                val pull = MessageSwipeToReply.resistedPull(swipeRaw, replyThreshold, maximumSwipe)
                if (MessageSwipeToReply.didCrossThreshold(pull, replyThreshold)) {
                    if (!didFireThresholdHaptic) {
                        didFireThresholdHaptic = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                } else {
                    didFireThresholdHaptic = false
                }
            },
            onDragStopped = {
                val pull = MessageSwipeToReply.resistedPull(swipeRaw, replyThreshold, maximumSwipe)
                if (MessageSwipeToReply.didCrossThreshold(pull, replyThreshold)) onReply()
                isSwipeDragging = false
                swipeRaw = 0f
                didFireThresholdHaptic = false
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = ChatBubbleMetrics.maxBubbleWidth(maxWidth, theme.messageMaximumWidth)
        // Content insets: a little extra leading for body text, trailing so the time
        // isn't flush against the bubble edge (leading 10 + trailing 12).
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
        // Visual media always uses the max bubble width so a short caption cannot shrink
        // the photo/video tile (iOS / WhatsApp behavior).
        val hasVisualMedia = message.attachments.any { it.isImage || it.isVideo }
        val isSingleImageMessage = message.attachments.size == 1 &&
            message.attachments.first().isImage
        val singleImageBubbleWidth = if (isSingleImageMessage) {
            message.attachments.first().aspectRatio
                ?.takeIf { it.isFinite() && it > 0f }
                ?.let { aspect ->
                    val mediaWidth = (maxBubble - 8.dp).coerceAtLeast(72.dp)
                    val safeAspect = aspect.coerceAtLeast(0.05f)
                    if (safeAspect < 0.9f) {
                        val height = minOf(340.dp, mediaWidth / safeAspect)
                        minOf(mediaWidth, height * safeAspect) + 8.dp
                    } else {
                        maxBubble
                    }
                }
        } else {
            null
        }
        val contentBubbleWidth = when {
            hasVisualMedia -> maxOf(
                singleImageBubbleWidth ?: maxBubble,
                captionLayout?.bubbleWidth ?: ChatBubbleMetrics.MinimumWidth,
            )
            captionLayout != null -> captionLayout.bubbleWidth
            hasMedia -> maxBubble
            else -> ChatBubbleMetrics.MinimumWidth
        }
        val bubbleWidth = if (message.replyToMessageId != null) {
            maxOf(contentBubbleWidth, minOf(maxBubble, 240.dp))
        } else {
            contentBubbleWidth
        }

        if (swipeProgress > 0.01f) {
            SwipeReplyAffordance(
                progress = swipeProgress,
                pullPx = swipeOffset,
                arrowColor = theme.outgoingBubbleColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isSelected) theme.accentColor.copy(alpha = 0.12f) else Color.Transparent)
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .then(swipeModifier)
                .then(rowGestureModifier)
                .padding(horizontal = 10.dp, vertical = rowVerticalInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isMessageSelectionMode) {
                MessageSelectionCheckmark(isSelected = isSelected, theme = theme)
                Spacer(Modifier.width(8.dp))
            }
            // Match iOS: the selection check has one stable leading location for
            // both directions; only the outgoing bubble is pushed to the trailing edge.
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
                        .clip(bubbleShape)
                        .then(
                            if (incoming) {
                                Modifier.border(1.dp, theme.incomingBubbleBorderColor, bubbleShape)
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(message.id, isMessageSelectionMode, onReply) {
                            detectTapGestures(
                                onTap = {
                                    if (!isMessageSelectionMode &&
                                        !incoming && message.deliveryStatus == DeliveryStatus.Failed
                                    ) {
                                        onRetry()
                                    }
                                },
                            )
                        },
                ) {
                    val hasMediaAttachments = message.attachments.any {
                        it.isImage || it.isVideo || it.isAudio
                    }
                    if (message.replyToMessageId != null) {
                        val replyWasIncoming = message.replyToWasIncoming
                            ?: !message.replyToSenderName.equals("You", ignoreCase = true)
                        MessageReplyQuote(
                            senderName = message.replyToSenderName?.takeIf(String::isNotBlank)
                                ?: if (replyWasIncoming) "Contact" else "You",
                            previewText = message.replyToMessageText
                                ?.takeIf(String::isNotBlank)
                                ?: message.replyToAttachment?.fileName
                                ?: "Message",
                            wasIncoming = replyWasIncoming,
                            attachment = message.replyToAttachment,
                            containingMessageIsIncoming = incoming,
                            compact = message.attachments.any(ChatAttachment::isAudio) &&
                                message.attachments.none { it.isImage || it.isVideo },
                            theme = theme,
                            attachmentResolver = attachmentResolver,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (hasMediaAttachments) 6.dp else 8.dp)
                                .padding(top = if (hasMediaAttachments) 5.dp else 8.dp, bottom = 4.dp),
                        )
                    }
                    if (attachmentContent != null) {
                        Column(
                            modifier = Modifier.padding(
                                start = 10.dp,
                                end = 12.dp,
                                top = if (hasMedia && !hasText) 4.dp else 6.dp,
                                bottom = 5.dp,
                            ),
                        ) {
                            message.attachments.forEach { attachment ->
                                attachmentContent(attachment)
                                if (hasText) Spacer(Modifier.height(6.dp))
                            }
                            MessageBubbleCaptionOrFooter(
                                hasText = hasText,
                                captionLayout = captionLayout,
                                trimmedText = trimmedText,
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
                        }
                    } else {
                        if (message.attachments.isNotEmpty() && audioPlayer != null) {
                            MessageAttachmentsContent(
                                message = message,
                                theme = theme,
                                maxBubbleWidth = maxBubble,
                                automaticallyLoadsImages = automaticallyLoadsImages,
                                attachmentResolver = attachmentResolver,
                                onCancelUpload = onCancelAttachmentUpload,
                                onCancelDownload = onCancelAttachmentDownload,
                                onRetryAttachment = onRetryAttachmentDownload,
                                audioPlayer = audioPlayer,
                                onSingleImageBubbleWidthChanged = {},
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 12.dp)
                                .padding(
                                    top = when {
                                        !hasMediaAttachments && message.replyToMessageId == null -> 6.dp
                                        hasText || !hasMediaAttachments -> 5.dp
                                        else -> 2.dp
                                    },
                                    bottom = 5.dp,
                                ),
                        ) {
                            MessageBubbleCaptionOrFooter(
                                hasText = hasText,
                                captionLayout = captionLayout,
                                trimmedText = trimmedText,
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
                        }
                    }
                }
            }
            if (incoming) {
                Spacer(Modifier.widthIn(min = sideInset).weight(1f))
            }
        }

        if (isMessageSelectionMode) {
            // A topmost transparent surface owns the entire row while selecting.
            // This prevents links, attachments, retry buttons, and empty bubble
            // margins from intercepting taps intended to add/remove selection.
            // Compose 1.10+: Modifier.matchParentSize() is a BoxScope member (no import).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(100f)
                    .clickable(onClick = onSelectionTap)
                    .semantics {
                        contentDescription = if (isSelected) {
                            "Deselect message"
                        } else {
                            "Select message"
                        }
                    },
            )
        }
    }
}

@Composable
private fun MessageSelectionCheckmark(isSelected: Boolean, theme: ChatTheme) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color.White else Color.Transparent)
            .then(
                if (isSelected) {
                    Modifier
                } else {
                    Modifier.border(
                        width = 1.5.dp,
                        color = theme.accentColor.copy(alpha = 0.65f),
                        shape = CircleShape,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = theme.accentColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private data class MeasuredBubbleCaption(
    val bubbleWidth: Dp,
    val placeholder: String,
)

@Composable
private fun MessageBubbleCaptionOrFooter(
    hasText: Boolean,
    captionLayout: MeasuredBubbleCaption?,
    trimmedText: String,
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
    if (hasText && captionLayout != null) {
        InlineTimestampCaption(
            text = trimmedText,
            placeholder = captionLayout.placeholder,
            timeText = timeText,
            isEdited = isEdited,
            showsDelivery = showsDelivery,
            deliveryStatus = deliveryStatus,
            textColor = textColor,
            timestampColor = timestampColor,
            theme = theme,
            onRetry = onRetry,
            deliveryStatusContent = deliveryStatusContent,
        )
    } else if (!hasText) {
        Box(Modifier.fillMaxWidth()) {
            MessageTimestampFooter(
                timeText = timeText,
                isEdited = isEdited,
                showsDelivery = showsDelivery,
                deliveryStatus = deliveryStatus,
                timestampColor = timestampColor,
                theme = theme,
                onRetry = onRetry,
                deliveryStatusContent = deliveryStatusContent,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * Builds a transparent trailing placeholder that matches the real footer width as closely
 * as possible (actual time / Edited text + tick slots). Avoids figure-space runs that are
 * wider than the footer and wrap onto a new line while the last text line still looks empty.
 */
private fun footerPlaceholder(
    timeText: String,
    isEdited: Boolean,
    showsDelivery: Boolean,
    deliveryStatus: DeliveryStatus,
): String = buildString {
    // Gap between body and footer (extra NBSPs keep timestamp clear of text).
    append("\u00A0\u00A0\u00A0\u00A0")
    if (isEdited) append("Edited ")
    append(timeText)
    if (showsDelivery) {
        // Approximate tick / double-tick icon width at 10sp.
        append(
            when (deliveryStatus) {
                DeliveryStatus.Delivered, DeliveryStatus.Read -> "\u2007\u2007\u2007\u2007"
                DeliveryStatus.Failed -> "\u2007\u2007\u2007"
                else -> "\u2007\u2007\u2007"
            },
        )
    }
}

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
    val messageStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val maxTextWidth = (maxBubbleWidth - horizontalPadding).coerceAtLeast(40.dp)
    val maxTextWidthPx = maxTextWidth.toPx().toInt().coerceAtLeast(1)
    val placeholder = footerPlaceholder(timeText, isEdited, showsDelivery, deliveryStatus)
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(color = Color.Transparent, fontSize = 10.sp)) {
            append(placeholder)
        }
    }

    // Preferred width if text + footer stay on one line.
    val singleLine = textMeasurer.measure(
        text = annotated,
        style = messageStyle,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(maxWidth = Constraints.Infinity),
    )
    val contentWidthPx = if (singleLine.size.width <= maxTextWidthPx) {
        singleLine.size.width.toFloat()
    } else {
        // Body needs to wrap; size to the wrapped layout (footer may sit on last or next line).
        val wrapped = textMeasurer.measure(
            text = annotated,
            style = messageStyle,
            softWrap = true,
            constraints = Constraints(maxWidth = maxTextWidthPx),
        )
        // Prefer growing to fit last-line footer when the calculator says it fits.
        val bodyOnly = textMeasurer.measure(
            text = text,
            style = messageStyle,
            softWrap = true,
            constraints = Constraints(maxWidth = maxTextWidthPx),
        )
        val lastLine = (bodyOnly.lineCount - 1).coerceAtLeast(0)
        val lastLineWidth = if (bodyOnly.lineCount > 0) {
            bodyOnly.getLineRight(lastLine) - bodyOnly.getLineLeft(lastLine)
        } else {
            0f
        }
        val footerWidthPx = textMeasurer.measure(
            text = androidx.compose.ui.text.AnnotatedString(
                placeholder,
                spanStyles = listOf(
                    androidx.compose.ui.text.AnnotatedString.Range(
                        SpanStyle(fontSize = 10.sp),
                        0,
                        placeholder.length,
                    ),
                ),
            ),
            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = Constraints.Infinity),
        ).size.width.toFloat()
        val layout = WhatsAppBubbleTextLayoutCalculator.measure(
            naturalTextWidthPx = textMeasurer.measure(
                text = text,
                style = messageStyle,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = Constraints.Infinity),
            ).size.width.toFloat(),
            wrappedTextWidthPx = bodyOnly.size.width.toFloat(),
            lastLineWidthPx = lastLineWidth,
            textHeightPx = bodyOnly.size.height.toFloat(),
            lineHeightPx = 19.sp.toPx(),
            maxTextWidthPx = maxTextWidthPx.toFloat(),
            footerWidthPx = footerWidthPx,
        )
        if (!layout.footerOnNewLine) {
            layout.contentWidthPx
        } else {
            maxOf(wrapped.size.width.toFloat(), maxTextWidthPx.toFloat())
        }
    }

    val bubbleWidth = (contentWidthPx + horizontalPadding.toPx() + 6.dp.toPx())
        .toDp()
        .coerceAtMost(maxBubbleWidth)
        .coerceAtLeast(72.dp)
    MeasuredBubbleCaption(bubbleWidth = bubbleWidth, placeholder = placeholder)
}

/**
 * iOS/WhatsApp-style caption: body + transparent footer placeholder, with the real
 * timestamp/ticks overlaid at bottom-end. Placeholder uses the real time string so it
 * does not wrap early and leave an empty gap on the last text line.
 */
@Composable
private fun InlineTimestampCaption(
    text: String,
    placeholder: String,
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
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                append(text)
                withStyle(SpanStyle(color = Color.Transparent, fontSize = 10.sp)) {
                    append(placeholder)
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
            Text("Edited", color = timestampColor, fontSize = 10.sp)
        }
        Text(timeText, color = timestampColor, fontSize = 10.sp)
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
private fun DeliveryStatus(status: DeliveryStatus, theme: ChatTheme, onRetry: () -> Unit) {
    DeliveryReceiptMark(
        status = status,
        theme = theme,
        timestampColor = theme.outgoingTimestampColor,
        onRetry = onRetry,
    )
}

/** iOS-style white circular chip with reply arrow (`arrowshape.turn.up.left`). */
@Composable
private fun SwipeReplyAffordance(
    progress: Float,
    pullPx: Float,
    arrowColor: Color,
    modifier: Modifier = Modifier,
) {
    val scale = 0.72f + (0.28f * progress)
    Box(
        modifier = modifier
            .offset { IntOffset((pullPx * 0.12f).roundToInt(), 0) }
            .graphicsLayer {
                alpha = progress
                scaleX = scale
                scaleY = scale
            }
            .size(MessageSwipeToReply.AffordanceSideDp.dp)
            .clip(CircleShape)
            .background(Color.White)
            .semantics { contentDescription = "Reply" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.arrow),
            contentDescription = null,
            tint = arrowColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun MessageReplyQuote(
    senderName: String,
    previewText: String,
    wasIncoming: Boolean,
    attachment: ChatAttachment?,
    containingMessageIsIncoming: Boolean,
    compact: Boolean,
    theme: ChatTheme,
    attachmentResolver: AttachmentResolver,
    modifier: Modifier = Modifier,
) {
    val thumbnailAttachment = attachment?.takeIf { it.isImage || it.isVideo }
    val showsThumbnail = thumbnailAttachment != null
    val previewColor = if (containingMessageIsIncoming) {
        theme.incomingTextColor.copy(alpha = 0.88f)
    } else {
        theme.outgoingTextColor.copy(alpha = 0.92f)
    }
    val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(
                if (containingMessageIsIncoming) {
                    Color.Black.copy(alpha = 0.08f)
                } else {
                    Color.White.copy(alpha = 0.22f)
                },
                shape,
            )
            .clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (compact) 3.5.dp else 5.dp)
                .fillMaxHeight()
                .background(theme.replyQuoteSenderColor(wasIncoming)),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (compact) 7.dp else 10.dp)
                .padding(end = if (showsThumbnail) 0.dp else if (compact) 7.dp else 10.dp)
                .padding(vertical = if (compact) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
            ) {
                Text(
                    text = senderName,
                    color = theme.replyQuoteSenderColor(wasIncoming),
                    fontSize = if (compact) 12.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReplyPreviewLine(
                    text = previewText,
                    attachment = attachment,
                    color = previewColor,
                    fontSize = if (compact) 13.sp else 15.sp,
                    maxLines = if (compact) 1 else 2,
                )
            }
            if (thumbnailAttachment != null) {
                ReplyAttachmentThumbnail(
                    attachment = thumbnailAttachment,
                    attachmentResolver = attachmentResolver,
                    size = if (compact) 32.dp else 56.dp,
                    cornerRadius = if (compact) 4.dp else 6.dp,
                )
            }
        }
    }
}

@Composable
internal fun ReplyPreviewLine(
    text: String,
    attachment: ChatAttachment?,
    color: Color,
    fontSize: TextUnit,
    maxLines: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val icon = when {
            attachment?.isImage == true -> Icons.Default.CameraAlt
            attachment?.isVideo == true -> Icons.Default.Videocam
            attachment?.isAudio == true -> Icons.Default.Mic
            attachment != null -> Icons.Default.Description
            else -> null
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ReplyAttachmentThumbnail(
    attachment: ChatAttachment,
    attachmentResolver: AttachmentResolver,
    size: Dp,
    cornerRadius: Dp,
) {
    val context = LocalContext.current
    val preferVideoFrame = attachment.isVideo
    val uri by produceState<android.net.Uri?>(
        attachment.posterUri ?: attachment.localUri,
        attachment.id,
        attachment.posterUri,
        attachment.localUri,
        attachment.transferState,
        attachmentResolver,
    ) {
        var result: android.net.Uri? = attachment.posterUri ?: attachment.localUri
        var attempt = 0
        while (result == null && attempt < 12) {
            result = withContext(Dispatchers.IO) {
                runCatching { attachmentResolver.resolvePoster(attachment) }.getOrNull()
                    ?: runCatching { attachmentResolver.resolveContent(attachment) }.getOrNull()
            }
            if (result != null) break
            attempt += 1
            delay((750L * attempt).coerceAtMost(5_000L))
        }
        value = result
    }
    val bitmap by produceState<ImageBitmap?>(null, uri, attachment.isVideo, attachment.posterUri) {
        value = uri?.let { resolved ->
            withContext(Dispatchers.IO) {
                val useVideoFrame = preferVideoFrame && resolved != attachment.posterUri
                // Reuse the bubble decode cache (1024px) when the media row already rendered.
                cachedAttachmentPreviewOrNull(resolved, useVideoFrame, maxSide = 1024)
                    ?: cachedAttachmentPreviewOrNull(resolved, useVideoFrame, maxSide = 256)
                    ?: decodeAndCacheAttachmentPreview(
                        context = context,
                        uri = resolved,
                        preferVideo = useVideoFrame,
                        maxSide = 256,
                    )
            }
        }
    }
    val placeholderTint = Color(0xFF5F7186)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.Black.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = if (attachment.isVideo) Icons.Default.Videocam else Icons.Default.CameraAlt,
                contentDescription = null,
                tint = placeholderTint,
                modifier = Modifier.size(18.dp),
            )
        }
        if (attachment.isVideo && bitmap != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun DateSeparator(label: String, theme: ChatTheme) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
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
    val corner = if (theme.bubbleCornerRadius == Dp.Unspecified) 12.dp else theme.bubbleCornerRadius
    val bubbleShape = RoundedCornerShape(
        topStart = corner,
        topEnd = corner,
        bottomStart = 0.dp, // tail — matches incoming message shape
        bottomEnd = corner,
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
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1y"
    )
    val dot2Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 160, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2y"
    )
    val dot3Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3y"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .background(theme.typingIndicatorBubbleColor, bubbleShape)
                .border(1.dp, theme.incomingBubbleBorderColor, bubbleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .offset(y = dot1Offset.dp)
                    .size(6.dp)
                    .background(
                        theme.typingIndicatorTextColor.copy(alpha = dot1Alpha),
                        CircleShape,
                    )
            )
            Box(
                Modifier
                    .offset(y = dot2Offset.dp)
                    .size(6.dp)
                    .background(
                        theme.typingIndicatorTextColor.copy(alpha = dot2Alpha),
                        CircleShape,
                    )
            )
            Box(
                Modifier
                    .offset(y = dot3Offset.dp)
                    .size(6.dp)
                    .background(
                        theme.typingIndicatorTextColor.copy(alpha = dot3Alpha),
                        CircleShape,
                    )
            )
        }
    }
}
