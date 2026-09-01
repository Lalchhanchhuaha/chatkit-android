package com.chatkit.compose

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import java.io.File
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
    public val replyToMessageId: String? = null,
    public val replyToMessageText: String? = null,
    public val replyToSenderName: String? = null,
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

/**
 * Status used for ticks. Pending displays as Sent so a single tick appears
 * immediately (iOS `displayedDeliveryStatus`).
 */
public val DeliveryStatus.displayedDeliveryStatus: DeliveryStatus
    get() = when (this) {
        DeliveryStatus.Pending -> DeliveryStatus.Sent
        else -> this
    }

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

    /**
     * Matches ChatKit iOS (`ChatAttachment.isImage` / `isVideo` / `isAudio`):
     * media MIME prefixes win, otherwise filename extension is used even when
     * MIME is generic (`application/octet-stream`) or missing. Non-media MIME
     * must not force Document — that was dropping decrypt cache files that iOS
     * still showed as photo/video bubbles.
     */
    internal fun attachmentKind(): AttachmentKind {
        when {
            mimeType?.startsWith("image/", ignoreCase = true) == true -> return AttachmentKind.Image
            mimeType?.startsWith("video/", ignoreCase = true) == true -> return AttachmentKind.Video
            mimeType?.startsWith("audio/", ignoreCase = true) == true -> return AttachmentKind.Audio
        }
        val extension = fileName.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "heic", "gif", "avif" -> AttachmentKind.Image
            "mov", "mp4", "m4v", "avi", "webm", "mkv" -> AttachmentKind.Video
            "m4a", "mp3", "aac", "wav", "ogg", "opus", "flac", "caf" -> AttachmentKind.Audio
            else -> AttachmentKind.Document
        }
    }
}

/** Upload / download state rendered by an attachment bubble. */
public sealed interface TransferState {
    /** In-flight upload progress. Rendering clamps [progress] to `0f..1f`. */
    @Immutable
    public data class Uploading(public val progress: Float) : TransferState

    /** In-flight download progress. Rendering clamps [progress] to `0f..1f`. */
    @Immutable
    public data class Downloading(public val progress: Float = 0f) : TransferState

    /** Attachment is available. */
    public data object Uploaded : TransferState

    /** Outgoing upload / send failed — bubble shows an upload affordance to retry. */
    public data object Failed : TransferState

    /** Incoming download failed — bubble shows a download affordance to retry. */
    public data object DownloadFailed : TransferState
}

/**
 * Lightweight media selection returned to the host.
 *
 * Gallery picks populate [localUri] from MediaStore. Camera captures populate both
 * [localFile] (required) and [localUri] (`file` URI for the same cache file). Never treat a
 * camera UUID as a MediaStore identifier.
 */
@Immutable
public data class ChatMediaAttachment(
    public val id: String,
    public val mediaType: MediaType,
    public val durationMillis: Long? = null,
    public val localUri: Uri,
    public val localFile: File? = null,
) {
    /** Prefer the camera cache file when present; otherwise the gallery content URI. */
    public fun resolvedUri(): Uri = localFile?.toUri() ?: localUri
}

/** Media category selected by the user. */
public enum class MediaType { Photo, Video }

/** Atomic composer submission. */
@Immutable
public data class ChatDraft(
    public val text: String,
    public val media: List<ChatMediaAttachment> = emptyList(),
    public val documents: List<Uri> = emptyList(),
    public val replyToMessageId: String? = null,
)

/** Resolves host-owned attachment content without coupling ChatKit to a network stack. */
public interface AttachmentResolver {
    /** Returns a readable content URI, reporting download progress when applicable. */
    public suspend fun resolveContent(
        attachment: ChatAttachment,
        onProgress: (Float) -> Unit = {},
    ): Uri?

    /** Returns a poster/preview URI for image or video content when available. */
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

internal fun TransferState.clampedProgress(): Float? = when (this) {
    is TransferState.Uploading -> progress.coerceIn(0f, 1f)
    is TransferState.Downloading -> progress.coerceIn(0f, 1f)
    else -> null
}

internal val TransferState.isTransferring: Boolean
    get() = this is TransferState.Uploading || this is TransferState.Downloading

internal val TransferState.isFailedTransfer: Boolean
    get() = this is TransferState.Failed || this is TransferState.DownloadFailed

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
    replyToMessageId: String? = null,
): ChatDraft = ChatDraft(text.trim(), media, documents, replyToMessageId)
