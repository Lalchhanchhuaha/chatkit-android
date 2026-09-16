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

    fun videoPosterFile(cacheDir: File, id: String): File =
        File(cacheDir, "$PREFIX$id-poster.jpg")

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

    /**
     * Makes the stored JPEG match the iOS 4:3 landscape / 3:4 portrait capture frame.
     * CameraX can expose a viewport crop without physically applying it to every
     * device's on-disk JPEG, so normalize orientation and crop the pixels explicitly.
     */
    fun normalizePhotoToCaptureAspect(context: Context, file: File): Boolean {
        val bitmap = decodeBitmapRespectingExif(context, file.toUri(), maxSide = 4096)
            ?: return false
        return try {
            val targetAspect = if (bitmap.width >= bitmap.height) 4f / 3f else 3f / 4f
            val crop = centeredCropForAspect(targetAspect, bitmap.width, bitmap.height)
            saveCroppedPhoto(bitmap, crop, file)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
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
        val posterUri = if (isVideo) {
            media.localFile?.let { video ->
                val dir = video.parentFile ?: return@let null
                val poster = videoPosterFile(dir, media.id)
                // Poster extraction can decode a full video frame. Never do that from the
                // send callback (which runs on the UI thread); use a pre-generated poster
                // when one exists and let the bubble's async decoder handle the fallback.
                poster.takeIf(File::exists)?.toUri()
            }
        } else {
            null
        }
        return ChatAttachment(
            id = media.id,
            fileName = if (isVideo) "video.mp4" else "photo.jpg",
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            durationMillis = media.durationMillis,
            localUri = media.resolvedUri(),
            posterUri = posterUri,
            transferState = TransferState.Uploading(0f),
        )
    }

    fun deviceHasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
}
