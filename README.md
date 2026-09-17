# ChatKit for Android

ChatKit is a reusable Jetpack Compose conversation UI. It contains no backend, credentials,
database, analytics, or dependency-injection framework. The host owns messages, authentication,
networking, persistence, uploads, delivery state, pagination, and retry policy.

## Capabilities

- Incoming/outgoing text and attachment bubbles, date separators, receipts, typing, and unread count
- Multiline composer with atomic `ChatDraft` submission
- Android Photo Picker, document picker, and full-screen CameraX capture with review, photo crop/rotate, caption, and video trim
- Optimistic media, document, and voice rows reconciled by stable message ID
- Hold/slide-to-cancel/slide-up-to-lock voice recording and conversation-scoped audio playback
- Retry, edit, delete, upload cancellation, host attachment resolution, and configurable theming
- Swipe-to-reply with composer quote preview and reply metadata on submitted drafts
- Edge-triggered older-message pagination with duplicate-request protection and portrait-aware image bubbles
- IME/navigation-bar insets, stable lazy-list keys, RTL-compatible layout, and TalkBack semantics

## Install from GitHub

Published GitHub tags can be consumed as an AAR through JitPack—no clone or source-module setup is
required. Add JitPack at the end of the repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.Lalchhanchhuaha")
            }
        }
    }
}
```

Then add the library module dependency:

```kotlin
dependencies {
    implementation("com.github.Lalchhanchhuaha:chatkit-android:v1.6.19")
}
```

For development snapshots, replace `v1.6.19` with `main-SNAPSHOT`. Tagged versions are recommended
for production because they are immutable after JitPack builds them.

## Add the source module

```kotlin
dependencies {
    implementation(project(":chatkit"))
}
```

Messages are immutable and must be supplied oldest to newest:

```kotlin
ChatScreen(
    conversationId = state.channelId,
    messages = state.messages,
    config = ChatConfig(
        showSenderNames = state.isGroup,
        showDeliveryStatus = true,
    ),
    attachmentResolver = viewModel.attachmentResolver,
    isTyping = state.isTyping,
    onSubmit = viewModel::submitDraft,
    onOptimisticMessage = viewModel::acceptOptimisticMessage,
    onCancelAttachmentUpload = viewModel::cancelUpload,
    onRetryMessage = viewModel::retry,
    onEditMessage = viewModel::edit,
    onDeleteMessage = viewModel::delete,
)
```

For a Stream-style bound screen, keep loading, connection, error, typing, pagination, and message
state in your ViewModel and handle a single action stream:

```kotlin
ChatScreen(
    state = uiState,
    onAction = viewModel::onChatAction,
    config = ChatConfig(showDeliveryStatus = true),
)
```

`ChatUiState` covers initial loading, empty and recoverable error UI, connection state, typing, and
older-message pagination. `ChatAction` covers submit, optimistic rows, upload cancellation, retry,
edit, delete, voice recordings, error recovery, and pagination. Your ViewModel translates these
actions into repository/API calls and publishes the next immutable state.

Set `conversationId` (or `ChatUiState.conversationId`) whenever the same screen can navigate between
chats. ChatKit uses it to isolate the lazy-list position, draft, selection, reply, pending attachments,
optimistic rows, unread count, and pagination requests. New incoming messages auto-scroll only while
the user is near the newest edge; outgoing messages follow to the bottom, and history reading is
preserved with a jump-to-newest control.

When `onSubmit` is supplied, it is called exactly once. The legacy callbacks (`onSendText`,
`onMediaPicked`, and `onDocumentsPicked`) are not called for that submission.

For an attachment draft, persist the message delivered to `onOptimisticMessage` immediately and
echo the same message ID in `messages`; this removes ChatKit's temporary copy without duplication.

## Permissions and URIs

The library declares `RECORD_AUDIO` and `CAMERA`, and requests them when recording or opening
the in-app camera. Photo and document selection use system contracts and require no broad storage
permission.

The composer camera button appears when the device has a camera (`ChatConfig.enableCameraCapture`,
default `true`). Capture uses CameraX with an inline photo/video review screen; results are written
to the app cache as `chat-camera-<uuid>.jpg` / `.mp4` and returned on `ChatMediaAttachment.localFile`.
Hosts should load bytes from that file for upload—never treat the UUID as a MediaStore id.
`ChatConfig.cameraCaptureUri` is deprecated and ignored.

Voice recordings are temporary cache files. Move or upload them from `onVoiceRecorded`; do not
treat their URI as durable storage.

For edge-to-edge hosts, use `android:windowSoftInputMode="adjustResize"`.

## Build and publish locally

```shell
./gradlew :chatkit:testDebugUnitTest :chatkit:assembleRelease
./gradlew :chatkit:publishReleasePublicationToMavenLocal
```

Local Maven coordinates: `com.chatkit:chatkit:1.6.19`. Minimum Android version: API 24; `java.time` is
supported through core-library desugaring.

## Releases

| Version | Notes |
|---------|--------|
| 1.6.19 | WhatsApp-style unread jump chip (bottom-end count + arrow); show only when new incoming arrives |
| 1.6.18 | Custom camera/composer icons; redesigned crop aspect menu; review caption keyboard dismiss |
| 1.6.17 | WhatsApp-style swipe-to-reply affordance with resistance/haptics; `replyQuoteSelfSender` theme color |
| 1.6.16 | Host `aspectRatio` reserves media tile size; sync poster decode; selection tap fix |
| 1.6.15 | Fix Compose 1.10 `matchParentSize` build; camera permission/rebind polish; selection allows reply/delete on media |
| 1.6.14 | Skip row animation on initial chat load; selection overlay owns row taps; fix crop-handle drag deltas |
| 1.6.13 | `conversationId` isolates scroll/composer state; smarter auto-scroll and jump-to-newest; crop handle gesture exclusion |
| 1.6.12 | Selection toolbar reply action; jump to newest without animation on chat switch |
| 1.6.11 | WhatsApp-style transcript spacing: tighter same-direction rows, larger gaps on direction changes |
| 1.6.10 | Shared 4:3 photo/video capture crop; post-capture aspect normalize; richer crop editor aspects/handles |
| 1.6.9 | Camera photo crop/rotate editor; portrait-aware image bubbles; edge-triggered older-message pagination |
| 1.6.8 | WhatsApp-style message selection/edit/delete; rich reply previews; attachment preview cache; camera lifecycle fixes |
| 1.6.7 | In-app full-screen video player; media album gallery; `onRetryAttachmentDownload`; camera trim/review polish |
| 1.6.6 | Attachment upload/download cancel (X) and retry; image poster fallback; `onCancelAttachmentDownload` |
| 1.6.5 | iOS-parity attachment kind routing; image↔video preview decode fallback; download fail/retry UI |
| 1.6.4 | Fix video thumbnail rotation on send/receive; preserve trim orientation metadata |
| 1.6.3 | WhatsApp-style video trim scrubbing, upright video posters, media bubbles keep max width |
| 1.6.2 | Camera WYSIWYG 4:3/16:9 capture, fresh session after send, polished Photos/Videos picker tabs |
| 1.6.1 | Fix camera review caption field staying above the keyboard |
| 1.6.0 | Full-screen CameraX capture with photo/video review, caption, trim, and `localFile` host contract |
| 1.5.1 | Fix hold-to-record layout: mic stays trailing, waveform/duration/cancel no longer overlap; lock pad positioning |
| 1.5.0 | iOS-parity media bubbles (image/video grids, voice waveform) and hold-to-record voice composer; tighter bubble/composer sizing |
| 1.4.4 | iOS-parity delivery ticks (single/double/blue) and attachment picker (Photos/Videos tabs, 4-col grid, document tile) |
| 1.4.3 | Fix outgoing bubble sizing to match incoming (explicit measured width, iOS-style side spacer) |
| 1.4.2 | iOS bubble parity: 78% max-width, content-hugging size, inline last-line timestamp/footer, 14dp corners |
| 1.4.1 | Bubble content-wrap, WhatsApp timestamp, inline photo picker, typing indicator bubble |
| 1.4.0 | iOS-parity UI improvements |
| 1.3.0 | Smart VC-style message detail UI, tailed bubbles, polished composer, and stable IME transitions |
| 1.2.0 | State-driven screen, pagination and swipe-to-reply |
| 1.1.0 | IME/keyboard safe-area handling, bottom-stacked message list, insert slide-up, iOS API/UX parity (attachment panel, hold-to-record voice, day separators, unread jump) |
| 1.0.0 | Initial publishable Compose ChatKit module |
