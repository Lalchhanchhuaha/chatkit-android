package com.chatkit.compose

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Remuxes an MP4 to the selected time range when possible. Falls back to copying the
 * original file when the container cannot be remuxed.
 */
internal object VideoTrimExporter {
    fun export(
        source: File,
        destination: File,
        startSeconds: Double,
        endSeconds: Double,
    ): Boolean {
        if (!source.exists() || endSeconds <= startSeconds) return false
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(source.absolutePath)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()
            var orientationHint = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    if (mime.startsWith("video/") &&
                        format.containsKey(MediaFormat.KEY_ROTATION)
                    ) {
                        orientationHint = format.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    trackMap[index] = muxer.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) return false

            // MediaMuxer drops container rotation unless set explicitly before start().
            if (orientationHint == 0) {
                orientationHint = readVideoRotationDegrees(source)
            }
            if (orientationHint != 0) {
                muxer.setOrientationHint(orientationHint)
            }

            muxer.start()
            val startUs = (startSeconds * 1_000_000.0).toLong()
            val endUs = (endSeconds * 1_000_000.0).toLong()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            for ((sourceTrack, muxerTrack) in trackMap) {
                extractor.selectTrack(sourceTrack)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime > endUs) break
                    if (sampleTime >= startUs) {
                        info.offset = 0
                        info.size = sampleSize
                        info.presentationTimeUs = (sampleTime - startUs).coerceAtLeast(0L)
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxerTrack, buffer, info)
                    }
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(sourceTrack)
            }
            true
        } catch (_: Exception) {
            ChatCameraFiles.deleteQuietly(destination)
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    fun filmstripFrames(file: File, frameCount: Int, maxSide: Int = 120): List<Bitmap> {
        if (!file.exists() || frameCount <= 0) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: return emptyList()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            val codedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val codedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val frames = ArrayList<Bitmap>(frameCount)
            for (i in 0 until frameCount) {
                val timeMs = if (frameCount == 1) {
                    0L
                } else {
                    (durationMs * i / (frameCount - 1).toDouble()).roundToInt().toLong()
                }
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: continue
                val upright = uprightRetrievedVideoFrame(frame, rotation, codedWidth, codedHeight)
                frames += scaleDown(upright, maxSide)
            }
            frames
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readVideoRotationDegrees(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
