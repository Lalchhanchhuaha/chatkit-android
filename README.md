# ChatKit for Android

ChatKit is a reusable Jetpack Compose conversation UI. It contains no backend, credentials,
database, analytics, or dependency-injection framework. The host owns messages, authentication,
networking, persistence, uploads, delivery state, pagination, and retry policy.

## Capabilities

- Incoming/outgoing text and attachment bubbles, date separators, receipts, typing, and unread count
- Multiline composer with atomic `ChatDraft` submission
- Android Photo Picker, document picker with persistable URI grants, and optional camera capture
- Optimistic media, document, and voice rows reconciled by stable message ID
- Hold/slide-to-cancel/slide-up-to-lock voice recording and conversation-scoped audio playback
- Retry, edit, delete, upload cancellation, host attachment resolution, and configurable theming
- Swipe-to-reply with composer quote preview and reply metadata on submitted drafts
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
                includeGroup("com.github.Lalchhanchhuaha.chatkit-android")
            }
        }
    }
}
```

Then add the library module dependency:

```kotlin
dependencies {
    implementation("com.github.Lalchhanchhuaha.chatkit-android:chatkit:v1.2.0")
}
```

For development snapshots, replace `v1.2.0` with `main-SNAPSHOT`. Tagged versions are recommended
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

When `onSubmit` is supplied, it is called exactly once. The legacy callbacks (`onSendText`,
`onMediaPicked`, and `onDocumentsPicked`) are not called for that submission.

For an attachment draft, persist the message delivered to `onOptimisticMessage` immediately and
echo the same message ID in `messages`; this removes ChatKit's temporary copy without duplication.

## Permissions and URIs

The library declares `RECORD_AUDIO` and `CAMERA`, and requests audio permission only when recording
is invoked. Photo and document selection use system contracts and require no broad storage
permission.

Camera is opt-in. Create an app-owned content URI with your own `FileProvider`, grant URI access,
and pass it as `ChatConfig(cameraCaptureUri = uri)`. ChatKit intentionally does not declare a
provider authority on behalf of every consuming app.

Voice recordings are temporary cache files. Move or upload them from `onVoiceRecorded`; do not
treat their URI as durable storage.

For edge-to-edge hosts, use `android:windowSoftInputMode="adjustResize"`.

## Build and publish locally

```shell
./gradlew :chatkit:testDebugUnitTest :app:assembleDebug
./gradlew :chatkit:publishReleasePublicationToMavenLocal
```

Local Maven coordinates: `com.chatkit:chatkit:1.2.0`. Minimum Android version: API 24; `java.time` is
supported through core-library desugaring.

## Releases

| Version | Notes |
|---------|--------|
| 1.2.0 | State-driven screen, pagination and swipe-to-reply |
| 1.1.0 | IME/keyboard safe-area handling, bottom-stacked message list, insert slide-up, iOS API/UX parity (attachment panel, hold-to-record voice, day separators, unread jump) |
| 1.0.0 | Initial publishable Compose ChatKit module |
