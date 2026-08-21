package com.chatkit.compose

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Feature switches and behavior for [ChatScreen]. */
@Immutable
public data class ChatConfig(
    public val showSenderNames: Boolean = false,
    public val dateLabel: String? = "Today",
    public val composerPlaceholder: String = "Message",
    public val showComposer: Boolean = true,
    public val enableVoiceRecorder: Boolean = true,
    public val enableVideoAttachments: Boolean = true,
    public val enableDocumentAttachments: Boolean = true,
    public val allowMultipleDocuments: Boolean = true,
    public val acceptedDocumentMimeTypes: List<String> = listOf("*/*"),
    public val automaticallyLoadIncomingImages: Boolean = true,
    public val maximumMediaSelection: Int = 10,
    public val cameraCaptureUri: Uri? = null,
    public val messageModificationWindowMillis: Long = 15 * 60 * 1000L,
    public val showDeliveryStatus: Boolean = false,
    public val enableSwipeToReply: Boolean = true,
    /** Number of rows from the oldest edge at which pagination is requested. */
    public val loadPreviousThreshold: Int = 5,
)

/** Colors for every visible ChatKit surface. */
@Immutable
public data class ChatColors(
    public val background: Color,
    public val incomingBubble: Color,
    public val outgoingBubble: Color,
    public val accent: Color,
    public val accentContent: Color,
    public val composerBar: Color,
    public val composerField: Color,
    public val composerFieldBorder: Color,
    public val incomingText: Color,
    public val outgoingText: Color,
    public val incomingTimestamp: Color,
    public val outgoingTimestamp: Color,
    public val dateSeparatorBackground: Color,
    public val dateSeparatorText: Color,
    public val attachmentPanelBackground: Color,
    public val attachmentTileBackground: Color,
    public val thumbnailPlaceholder: Color,
    public val incomingBubbleBorder: Color,
    public val readReceipt: Color,
    public val typingIndicatorBubble: Color,
    public val typingIndicatorContent: Color,
    public val composerButtonBackground: Color = Color(0xFFEFF3F6),
    public val composerIcon: Color = Color(0xFF5F7186),
)

/** Layout dimensions for message bubbles. */
@Immutable
public data class ChatDimensions(
    public val bubbleCornerRadius: Dp = 16.dp,
    public val maximumBubbleWidth: Dp = Dp.Unspecified,
)

/** Defaults which follow Material light/dark colors through [ChatTheme]. */
public object ChatDefaults {
    /** Returns the default ChatKit palette. */
    @Composable
    public fun colors(): ChatColors = ChatTheme().toColors()
}

/**
 * Reusable, state-hoisted conversation UI. The host owns all durable messages and side effects.
 * Messages must be supplied oldest first and use stable IDs.
 */
@Composable
public fun ChatScreen(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    config: ChatConfig = ChatConfig(),
    colors: ChatColors = ChatDefaults.colors(),
    dimensions: ChatDimensions = ChatDimensions(),
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    isTyping: Boolean = false,
    typingIndicatorText: String = "Typing…",
    onSubmit: ((ChatDraft) -> Unit)? = null,
    onSendText: (String) -> Unit = {},
    onMediaPicked: (List<ChatMediaAttachment>) -> Unit = {},
    onDocumentsPicked: (List<Uri>) -> Unit = {},
    onVoiceRecorded: (uri: Uri, durationMillis: Long) -> Unit = { _, _ -> },
    onOptimisticMessage: (ChatMessage) -> Unit = {},
    onCancelAttachmentUpload: (ChatAttachment) -> Unit = {},
    onRetryMessage: (messageId: String) -> Unit = {},
    onEditMessage: ((messageId: String, text: String) -> Unit)? = null,
    onDeleteMessage: ((messageId: String) -> Unit)? = null,
    onLoadPreviousMessages: (() -> Unit)? = null,
    onReplyToMessage: (ChatMessage) -> Unit = {},
) {
    val theme = colors.toTheme(dimensions, config.showDeliveryStatus)
    ChatView(
        messages = messages,
        modifier = modifier,
        showsSender = config.showSenderNames,
        dateLabel = config.dateLabel,
        composerPlaceholder = config.composerPlaceholder,
        theme = theme,
        isTyping = isTyping,
        typingIndicatorText = typingIndicatorText,
        showsComposer = config.showComposer,
        showsVoiceRecorder = config.enableVoiceRecorder,
        showsVideoAttachments = config.enableVideoAttachments,
        showsDocumentAttachments = config.enableDocumentAttachments,
        allowsMultipleDocumentSelection = config.allowMultipleDocuments,
        documentMimeTypes = config.acceptedDocumentMimeTypes,
        automaticallyLoadsImages = config.automaticallyLoadIncomingImages,
        maximumMediaSelection = config.maximumMediaSelection.coerceAtLeast(1),
        cameraCaptureUri = config.cameraCaptureUri,
        attachmentResolver = attachmentResolver,
        onMediaPicked = onMediaPicked,
        onDocumentsPicked = onDocumentsPicked,
        onVoiceRecorded = onVoiceRecorded,
        onOptimisticMessage = onOptimisticMessage,
        onCancelAttachmentUpload = onCancelAttachmentUpload,
        onMessageRetry = onRetryMessage,
        modificationWindowMillis = config.messageModificationWindowMillis,
        onEditMessage = onEditMessage,
        onDeleteMessage = onDeleteMessage,
        onLoadPreviousMessages = onLoadPreviousMessages,
        loadPreviousThreshold = config.loadPreviousThreshold.coerceAtLeast(1),
        swipeToReplyEnabled = config.enableSwipeToReply,
        onReplyToMessage = onReplyToMessage,
        onSubmit = onSubmit,
        onSend = onSendText,
    )
}

private fun ChatTheme.toColors(): ChatColors = ChatColors(
    backgroundColor, incomingBubbleColor, outgoingBubbleColor, accentColor, accentContentColor,
    composerBarColor, composerFieldBackground, composerFieldBorderColor, incomingTextColor,
    outgoingTextColor, incomingTimestampColor, outgoingTimestampColor, dateSeparatorBackground,
    dateSeparatorTextColor, attachmentPanelBackgroundColor, attachmentTileBackgroundColor,
    thumbnailPlaceholderBackgroundColor, incomingBubbleBorderColor, readReceiptColor,
    typingIndicatorBubbleColor, typingIndicatorTextColor, composerButtonBackgroundColor,
    composerIconColor,
)

private fun ChatColors.toTheme(dimensions: ChatDimensions, showDeliveryStatus: Boolean): ChatTheme =
    ChatTheme(
        backgroundColor = background,
        incomingBubbleColor = incomingBubble,
        outgoingBubbleColor = outgoingBubble,
        accentColor = accent,
        accentContentColor = accentContent,
        composerBarColor = composerBar,
        composerFieldBackground = composerField,
        composerFieldBorderColor = composerFieldBorder,
        incomingTextColor = incomingText,
        outgoingTextColor = outgoingText,
        incomingTimestampColor = incomingTimestamp,
        outgoingTimestampColor = outgoingTimestamp,
        dateSeparatorBackground = dateSeparatorBackground,
        dateSeparatorTextColor = dateSeparatorText,
        attachmentPanelBackgroundColor = attachmentPanelBackground,
        attachmentTileBackgroundColor = attachmentTileBackground,
        thumbnailPlaceholderBackgroundColor = thumbnailPlaceholder,
        incomingBubbleBorderColor = incomingBubbleBorder,
        readReceiptColor = readReceipt,
        typingIndicatorBubbleColor = typingIndicatorBubble,
        typingIndicatorTextColor = typingIndicatorContent,
        composerButtonBackgroundColor = composerButtonBackground,
        composerIconColor = composerIcon,
        bubbleCornerRadius = dimensions.bubbleCornerRadius,
        messageMaximumWidth = dimensions.maximumBubbleWidth,
        showsDeliveryStatus = showDeliveryStatus,
    )
