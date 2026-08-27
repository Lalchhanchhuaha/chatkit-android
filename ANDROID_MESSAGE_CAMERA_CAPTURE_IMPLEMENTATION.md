# Android Message Camera Capture

Implement the Android chat camera so its behavior and host contract match the
iOS implementation in `Sources/ChatKit/CameraCapturePicker.swift` and
`Sources/ChatKit/ChatView.swift`.

## Required user flow

1. Show a dedicated camera button beside the attachment button in the message
   composer. Disable it while a message is being edited.
2. Tapping the button dismisses the keyboard and attachment panel, then opens a
   full-screen camera.
3. Start in **Photo** mode with the rear camera at the normal 1x lens.
4. If video attachments are enabled, show a **Photo / Video** selector. Hide the
   selector and configure image capture only when video is disabled.
5. In the live camera provide:
   - Close
   - Flash off/on when supported
   - Front/rear camera flip
   - 0.5x and 1x lens shortcuts when supported
   - Pinch-to-zoom, capped at 8x
   - Tap-to-focus inside the viewfinder
   - A centered shutter button
6. In Photo mode, one shutter tap captures a JPEG.
7. In Video mode, the first shutter tap starts recording and the second stops
   it. Show a red recording state and use the torch continuously when flash is
   enabled.
8. After capture, stop the camera preview and show an inline review screen:
   - Photo: aspect-fit image preview
   - Video: aspect-fit playback, play/pause control, filmstrip, duration, and
     draggable start/end trim handles
   - Retake button in the top-left
   - Caption field with placeholder `Write a message…`
   - Send button using the ChatKit accent and content colors
9. Retake deletes the current temporary file, clears the caption and trim, and
   returns to the live camera.
10. Send trims surrounding whitespace from the caption. If a video was trimmed,
    export the selected range first; otherwise return the original file.
11. Close or back cancels without submitting. Delete abandoned temporary files.

The live camera must not show the message composer. Caption and send controls
appear only in the review state.

## Compose and CameraX structure

Use a full-screen Compose destination or dialog backed by CameraX:

- `PreviewView` hosted through `AndroidView`
- `Preview`
- `ImageCapture`
- `VideoCapture<Recorder>` only when video is enabled
- `CameraSelector` for front/rear switching
- `CameraControl` and `CameraInfo` for focus, zoom, torch, and supported ranges
- Media3 for review playback
- `MediaMetadataRetriever` for duration and trimmer thumbnails
- `MediaExtractor`/`MediaMuxer`, Media3 Transformer, or an equivalent local
  exporter for lossless video trimming when possible

Model the screen as an explicit state machine. Do not infer whether the camera
or review is active from nullable UI elements.

```kotlin
sealed interface ChatCameraState {
    data object RequestingPermission : ChatCameraState
    data object Live : ChatCameraState
    data class Reviewing(val capture: CapturedMedia) : ChatCameraState
    data object ExportingTrim : ChatCameraState
}

enum class CaptureMode { PHOTO, VIDEO }
enum class ChatMediaType { PHOTO, VIDEO }

data class CapturedMedia(
    val id: String,
    val mediaType: ChatMediaType,
    val localFile: File,
    val durationSeconds: Double?,
    val caption: String = "",
)
```

Bind and unbind CameraX use cases with the destination lifecycle. Serialize
mode changes and camera flips so a second request cannot run while rebinding is
in progress. Stop recording before unbinding.

Use a 4:3 photo viewfinder and a 16:9 video viewfinder. The preview may use
center-crop, but captured media shown during review must use aspect-fit so it is
not silently cropped.

## Attachment and submission contract

Camera captures are files owned by the app, not MediaStore selections. Preserve
that distinction in the shared attachment model.

```kotlin
data class ChatMediaAttachment(
    val id: String,
    val mediaType: ChatMediaType,
    val durationSeconds: Double? = null,
    val localFile: File? = null,
)

data class ChatDraft(
    val text: String,
    val media: List<ChatMediaAttachment>,
    val documents: List<File> = emptyList(),
)
```

For every camera result:

- Generate a lowercase UUID for `id`.
- Save it in `context.cacheDir` as
  `chat-camera-<lowercase-uuid>.jpg` or
  `chat-camera-<lowercase-uuid>.mp4`.
- Set `localFile` to that file. It is required for camera captures.
- Set `durationSeconds` for video and `null` for photos.
- Never insert the capture into MediaStore merely to send it.
- Never treat the UUID as a MediaStore identifier. Gallery selections may use a
  content URI; camera captures must be loaded directly from `localFile`.

On send, create the attachment and optimistic message, then invoke callbacks in
this order:

```kotlin
val media = ChatMediaAttachment(
    id = capture.id,
    mediaType = capture.mediaType,
    durationSeconds = capture.durationSeconds,
    localFile = capture.localFile,
)

onOptimisticMessage(
    ChatMessage.outgoing(
        text = capture.caption.trim(),
        attachments = listOf(makeOptimisticAttachment(media)),
    )
)
onSubmit(
    ChatDraft(
        text = capture.caption.trim(),
        media = listOf(media),
    )
)
```

Dismiss the camera before submitting. Dispatch submission after dismissal (for
example, in the next main-loop turn) so destination teardown cannot re-enter
the capture callback. Guard the callback so a double tap cannot submit twice.

The host must retain the local file long enough for the optimistic preview and
upload. It may copy the file into its own cache before ChatKit cleanup.

## Permissions and failure behavior

Declare:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- Request `CAMERA` when opening the camera.
- Request `RECORD_AUDIO` when video capture needs sound. If product policy
  allows silent video, keep video available when microphone access is denied
  and clearly record without audio.
- A denied camera permission shows:
  - Title: `Camera Access Needed`
  - Message: `Allow camera access in Settings to take photos and videos.`
  - Actions: **Settings** and **Cancel**
- A missing, busy, or unusable camera shows:
  - Title: `Camera Unavailable`
  - Message: `The camera is unavailable on this device right now.`
  - Action: **Cancel**
- The composer camera button may be hidden on devices without a camera.

Open app settings with `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

## Video trim behavior

- Initialize the selected range to the entire recording.
- Display filmstrip frames across the recorded duration.
- Keep the selected range at least
  `min(1.0, max(0.3, durationSeconds * 0.05))` seconds.
- Allow either trim handle to resize the range.
- Allow dragging the selected window without changing its duration.
- Seeking while trimming previews the selected boundary.
- Playback starts at the trim start and stops at the trim end.
- Sending an unchanged range uses the original file.
- Sending a changed range exports a new MP4, replaces the attachment file and
  duration, and deletes the superseded temporary file.
- Disable repeated send actions while export is running.

## Upload integration

Camera capture ends at the same host boundary as gallery media. The host then:

1. Loads bytes from `localFile`.
2. Compresses video on-device and enforces the upload limit.
3. Generates a low-resolution video poster with
   `MediaMetadataRetriever`.
4. Encrypts the media locally.
5. Encrypts the poster with the same file key.
6. Uploads only ciphertext and sends the caption plus encrypted media metadata.

Follow the host application's end-to-end media specification for exact
encryption, upload, and thumbnail fields. ChatKit must not perform networking.

## Accessibility and lifecycle

- Provide content descriptions for Open camera, Close, Flash, Flip camera,
  Shutter, Retake, Play/Pause video, trim handles, and Send.
- Keep every interactive target at least 48 dp.
- Restore system-bar appearance when leaving the full-screen camera.
- Handle app backgrounding by stopping preview or recording safely.
- Preserve the captured review across configuration changes with a
  `ViewModel`/`SavedStateHandle`; store only the file path and primitive state.
- Do not persist camera bitmaps or video bytes in saved state.

## Acceptance tests

At minimum, verify:

1. The composer camera opens full-screen and dismisses keyboard/pickers.
2. Photo capture returns a JPEG file and a non-null `localFile`.
3. Video mode is absent when video attachments are disabled.
4. Video recording returns a playable local file and accurate duration.
5. Front/rear flip, flash, focus, and zoom respect device capabilities.
6. Retake deletes the discarded file and resets caption and trim.
7. Caption is trimmed and included in both optimistic and submitted messages.
8. Optimistic callback happens before submit and only once.
9. Camera UUIDs are never resolved through MediaStore.
10. A trimmed video exports the selected range and cannot be double-submitted.
11. Permission denial offers Settings; unavailable hardware exits cleanly.
12. Rotation and background/foreground transitions do not leak or duplicate a
    CameraX binding.
13. Temporary files are cleaned after upload, cancellation, or terminal error.

## Copyable build instruction

```text
Implement Android message camera capture with Jetpack Compose and CameraX so it
matches the iOS ChatKit camera behavior documented in
ANDROID_MESSAGE_CAMERA_CAPTURE_IMPLEMENTATION.md.

Use the existing Android ChatKit models, theme, composer, optimistic-message
pipeline, and host callbacks. Add a dedicated composer camera button and a
full-screen live/review state machine. Support photo capture and optional video,
camera flip, supported flash modes, 0.5x/1x shortcuts, pinch zoom, tap focus,
video recording state, photo/video review, caption, retake, send, playback, and
video trimming.

Camera results must be cache files named chat-camera-<lowercase-uuid>.<ext>.
Return them as ChatMediaAttachment with localFile populated; never treat the
UUID as a MediaStore id. Dismiss before invoking one optimistic callback,
followed by one submit callback. Keep capture separate from encryption and
network upload.

Implement permission, lifecycle, accessibility, cleanup, and error behavior
from the guide. Add unit and instrumentation tests for every acceptance item.
Do not change backend APIs or gallery attachment semantics.
```
