package com.chatkit.compose

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Adds explicitly downloaded incoming photos/videos to the device gallery when the
 * host opts in via [ChatConfig.automaticallySavesDownloadedMediaToPhotos].
 * Failures are isolated so a gallery permission/write error never fails the chat download.
 */
internal object ReceivedAttachmentMediaStoreSaver {
    private val mutex = Mutex()
    private val savedAttachmentIds = mutableSetOf<String>()
    private val savingAttachmentIds = mutableSetOf<String>()

    suspend fun saveIfNeeded(
        context: Context,
        attachment: ChatAttachment,
        sourceUri: Uri,
    ) {
        if (!attachment.isImage && !attachment.isVideo) return
        mutex.withLock {
            if (attachment.id in savedAttachmentIds) return
            if (!savingAttachmentIds.add(attachment.id)) return
        }
        try {
            withContext(Dispatchers.IO) {
                copyIntoMediaStore(context, attachment, sourceUri)
            }
            mutex.withLock { savedAttachmentIds.add(attachment.id) }
        } catch (_: Exception) {
            // Match iOS: never surface Photos errors as chat download failures.
        } finally {
            mutex.withLock { savingAttachmentIds.remove(attachment.id) }
        }
    }

    private fun copyIntoMediaStore(
        context: Context,
        attachment: ChatAttachment,
        sourceUri: Uri,
    ) {
        val resolver = context.contentResolver
        val isVideo = attachment.isVideo
        val mime = attachment.mimeType?.takeIf { it.isNotBlank() }
            ?: if (isVideo) "video/mp4" else "image/jpeg"
        val displayName = attachment.fileName.ifBlank {
            if (isVideo) "Lungdi-${attachment.id}.mp4" else "Lungdi-${attachment.id}.jpg"
        }
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }
        val relativePath = if (isVideo) {
            Environment.DIRECTORY_MOVIES + "/Lungdi"
        } else {
            Environment.DIRECTORY_PICTURES + "/Lungdi"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val dest = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(dest)?.use { output ->
                resolver.openInputStream(sourceUri)?.use { input ->
                    input.copyTo(output)
                } ?: throw IOException("Unable to open source $sourceUri")
            } ?: throw IOException("Unable to open destination $dest")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(dest, values, null, null)
            }
        } catch (error: Exception) {
            resolver.delete(dest, null, null)
            throw error
        }
    }
}
