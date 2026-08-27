package com.chatkit.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Camera JPEGs often store pixels in sensor orientation and put the real upright
 * transform in EXIF. [BitmapFactory] ignores that tag, which is why received
 * camera photos look rotated in chat bubbles.
 *
 * On API 28+, [android.graphics.ImageDecoder] applies EXIF automatically when the
 * ContentResolver can supply a MIME type. Host cache files are frequently stored
 * without an extension (for example `chat_media/<uuid>`), so FileProvider returns
 * a null type and ImageDecoder fails — fall back to [BitmapFactory] in that case.
 */
internal fun decodeBitmapRespectingExif(
    context: Context,
    uri: Uri,
    maxSide: Int = 0,
): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val decoded = runCatching {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (maxSide > 0) {
                    val longest = max(info.size.width, info.size.height).coerceAtLeast(1)
                    decoder.setTargetSampleSize(max(1, longest / maxSide))
                }
            }
        }.getOrNull()
        if (decoded != null) return decoded
    }
    return decodeBitmapWithFactory(context, uri, maxSide)
}

/**
 * Video frames from [MediaMetadataRetriever] may be sensor-oriented or already
 * display-oriented (OEM/API dependent). Use [uprightRetrievedVideoFrame] with
 * coded size + [MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION].
 */
internal fun decodeVideoFrameRespectingRotation(
    context: Context,
    uri: Uri,
    maxSide: Int = 0,
    timeUs: Long = 0L,
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        runCatching { retriever.setDataSource(context, uri) }
            .recoverCatching {
                val path = uri.path
                if (uri.scheme == "file" && path != null) {
                    retriever.setDataSource(path)
                } else {
                    throw it
                }
            }
            .getOrElse { return null }
        uprightFrameFromRetriever(retriever, maxSide, timeUs)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun decodeVideoFrameRespectingRotation(
    file: File,
    maxSide: Int = 0,
    timeUs: Long = 0L,
): Bitmap? {
    if (!file.exists()) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        uprightFrameFromRetriever(retriever, maxSide, timeUs)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun uprightFrameFromRetriever(
    retriever: MediaMetadataRetriever,
    maxSide: Int,
    timeUs: Long,
): Bitmap? {
    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: return null
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        ?.toIntOrNull()
        ?: 0
    val codedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        ?.toIntOrNull()
    val codedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        ?.toIntOrNull()
    val upright = uprightRetrievedVideoFrame(frame, rotation, codedWidth, codedHeight)
    return if (maxSide > 0) scaleDownBitmap(upright, maxSide) else upright
}

/**
 * Applies container rotation only when [frame] still matches the coded (sensor)
 * orientation. If [getFrameAtTime] already returned display-oriented pixels,
 * rotating again would leave the bubble thumbnail sideways / upside-down.
 */
internal fun uprightRetrievedVideoFrame(
    frame: Bitmap,
    rotationDegrees: Int,
    codedWidth: Int?,
    codedHeight: Int?,
): Bitmap {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation == 0) return frame
    return if (
        shouldApplyVideoRotationMetadata(
            frameWidth = frame.width,
            frameHeight = frame.height,
            rotationDegrees = rotation,
            codedWidth = codedWidth,
            codedHeight = codedHeight,
        )
    ) {
        applyRotationDegrees(frame, rotation)
    } else {
        frame
    }
}

/**
 * Returns true when metadata rotation should still be applied to a retrieved frame.
 * False when the frame already matches display orientation (common on some OEMs).
 */
internal fun shouldApplyVideoRotationMetadata(
    frameWidth: Int,
    frameHeight: Int,
    rotationDegrees: Int,
    codedWidth: Int?,
    codedHeight: Int?,
): Boolean {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation == 0) return false
    val cw = codedWidth ?: 0
    val ch = codedHeight ?: 0
    if (cw <= 0 || ch <= 0) return true

    val displayW = if (rotation == 90 || rotation == 270) ch else cw
    val displayH = if (rotation == 90 || rotation == 270) cw else ch
    val frameLandscape = frameWidth >= frameHeight
    val codedLandscape = cw >= ch
    val displayLandscape = displayW >= displayH

    // Already matches display orientation and differs from coded → don't double-rotate.
    if (frameLandscape == displayLandscape && frameLandscape != codedLandscape) {
        return false
    }
    return true
}

/** Writes an upright JPEG poster next to a camera video for optimistic bubble display. */
internal fun writeUprightVideoPoster(videoFile: File, posterFile: File, maxSide: Int = 720): Boolean {
    val frame = decodeVideoFrameRespectingRotation(videoFile, maxSide = maxSide) ?: return false
    return try {
        posterFile.parentFile?.mkdirs()
        FileOutputStream(posterFile).use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        true
    } catch (_: Exception) {
        ChatCameraFiles.deleteQuietly(posterFile)
        false
    } finally {
        if (!frame.isRecycled) frame.recycle()
    }
}

private fun decodeBitmapWithFactory(
    context: Context,
    uri: Uri,
    maxSide: Int,
): Bitmap? {
    // A valid JPEG may be an MPO container or carry malformed EXIF. Orientation
    // metadata must never prevent BitmapFactory from decoding its first image.
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = if (maxSide > 0) max(1, longest / maxSide) else 1
    }
    val raw = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    return applyExifOrientation(raw, orientation)
}

internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    ) {
        return bitmap
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return bitmap
    }
    val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (upright !== bitmap) bitmap.recycle()
    return upright
}

internal fun applyRotationDegrees(bitmap: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return bitmap
    val matrix = Matrix().apply { setRotate(normalized.toFloat()) }
    val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (upright !== bitmap) bitmap.recycle()
    return upright
}

private fun scaleDownBitmap(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height).coerceAtLeast(1)
    if (longest <= maxSide) return bitmap
    val scale = maxSide.toFloat() / longest
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
