package com.chatkit.compose

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import java.io.File
import java.util.Locale
import java.util.UUID

internal object ChatCameraFiles {
    private const val PREFIX = "chat-camera-"

    fun newCaptureId(): String = UUID.randomUUID().toString().lowercase(Locale.US)

    fun photoFile(cacheDir: File, id: String = newCaptureId()): Pair<String, File> {
        val file = File(cacheDir, "$PREFIX$id.jpg")
        return id to file
    }

    fun videoFile(cacheDir: File, id: String = newCaptureId()): Pair<String, File> {
        val file = File(cacheDir, "$PREFIX$id.mp4")
        return id to file
    }

    fun deleteQuietly(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
    }

    fun isChatCameraFile(file: File): Boolean =
        file.name.startsWith(PREFIX) &&
            (file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".mp4", ignoreCase = true))

    fun durationSeconds(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return null
            millis / 1000.0
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun toMediaAttachment(
        capture: CapturedMedia,
        trimmedFile: File? = null,
        trimmedDurationSeconds: Double? = null,
    ): ChatMediaAttachment {
        val file = trimmedFile ?: capture.localFile
        val durationSeconds = when {
            capture.mediaType == MediaType.Photo -> null
            trimmedDurationSeconds != null -> trimmedDurationSeconds
            else -> capture.durationSeconds
        }
        return ChatMediaAttachment(
            id = capture.id,
            mediaType = capture.mediaType,
            durationMillis = durationSeconds?.let { (it * 1000.0).toLong() },
            localUri = file.toUri(),
            localFile = file,
        )
    }

    fun makeOptimisticAttachment(media: ChatMediaAttachment): ChatAttachment {
        val isVideo = media.mediaType == MediaType.Video
        return ChatAttachment(
            id = media.id,
            fileName = if (isVideo) "video.mp4" else "photo.jpg",
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            durationMillis = media.durationMillis,
            localUri = media.resolvedUri(),
            transferState = TransferState.Uploading(0f),
        )
    }

    fun deviceHasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
}
