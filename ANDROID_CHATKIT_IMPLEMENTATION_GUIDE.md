# Android ChatKit Implementation Guide

This document specifies an Android library that matches the behavior and ownership model of the iOS `ChatKit` package in this repository.

The Android library should be a reusable Jetpack Compose conversation UI, not a messaging backend. The consuming application remains the source of truth for messages and owns authentication, networking, persistence, uploads, downloads, delivery receipts, pagination, notifications, and retry policy.

## 1. Product goal

Create an Android library named `chatkit` with these capabilities:

- Incoming and outgoing message bubbles
- Chronological message input with newest messages displayed at the bottom
- Multiline message composer
- Photo, video, camera, and document selection
- Voice recording with hold, slide-to-cancel, and slide-up-to-lock behavior
- Image, video, document, and voice-message bubbles
- Optimistic attachment and voice messages with upload progress
- Sent, delivered, read, and failed delivery states
- Typing indicator
- Unread-message counter while the user is reading history
- Message retry, edit, and delete actions
- Configurable colors, dimensions, feature switches, and content descriptions
- Predictable keyboard and scrolling behavior
- No required backend, database, analytics service, or dependency-injection framework

Recommended Android baseline:

- Kotlin
- Jetpack Compose and Material 3
- Coroutines
- `minSdk` chosen by the product team; use AndroidX compatibility APIs where possible
- Gradle Kotlin DSL and a version catalog

## 2. Core ownership rule

Use unidirectional data flow.

```text
Host ViewModel / message store
        |
        | List<ChatMessage>
        v
    ChatScreen
        |
        +-- MessageList
        +-- MessageBubble
        +-- Composer
        +-- AttachmentPicker
        +-- VoiceRecorderController
        |
        | callbacks and draft events
        v
Host repository, uploads, database, WebSocket, and API
```

`ChatScreen` receives an immutable message list. It must never treat its internal copy as durable application data. When the user sends or changes something, the library emits an event. The host performs the operation and supplies the updated message list on the next recomposition.

The library may temporarily keep presentation state such as:

- Composer text
- Selected media and documents
- Open attachment panel
- Active voice recording
- Scroll position
- Temporary optimistic rows, until the host echoes the same message ID

## 3. Suggested project structure

```text
android-chatkit/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── chatkit/
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/com/example/chatkit/
│       │       ├── model/
│       │       │   ├── ChatMessage.kt
│       │       │   ├── ChatAttachment.kt
│       │       │   ├── ChatDraft.kt
│       │       │   └── ChatMediaAttachment.kt
│       │       ├── ui/
│       │       │   ├── ChatScreen.kt
│       │       │   ├── MessageList.kt
│       │       │   ├── MessageBubble.kt
│       │       │   ├── MessageComposer.kt
│       │       │   ├── AttachmentPanel.kt
│       │       │   ├── TypingIndicator.kt
│       │       │   └── ChatTheme.kt
│       │       ├── media/
│       │       │   ├── AttachmentResolver.kt
│       │       │   ├── AudioPlayerController.kt
│       │       │   └── VideoPlayer.kt
│       │       └── recording/
│       │           └── VoiceRecorderController.kt
│       ├── test/
│       └── androidTest/
└── sample/
    └── src/main/kotlin/.../SampleConversationScreen.kt
```

Keep the models and primary composables public. Keep internal rendering helpers `internal` unless consumers genuinely need them.

## 4. Public data models

Use stable string IDs so host-generated, database, and server IDs all work.

```kotlin
package com.example.chatkit.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.time.Instant

@Immutable
data class ChatMessage(
    val id: String,
    val text: String,
    val timestamp: Instant,
    val direction: MessageDirection,
    val senderName: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val deliveryStatus: DeliveryStatus = DeliveryStatus.Sent,
    val isEdited: Boolean = false,
)

enum class MessageDirection { Incoming, Outgoing }

enum class DeliveryStatus {
    None,
    Pending,
    Failed,
    Sent,
    Delivered,
    Read,
}

@Immutable
data class ChatAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val durationMillis: Long? = null,
    val localUri: Uri? = null,
    val posterUri: Uri? = null,
    val transferState: TransferState = TransferState.Uploaded,
)

sealed interface TransferState {
    data class Uploading(val progress: Float) : TransferState
    data object Uploaded : TransferState
    data object Failed : TransferState
}

@Immutable
data class ChatMediaAttachment(
    val id: String,
    val mediaType: MediaType,
    val durationMillis: Long? = null,
    val localUri: Uri,
)

enum class MediaType { Photo, Video }

@Immutable
data class ChatDraft(
    val text: String,
    val media: List<ChatMediaAttachment> = emptyList(),
    val documents: List<Uri> = emptyList(),
)
```

Rules:

- IDs must remain stable across recompositions and database refreshes.
- Treat `Pending` like `Sent` visually so an outgoing message immediately shows one tick.
- Only `Failed` shows an error/retry state.
- Use MIME type first and the file extension as a fallback when deciding attachment type.
- Clamp all transfer progress values to `0f..1f`.
- Prefer an explicit `isEdited` property on Android instead of changing the message text.

## 5. Public Compose API

The main API should be stateless with respect to durable data:

```kotlin
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    config: ChatConfig = ChatConfig(),
    colors: ChatColors = ChatDefaults.colors(),
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
)
```

Configuration should group feature switches and behavior rather than continually expanding the composable signature:

```kotlin
@Immutable
data class ChatConfig(
    val showSenderNames: Boolean = false,
    val dateLabel: String? = "Today",
    val composerPlaceholder: String = "Message",
    val showComposer: Boolean = true,
    val enableVoiceRecorder: Boolean = true,
    val enableVideoAttachments: Boolean = true,
    val enableDocumentAttachments: Boolean = true,
    val allowMultipleDocuments: Boolean = true,
    val acceptedDocumentMimeTypes: List<String> = listOf("*/*"),
    val automaticallyLoadIncomingImages: Boolean = true,
    val maximumMediaSelection: Int = 10,
    val messageModificationWindowMillis: Long = 15 * 60 * 1000L,
    val showDeliveryStatus: Boolean = false,
)
```

Use one atomic submission path when `onSubmit` is non-null. In that case call `onSubmit(ChatDraft)` exactly once and do not also call `onSendText`, `onMediaPicked`, or `onDocumentsPicked`. The individual callbacks are the legacy/simple integration path.

## 6. Attachment resolution

Do not store tokens, authenticated URLs, Retrofit services, or storage SDKs in the UI library. Let the host resolve an attachment.

```kotlin
interface AttachmentResolver {
    suspend fun resolveContent(
        attachment: ChatAttachment,
        onProgress: (Float) -> Unit = {},
    ): Uri?

    suspend fun resolvePoster(attachment: ChatAttachment): Uri? = null

    suspend fun isAvailableLocally(attachment: ChatAttachment): Boolean = false

    data object None : AttachmentResolver {
        override suspend fun resolveContent(
            attachment: ChatAttachment,
            onProgress: (Float) -> Unit,
        ): Uri? = attachment.localUri
    }
}
```

Resolver calls must run from lifecycle-aware coroutines. Cancel work when a row leaves composition unless a shared host cache owns the download. Never start duplicate downloads for every recomposition; key work by attachment ID.

For image loading, either expose an optional adapter interface or use a small, clearly documented image-loading dependency. The networking and authorization policy still belongs to the host.

## 7. How the UI functions

### Message list

- The host supplies messages in chronological order, oldest first.
- Render with `LazyColumn(reverseLayout = true)` and reverse the indexed presentation, or use a normal list and explicitly maintain bottom anchoring. Pick one approach and test it thoroughly.
- Keep the newest message at the visual bottom.
- When a message is appended and the user is already at the bottom, animate to the newest item.
- When the user is reading history, do not force-scroll. Count newly arrived incoming messages and show a floating down button with the unread count.
- Opening or closing the software keyboard must not jump the user away from the history they are reading.
- Use stable item keys: `key = { message.id }`.
- The composer remains above `WindowInsets.ime` and navigation bars.

### Message bubble

- Incoming messages align left; outgoing messages align right.
- Default maximum width is approximately 78% of the available transcript width, with a sensible minimum.
- Show the sender name only for incoming messages when group-chat labels are enabled.
- Show timestamp and optional edited label.
- Outgoing receipt states are one tick for sent/pending, two ticks for delivered, colored double ticks for read, and an error icon for failed.
- Tapping a failed outgoing message or its error icon calls `onRetryMessage`.
- A long press opens a message action surface. Show Edit only for outgoing text messages inside the modification window. Show Delete only when the host supplies `onDeleteMessage`.

### Composer

- Support multiline text with a bounded maximum height.
- Trim leading/trailing whitespace when submitting, but do not mutate text while the user types.
- Disable send when text and selected attachments are all empty.
- Show the microphone action when there is no sendable draft and voice recording is enabled.
- Tapping the transcript dismisses the keyboard and attachment panel.
- Keep selected media visible if the user opens the keyboard to add a caption.

### Attachment picker

- Use Android Photo Picker contracts when available.
- Use `ActivityResultContracts.OpenDocument` or `OpenMultipleDocuments` for files.
- Call `takePersistableUriPermission` when long-term access is required and the provider grants it.
- Do not read full-resolution media on the main thread.
- Return lightweight `Uri`/metadata descriptors to the host.
- Limit media selection to `maximumMediaSelection`.
- Camera capture should use an app-owned content `Uri`, normally through `FileProvider`; do not expose raw file paths.
- The consuming app decides whether to compress, transcode, validate, or upload the content.

### Voice recording

- Request `RECORD_AUDIO` at runtime before recording.
- Record into an app cache file using `MediaRecorder` or another documented Android media API.
- Hold starts recording.
- Horizontal drag arms cancellation; releasing while armed deletes the temporary file and emits cancellation.
- Vertical drag locks recording; after locking, provide explicit delete and finish buttons.
- On success, return the temporary content `Uri` and duration to the host.
- Stop and release all recorder resources for success, cancel, composable disposal, process interruption, and error paths.
- Do not assume cache files are durable. The host must move or upload them.

### Optimistic messages

For media, documents, and voice recordings:

1. Generate one stable client message ID.
2. Create a local attachment with `Uploading(0f)`.
3. Immediately render the optimistic outgoing bubble.
4. Call `onOptimisticMessage` so the host can persist and upload it.
5. The host returns the same message ID through `messages` with progress or final server metadata.
6. Remove the library-owned optimistic copy as soon as the host supplies that ID.

Never show both the local optimistic row and the host row.

## 8. Theming

Expose immutable colors and dimensions. Use Material defaults but allow the host to override every visible chat surface.

```kotlin
@Immutable
data class ChatColors(
    val background: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val accent: Color,
    val accentContent: Color,
    val composerBar: Color,
    val composerField: Color,
    val composerFieldBorder: Color,
    val incomingText: Color,
    val outgoingText: Color,
    val incomingTimestamp: Color,
    val outgoingTimestamp: Color,
    val dateSeparatorBackground: Color,
    val dateSeparatorText: Color,
    val attachmentPanelBackground: Color,
    val attachmentTileBackground: Color,
    val thumbnailPlaceholder: Color,
    val incomingBubbleBorder: Color,
    val readReceipt: Color,
    val typingIndicatorBubble: Color,
    val typingIndicatorContent: Color,
)

@Immutable
data class ChatDimensions(
    val bubbleCornerRadius: Dp = 14.dp,
    val maximumBubbleWidth: Dp = Dp.Unspecified,
)
```

Support light/dark themes and edge-to-edge layouts. Do not hardcode white text or backgrounds inside leaf composables.

## 9. Android permissions and manifest responsibilities

The library manifest may declare capabilities it implements, but the sample app and README must explain what the consuming app needs.

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
```

Only request a permission when its feature is invoked. Modern system photo and document pickers generally avoid broad storage permissions; do not add legacy storage permissions unless the supported OS range and implementation truly require them.

If camera capture is included, document the required `FileProvider`, paths XML, and URI grant flags. Avoid manifest components that force one application ID or authority on every consumer; use manifest placeholders or let the host provide the capture URI.

## 10. Media rendering

- Image: thumbnail with placeholder, loading state, failure state, and optional tap-to-retry when automatic loading is disabled.
- Video: poster image, centered play icon, and duration badge. Open a host-overridable full-screen player or emit an attachment-open event.
- Audio: play/pause, elapsed progress, waveform or progress bar, and duration. Only one voice message should play at a time.
- Document: type icon, file name, optional size if supplied, and download/progress state.
- Uploading: progress ring and cancel action.
- Failed attachment: failure state and host-owned retry behavior.

Playback objects must follow lifecycle state, release resources, and avoid one player instance per inactive row.

## 11. Accessibility and localization

- Put all user-facing strings in Android resources.
- Provide content descriptions for send, microphone, attach, camera, play, pause, retry, cancel upload, unread count, receipts, and message actions.
- Announce recording start, lock, cancel, failure, and completion.
- Do not rely on color alone for transfer or receipt state.
- Support font scaling, TalkBack traversal, RTL layouts, 12/24-hour time formatting, and locale-aware dates.
- Ensure touch targets satisfy Material accessibility guidance.

## 12. Error handling

The UI must fail safely when:

- Permission is denied or permanently denied
- A selected URI can no longer be read
- Recorder initialization or finalization fails
- A download returns null or throws
- A video poster cannot be generated
- Upload progress is invalid
- The host removes an optimistic message
- The activity is recreated during selection or recording

Expose recoverable events to the host where product-specific UI is required. Do not swallow exceptions silently, and do not crash the conversation because one attachment cannot render.

## 13. Testing requirements

### Unit tests

- Direction and bubble alignment rules
- MIME/extension attachment classification
- `Pending` displays as `Sent`
- Progress clamping
- Draft trimming and atomic submission behavior
- Optimistic-message de-duplication by stable ID
- Message edit eligibility and time window
- Duration formatting

### Compose UI tests

- Incoming/outgoing bubble placement
- Send-button and microphone state changes
- Text-only, media-only, and caption-plus-media submission
- `onSubmit` prevents duplicate legacy callbacks
- Typing indicator visibility
- Delivery receipt rendering
- Failed-message retry callback
- Long-press edit/delete menu rules
- Unread counter while scrolled away from newest
- Large font, RTL, and TalkBack semantics

### Instrumentation/manual tests

- Keyboard opening while at bottom and while reading history
- Activity recreation
- Photo/video/document picker result handling
- Security-scoped/persisted URI access
- Camera capture through `FileProvider`
- Voice start, finish, cancel, lock, permission denial, and interruption
- Audio/video lifecycle and resource cleanup
- Large conversations and attachment-heavy scrolling performance

## 14. Sample host integration

```kotlin
@Composable
fun ConversationRoute(viewModel: ConversationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ChatScreen(
        messages = state.messages,
        isTyping = state.otherParticipantIsTyping,
        config = ChatConfig(
            showSenderNames = state.isGroupConversation,
            showDeliveryStatus = true,
        ),
        attachmentResolver = viewModel.attachmentResolver,
        onSubmit = viewModel::submitDraft,
        onOptimisticMessage = viewModel::acceptOptimisticMessage,
        onCancelAttachmentUpload = viewModel::cancelUpload,
        onRetryMessage = viewModel::retryMessage,
        onEditMessage = viewModel::editMessage,
        onDeleteMessage = viewModel::deleteMessage,
        onVoiceRecorded = viewModel::uploadVoiceMessage,
    )
}
```

The ViewModel should immediately store outgoing messages locally, enqueue network work, update transfer/delivery states in the database, and expose the database stream back to the UI.

## 15. Implementation order

1. Create the Gradle library and sample app.
2. Add immutable public models, configuration, theme tokens, and unit tests.
3. Build the text-only message list, bubbles, composer, receipts, and keyboard behavior.
4. Add host callbacks and atomic `ChatDraft` submission.
5. Add photo/video/document selection and optimistic upload rows.
6. Add attachment resolution and media/document rendering.
7. Add voice recording and audio playback.
8. Add typing, unread counter, retry, edit, and delete actions.
9. Complete accessibility, localization, lifecycle, performance, and recreation tests.
10. Add Dokka API documentation, a sample application, publishing configuration, and a migration/versioning policy.

Each phase should compile and have tests before starting the next phase.

## 16. Definition of done

The Android library is complete when:

- A host can render a conversation using only `List<ChatMessage>` and callbacks.
- The library contains no backend or application-specific credentials.
- Text, media, documents, and voice drafts work without duplicate callbacks.
- Optimistic rows reconcile correctly with host messages.
- Keyboard behavior and history anchoring work on phones and tablets.
- Permissions, URI access, recording, and playback follow Android lifecycle rules.
- All public APIs have KDoc.
- Unit, Compose UI, and key instrumentation tests pass.
- The sample app demonstrates local fake data and an asynchronous fake upload pipeline.
- A release AAR can be consumed from Maven Local before remote publishing.

## 17. Copyable build instruction

Use the following as a task prompt for an Android developer or coding agent:

> Build a production-quality Android Jetpack Compose library named `chatkit` that mirrors the behavior and unidirectional data-flow contract documented in `ANDROID_CHATKIT_IMPLEMENTATION_GUIDE.md`. The host application must own messages, networking, persistence, uploads, and delivery state. Implement the work in the documented phases. Keep public models immutable, use stable IDs, make `ChatScreen` receive messages and emit callbacks, support atomic `ChatDraft` submission, reconcile optimistic messages by ID, and correctly handle keyboard anchoring, URI permissions, media lifecycle, voice recording, accessibility, localization, and tests. Include a sample app and do not introduce a backend or application-specific dependency.

