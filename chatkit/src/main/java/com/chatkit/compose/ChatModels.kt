package com.chatkit.compose

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.time.Instant

/** Immutable message supplied by the host, ordered oldest to newest. */
@Immutable
public data class ChatMessage(
    public val id: String,
    public val text: String,
    public val timestamp: Instant,
    public val direction: MessageDirection,
    public val senderName: String? = null,
    public val attachments: List<ChatAttachment> = emptyList(),
    public val deliveryStatus: DeliveryStatus = DeliveryStatus.Sent,
    public val isEdited: Boolean = false,
) {
    /** True when this row should use the incoming bubble treatment. */
    public val isIncoming: Boolean get() = direction == MessageDirection.Incoming

    /** Epoch representation retained as a convenience for date and time formatters. */
    public val timestampMillis: Long get() = timestamp.toEpochMilli()
}

/** Direction of a message relative to the current user. */
public enum class MessageDirection { Incoming, Outgoing }

/** Host-owned delivery state for an outgoing message. */
public enum class DeliveryStatus { None, Pending, Failed, Sent, Delivered, Read }

/** Immutable attachment metadata. Content resolution remains the host's responsibility. */
@Immutable
public data class ChatAttachment(
    public val id: String,
    public val fileName: String,
    public val mimeType: String? = null,
    public val durationMillis: Long? = null,
    public val localUri: Uri? = null,
    public val posterUri: Uri? = null,
    public val transferState: TransferState = TransferState.Uploaded,
) {
    /** True when MIME metadata or, as a fallback, the extension describes an image. */
    public val isImage: Boolean get() = attachmentKind() == AttachmentKind.Image

    /** True when MIME metadata or, as a fallback, the extension describes a video. */
    public val isVideo: Boolean get() = attachmentKind() == AttachmentKind.Video

    /** True when MIME metadata or, as a fallback, the extension describes audio. */
    public val isAudio: Boolean get() = attachmentKind() == AttachmentKind.Audio

    internal fun attachmentKind(): AttachmentKind {
        val mimeKind = when {
            mimeType?.startsWith("image/", ignoreCase = true) == true -> AttachmentKind.Image
            mimeType?.startsWith("video/", ignoreCase = true) == true -> AttachmentKind.Video
            mimeType?.startsWith("audio/", ignoreCase = true) == true -> AttachmentKind.Audio
            mimeType != null -> AttachmentKind.Document
            else -> null
        }
        if (mimeKind != null) return mimeKind
        val extension = fileName.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "heic", "gif", "avif" -> AttachmentKind.Image
            "mov", "mp4", "m4v", "avi", "webm", "mkv" -> AttachmentKind.Video
            "m4a", "mp3", "aac", "wav", "ogg", "opus", "flac" -> AttachmentKind.Audio
            else -> AttachmentKind.Document
        }
    }
}

/** Upload state rendered by an attachment bubble. */
public sealed interface TransferState {
    /** In-flight upload progress. Rendering clamps [progress] to `0f..1f`. */
    @Immutable
    public data class Uploading(public val progress: Float) : TransferState

    /** Attachment is available. */
    public data object Uploaded : TransferState

    /** Attachment upload failed. */
    public data object Failed : TransferState
}

/** Lightweight media selection returned to the host. */
@Immutable
public data class ChatMediaAttachment(
    public val id: String,
    public val mediaType: MediaType,
    public val durationMillis: Long? = null,
    public val localUri: Uri,
)

/** Media category selected by the user. */
public enum class MediaType { Photo, Video }

/** Atomic composer submission. */
@Immutable
public data class ChatDraft(
    public val text: String,
    public val media: List<ChatMediaAttachment> = emptyList(),
    public val documents: List<Uri> = emptyList(),
)

/** Resolves host-owned attachment content without coupling ChatKit to a network stack. */
public interface AttachmentResolver {
    /** Returns a readable content URI, reporting download progress when applicable. */
    public suspend fun resolveContent(
        attachment: ChatAttachment,
        onProgress: (Float) -> Unit = {},
    ): Uri?

    /** Returns a poster URI for video content when available. */
    public suspend fun resolvePoster(attachment: ChatAttachment): Uri? = attachment.posterUri

    /** Returns whether content can be rendered without starting a network transfer. */
    public suspend fun isAvailableLocally(attachment: ChatAttachment): Boolean =
        attachment.localUri != null

    /** Resolver that only exposes attachment-local URIs. */
    public data object None : AttachmentResolver {
        override suspend fun resolveContent(
            attachment: ChatAttachment,
            onProgress: (Float) -> Unit,
        ): Uri? = attachment.localUri
    }
}

internal enum class AttachmentKind { Image, Video, Audio, Document }

internal fun TransferState.clampedProgress(): Float? =
    (this as? TransferState.Uploading)?.progress?.coerceIn(0f, 1f)

internal fun ChatMessage.canEdit(now: Instant, windowMillis: Long): Boolean =
    direction == MessageDirection.Outgoing && text.isNotBlank() && windowMillis >= 0 &&
        timestamp.plusMillis(windowMillis).isAfter(now)

internal fun reconcileMessages(
    hostMessages: List<ChatMessage>,
    optimisticMessages: List<ChatMessage>,
): List<ChatMessage> {
    val hostIds = hostMessages.asSequence().map(ChatMessage::id).toHashSet()
    return hostMessages + optimisticMessages.filterNot { it.id in hostIds }
}

internal fun buildDraft(
    text: String,
    media: List<ChatMediaAttachment>,
    documents: List<Uri>,
): ChatDraft = ChatDraft(text.trim(), media, documents)
