package com.chatkit.compose

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.UUID

/**
 * A state-hoisted conversation surface. The host remains the source of truth for [messages].
 * ChatKit owns only temporary composer, picker, recorder, and optimistic-row state.
 *
 * @param dateLabel Kept for API compatibility with iOS ChatKit; day pills are derived from
 * message timestamps automatically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun ChatView(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    showsSender: Boolean = false,
    @Suppress("UNUSED_PARAMETER") dateLabel: String? = "Today",
    composerPlaceholder: String = "Message",
    theme: ChatTheme = ChatTheme(),
    isTyping: Boolean = false,
    typingIndicatorText: String = "Typing…",
    showsVoiceRecorder: Boolean = true,
    showsVideoAttachments: Boolean = true,
    allowsMultipleDocumentSelection: Boolean = true,
    documentMimeTypes: List<String> = listOf("*/*"),
    automaticallyLoadsImages: Boolean = true,
    maximumMediaSelection: Int = 10,
    cameraCaptureUri: Uri? = null,
    showsComposer: Boolean = true,
    showsDocumentAttachments: Boolean = true,
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    onDocumentSelection: ((List<Uri>) -> Unit)? = null,
    onAttachmentTap: () -> Unit = {},
    onMediaPicked: (List<ChatMediaAttachment>) -> Unit = {},
    onDocumentsPicked: (List<Uri>) -> Unit = {},
    onVoiceRecordTap: () -> Unit = {},
    onVoiceRecorded: (uri: Uri, durationMillis: Long) -> Unit = { _, _ -> },
    onVoiceRecordingCancelled: () -> Unit = {},
    onOptimisticMessage: (ChatMessage) -> Unit = {},
    onCancelAttachmentUpload: (ChatAttachment) -> Unit = {},
    onMessageRetry: (String) -> Unit = {},
    modificationWindowMillis: Long = 15 * 60 * 1000L,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onLoadPreviousMessages: (() -> Unit)? = null,
    loadPreviousThreshold: Int = 5,
    swipeToReplyEnabled: Boolean = true,
    onReplyToMessage: (ChatMessage) -> Unit = {},
    onSubmit: ((ChatDraft) -> Unit)? = null,
    onSend: (String) -> Unit = {},
    attachmentContent: (@Composable (ChatAttachment) -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val composerFocusRequester = remember { FocusRequester() }
    val recorder = remember(context) { VoiceRecorder(context.applicationContext) }
    val audioPlayer = remember(context) { AudioPlayerController(context.applicationContext) }
    val resolvedAttachmentContent: @Composable (ChatAttachment) -> Unit =
        attachmentContent ?: { attachment ->
            DefaultAttachment(
                attachment = attachment,
                theme = theme,
                automaticallyLoadsImages = automaticallyLoadsImages,
                attachmentResolver = attachmentResolver,
                onCancelUpload = onCancelAttachmentUpload,
                audioPlayer = audioPlayer,
            )
        }

    var draft by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var isAttachmentPickerPresented by remember { mutableStateOf(false) }
    val pendingMedia = remember { mutableStateListOf<ChatMediaAttachment>() }
    val pendingDocuments = remember { mutableStateListOf<Uri>() }
    val optimisticMessages = remember { mutableStateListOf<ChatMessage>() }

    var isRecording by remember { mutableStateOf(false) }
    var isVoiceRecordingLocked by remember { mutableStateOf(false) }
    var isVoiceGestureActive by remember { mutableStateOf(false) }
    var isVoiceLockArmed by remember { mutableStateOf(false) }
    var voiceDragOffset by remember { mutableFloatStateOf(0f) }

    var isViewingNewest by remember { mutableStateOf(true) }
    var unreadIncomingCount by remember { mutableIntStateOf(0) }
    var scrollToNewestRequest by remember { mutableIntStateOf(0) }

    // Snapshot host messages so in-place SnapshotStateList mutations invalidate remember.
    val hostMessages = messages.toList()
    val pendingOptimistic = optimisticMessages.toList()
    val displayedMessages = remember(hostMessages, pendingOptimistic) {
        reconcileMessages(hostMessages, pendingOptimistic)
    }

    LaunchedEffect(hostMessages.map { it.id }) {
        val claimed = hostMessages.map { it.id }.toSet()
        optimisticMessages.removeAll { it.id in claimed }
        if (replyingTo != null && displayedMessages.none { it.id == replyingTo?.id }) {
            replyingTo = null
        }
    }

    LaunchedEffect(replyingTo?.id, showsComposer) {
        if (replyingTo != null && showsComposer) {
            composerFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val mimeArray = remember(documentMimeTypes) { documentMimeTypes.toTypedArray() }
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = maximumMediaSelection),
    ) { uris ->
        pendingDocuments.clear()
        pendingMedia.clear()
        pendingMedia += uris.take(maximumMediaSelection).map { uri ->
            val type = context.contentResolver.getType(uri)
            ChatMediaAttachment(
                id = uri.toString(),
                mediaType = if (type?.startsWith("video/") == true) {
                    MediaType.Video
                } else {
                    MediaType.Photo
                },
                localUri = uri,
            )
        }
    }
    val cameraPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        if (captured && cameraCaptureUri != null) {
            pendingDocuments.clear()
            pendingMedia.clear()
            pendingMedia += ChatMediaAttachment(
                id = cameraCaptureUri.toString(),
                mediaType = MediaType.Photo,
                localUri = cameraCaptureUri,
            )
        }
    }
    val multiDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        handleDocumentPick(uris, pendingMedia, pendingDocuments, onDocumentSelection)
    }
    val singleDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        handleDocumentPick(
            listOfNotNull(uri),
            pendingMedia,
            pendingDocuments,
            onDocumentSelection,
        )
    }

    fun launchDocumentPicker() {
        isAttachmentPickerPresented = false
        if (allowsMultipleDocumentSelection) {
            multiDocumentPicker.launch(mimeArray)
        } else {
            singleDocumentPicker.launch(mimeArray)
        }
    }

    fun presentAttachmentPicker() {
        onAttachmentTap()
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        pendingMedia.clear()
        pendingDocuments.clear()
        isAttachmentPickerPresented = true
    }

    fun dismissAttachmentPicker() {
        if (!isAttachmentPickerPresented && pendingMedia.isEmpty() && pendingDocuments.isEmpty()) return
        pendingMedia.clear()
        pendingDocuments.clear()
        isAttachmentPickerPresented = false
    }

    fun dismissInputPanels() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        dismissAttachmentPicker()
    }

    fun startRecording(): Boolean {
        onVoiceRecordTap()
        dismissAttachmentPicker()
        val started = recorder.start()
        isRecording = started
        return started
    }

    val audioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && startRecording()) {
            isVoiceRecordingLocked = true
            isVoiceGestureActive = true
        }
    }

    fun ensureRecordingPermissionAndStart(): Boolean {
        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }

    fun deliverVoiceRecording(recording: VoiceRecorder.Recording) {
        val id = UUID.randomUUID().toString()
        val optimistic = ChatMessage(
            id = id,
            text = "",
            timestamp = Instant.now(),
            direction = MessageDirection.Outgoing,
            deliveryStatus = DeliveryStatus.None,
            replyToMessageId = replyingTo?.id,
            replyToMessageText = replyingTo?.text,
            replyToSenderName = replyingTo?.senderName,
            attachments = listOf(
                ChatAttachment(
                    id = id,
                    fileName = "voice-note.m4a",
                    mimeType = "audio/mp4",
                    durationMillis = recording.durationMillis,
                    localUri = recording.uri,
                    transferState = TransferState.Uploading(0f),
                ),
            ),
        )
        optimisticMessages += optimistic
        onOptimisticMessage(optimistic)
        onVoiceRecorded(recording.uri, recording.durationMillis)
        replyingTo = null
    }

    fun finishVoiceRecording() {
        recorder.finish()?.let(::deliverVoiceRecording) ?: recorder.cancel()
        isRecording = false
        isVoiceRecordingLocked = false
        isVoiceGestureActive = false
        isVoiceLockArmed = false
        voiceDragOffset = 0f
    }

    fun cancelVoiceRecording() {
        recorder.cancel()
        isRecording = false
        isVoiceRecordingLocked = false
        isVoiceGestureActive = false
        isVoiceLockArmed = false
        voiceDragOffset = 0f
        onVoiceRecordingCancelled()
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty() && pendingMedia.isEmpty() && pendingDocuments.isEmpty()) return

        if (pendingMedia.isNotEmpty() || pendingDocuments.isNotEmpty()) {
            val optimistic = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = if (onSubmit == null) "" else text,
                timestamp = Instant.now(),
                direction = MessageDirection.Outgoing,
                deliveryStatus = DeliveryStatus.None,
                replyToMessageId = replyingTo?.id,
                replyToMessageText = replyingTo?.text,
                replyToSenderName = replyingTo?.senderName,
                attachments = pendingMedia.map { media ->
                    val isVideo = media.mediaType == MediaType.Video
                    ChatAttachment(
                        id = media.id,
                        fileName = if (isVideo) "video.mp4" else "photo.jpg",
                        mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                        durationMillis = media.durationMillis,
                        localUri = media.localUri,
                        transferState = TransferState.Uploading(0f),
                    )
                } + pendingDocuments.mapIndexed { index, uri ->
                    ChatAttachment(
                        id = "${uri}#$index",
                        fileName = uri.lastPathSegment ?: "document-${index + 1}",
                        mimeType = context.contentResolver.getType(uri),
                        localUri = uri,
                        transferState = TransferState.Uploading(0f),
                    )
                },
            )
            optimisticMessages += optimistic
            onOptimisticMessage(optimistic)
        }

        val submission = buildDraft(
            text,
            pendingMedia.toList(),
            pendingDocuments.toList(),
            replyingTo?.id,
        )
        if (onSubmit != null) {
            onSubmit(submission)
        } else {
            if (submission.media.isNotEmpty()) onMediaPicked(submission.media)
            if (submission.documents.isNotEmpty()) onDocumentsPicked(submission.documents)
            if (submission.text.isNotEmpty()) onSend(submission.text)
        }
        draft = ""
        replyingTo = null
        dismissAttachmentPicker()
    }

    val canSend = draft.isNotBlank() || pendingMedia.isNotEmpty() || pendingDocuments.isNotEmpty()
    val isVoiceRecorderActive = isRecording || isVoiceGestureActive
    val voiceDuration = rememberVoiceDurationMillis(isRecording, recorder)

    DisposableEffect(recorder) {
        onDispose {
            recorder.cancel()
            audioPlayer.release()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.backgroundColor)
            // Keep the composer attached to the animated IME edge. imeNestedScroll
            // also lets a transcript drag move/dismiss the keyboard on Android 11+.
            .navigationBarsPadding()
            .imePadding()
            .imeNestedScroll(),
    ) {
        MessageList(
            messages = displayedMessages,
            listState = listState,
            showsSender = showsSender,
            theme = theme,
            isTyping = isTyping,
            typingIndicatorText = typingIndicatorText,
            isViewingNewest = isViewingNewest,
            unreadIncomingCount = unreadIncomingCount,
            onTranscriptTap = ::dismissInputPanels,
            onMessageRetry = onMessageRetry,
            modificationWindowMillis = modificationWindowMillis,
            onEditMessage = onEditMessage,
            onDeleteMessage = onDeleteMessage,
            onReplyMessage = if (swipeToReplyEnabled && showsComposer) {
                { message ->
                    replyingTo = message
                    onReplyToMessage(message)
                }
            } else {
                null
            },
            onLoadPreviousMessages = onLoadPreviousMessages,
            loadPreviousThreshold = loadPreviousThreshold,
            onUnreadIncomingCountChanged = { unreadIncomingCount = it },
            onNewestVisibilityChanged = { isViewingNewest = it },
            scrollToNewestRequest = scrollToNewestRequest,
            onJumpToNewest = { scrollToNewestRequest += 1 },
            attachmentContent = resolvedAttachmentContent,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        if (showsComposer) Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.composerBarColor),
        ) {
            HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))
            replyingTo?.let { message ->
                ReplyComposerPreview(
                    message = message,
                    theme = theme,
                    onCancel = { replyingTo = null },
                )
            }
            if ((pendingMedia.isNotEmpty() || pendingDocuments.isNotEmpty()) && !isAttachmentPickerPresented) {
                PendingAttachments(
                    media = pendingMedia,
                    documents = pendingDocuments,
                    theme = theme,
                    onClear = {
                        pendingMedia.clear()
                        pendingDocuments.clear()
                    },
                )
            }
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ComposerButton("+", enabled = !isVoiceRecorderActive, theme = theme) {
                        if (isAttachmentPickerPresented) {
                            dismissAttachmentPicker()
                        } else {
                            presentAttachmentPicker()
                        }
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(composerFocusRequester)
                            .border(0.5.dp, theme.composerFieldBorderColor, RoundedCornerShape(18.dp))
                            .background(theme.composerFieldBackground, RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused && isAttachmentPickerPresented) {
                                    isAttachmentPickerPresented = false
                                }
                            },
                        enabled = !isVoiceRecorderActive,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.incomingTextColor),
                        maxLines = 5,
                        decorationBox = { field ->
                            Box {
                                if (draft.isEmpty()) {
                                    Text(composerPlaceholder, color = theme.incomingTimestampColor)
                                }
                                field()
                            }
                        },
                    )
                    if (canSend || !showsVoiceRecorder) {
                        ComposerButton("➤", enabled = canSend, theme = theme, filled = true, onClick = ::submit)
                    } else {
                        VoiceMicButton(
                            theme = theme,
                            enabled = true,
                            isActive = isVoiceRecorderActive,
                            onGestureActiveChanged = { isVoiceGestureActive = it },
                            onLockArmedChanged = { isVoiceLockArmed = it },
                            onDragOffsetChanged = { voiceDragOffset = it },
                            onPressStart = ::ensureRecordingPermissionAndStart,
                            onCancel = ::cancelVoiceRecording,
                            onLock = { isVoiceRecordingLocked = true },
                            onFinish = ::finishVoiceRecording,
                        )
                    }
                }
                if (isVoiceRecorderActive) {
                    VoiceRecordingOverlay(
                        theme = theme,
                        durationMillis = voiceDuration,
                        isLocked = isVoiceRecordingLocked,
                        isLockArmed = isVoiceLockArmed,
                        dragOffsetX = voiceDragOffset,
                        onDiscard = ::cancelVoiceRecording,
                        onSend = ::finishVoiceRecording,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isAttachmentPickerPresented,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            AttachmentPanel(
                theme = theme,
                showsVideoAttachments = showsVideoAttachments,
                showsDocumentAttachments = showsDocumentAttachments,
                showsCamera = cameraCaptureUri != null,
                maximumMediaSelection = maximumMediaSelection,
                documentSelectionCount = pendingDocuments.size,
                selectedMedia = pendingMedia.toList(),
                onClose = ::dismissAttachmentPicker,
                onMediaSelectionChanged = { attachments ->
                    pendingMedia.clear()
                    pendingMedia += attachments
                    if (attachments.isNotEmpty()) pendingDocuments.clear()
                },
                onDocumentPickerRequested = ::launchDocumentPicker,
                onCameraRequested = {
                    isAttachmentPickerPresented = false
                    cameraCaptureUri?.let(cameraPicker::launch)
                },
                onFallbackPickMedia = {
                    isAttachmentPickerPresented = false
                    mediaPicker.launch(
                        PickVisualMediaRequest(
                            if (showsVideoAttachments) {
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            } else {
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun ReplyComposerPreview(
    message: ChatMessage,
    theme: ChatTheme,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.composerFieldBackground)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 38.dp)
                .background(theme.accentColor, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = message.senderName?.takeIf { it.isNotBlank() }
                    ?: if (message.isIncoming) "Replying" else "You",
                color = theme.accentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
            Text(
                text = message.text.ifBlank { "Attachment" },
                color = theme.incomingTimestampColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "✕",
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onCancel)
                .padding(8.dp)
                .semantics { contentDescription = "Cancel reply" },
            color = theme.incomingTimestampColor,
        )
    }
}

private fun handleDocumentPick(
    uris: List<Uri>,
    pendingMedia: MutableList<ChatMediaAttachment>,
    pendingDocuments: MutableList<Uri>,
    onDocumentSelection: ((List<Uri>) -> Unit)?,
) {
    pendingMedia.clear()
    if (onDocumentSelection != null) {
        pendingDocuments.clear()
        onDocumentSelection(uris)
    } else {
        pendingDocuments.clear()
        pendingDocuments += uris
    }
}

@Composable
internal fun ComposerButton(
    label: String,
    theme: ChatTheme,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    when {
                        filled && enabled -> theme.accentColor
                        filled && !enabled -> Color(0xFFC7C7CC)
                        else -> theme.accentColor.copy(alpha = 0.14f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (filled) theme.accentContentColor else theme.accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
internal fun PendingAttachments(
    media: List<ChatMediaAttachment>,
    documents: List<Uri>,
    theme: ChatTheme,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.composerBarColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = when {
            media.isNotEmpty() -> "${media.size} media selected"
            documents.size == 1 -> documents.first().lastPathSegment ?: "Document selected"
            else -> "${documents.size} documents selected"
        }
        Text(label, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "×",
            color = theme.incomingTimestampColor,
            fontSize = 22.sp,
            modifier = Modifier.clickable(onClick = onClear).padding(6.dp),
        )
    }
}
