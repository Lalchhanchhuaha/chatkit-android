package com.chatkit.compose

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * Measures upright media width / height for bubble sizing — WhatsApp-style metadata
 * that should travel with the message so receivers can reserve the correct silhouette
 * before the full file downloads.
 *
 * Prefer stamping [ChatAttachment.aspectRatio] (and optionally [ChatAttachment.posterUri])
 * into every published image/video message. ChatKit reads that field first and only
 * falls back to local decode when it is missing.
 */
public object ChatMediaDimensions {
    /**
     * Returns upright `width / height`, or null when dimensions cannot be read.
     * Safe to call off the main thread; does not decode full bitmaps.
     */
    @JvmStatic
    public fun aspectRatio(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
    ): Float? = measureUprightMediaAspectRatio(context, uri, isVideo)

    /** File-path convenience for camera cache captures. */
    @JvmStatic
    public fun aspectRatio(file: File, isVideo: Boolean): Float? =
        measureUprightMediaAspectRatio(file, isVideo)
}

/**
 * Upright display size from coded (sensor) size + container/EXIF rotation.
 * Used by MediaStore queries and unit tests.
 */
internal fun uprightDisplaySize(
    codedWidth: Int,
    codedHeight: Int,
    rotationDegrees: Int,
): Pair<Int, Int>? {
    if (codedWidth <= 0 || codedHeight <= 0) return null
    val rotation = ((rotationDegrees % 360) + 360) % 360
    return if (rotation == 90 || rotation == 270) {
        codedHeight to codedWidth
    } else {
        codedWidth to codedHeight
    }
}

internal fun aspectRatioFromSize(width: Int, height: Int): Float? {
    if (width <= 0 || height <= 0) return null
    val ratio = width.toFloat() / height.toFloat()
    return ratio.takeIf { it.isFinite() && it > 0f }
}

internal fun measureUprightMediaAspectRatio(
    context: Context,
    uri: Uri,
    isVideo: Boolean,
): Float? = runCatching {
    if (isVideo) {
        measureVideoAspectRatio(context, uri)
    } else {
        measureImageAspectRatio(context, uri)
    }
}.getOrNull()

internal fun measureUprightMediaAspectRatio(file: File, isVideo: Boolean): Float? {
    if (!file.exists()) return null
    return runCatching {
        if (isVideo) {
            measureVideoAspectRatio(file)
        } else {
            measureImageAspectRatio(file)
        }
    }.getOrNull()
}

private fun measureImageAspectRatio(context: Context, uri: Uri): Float? {
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
    return uprightSizeFromExifBounds(bounds.outWidth, bounds.outHeight, orientation)
        ?.let { (w, h) -> aspectRatioFromSize(w, h) }
}

private fun measureImageAspectRatio(file: File): Float? {
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return uprightSizeFromExifBounds(bounds.outWidth, bounds.outHeight, orientation)
        ?.let { (w, h) -> aspectRatioFromSize(w, h) }
}

private fun uprightSizeFromExifBounds(
    codedWidth: Int,
    codedHeight: Int,
    orientation: Int,
): Pair<Int, Int>? {
    if (codedWidth <= 0 || codedHeight <= 0) return null
    val swaps = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
    return if (swaps) codedHeight to codedWidth else codedWidth to codedHeight
}

private fun measureVideoAspectRatio(context: Context, uri: Uri): Float? {
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
        videoAspectFromRetriever(retriever)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun measureVideoAspectRatio(file: File): Float? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        videoAspectFromRetriever(retriever)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun videoAspectFromRetriever(retriever: MediaMetadataRetriever): Float? {
    val codedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        ?.toIntOrNull()
        ?: return null
    val codedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        ?.toIntOrNull()
        ?: return null
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        ?.toIntOrNull()
        ?: 0
    val (w, h) = uprightDisplaySize(codedWidth, codedHeight, rotation) ?: return null
    return aspectRatioFromSize(w, h)
}
