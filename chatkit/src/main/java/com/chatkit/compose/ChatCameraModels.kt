package com.chatkit.compose

import androidx.compose.runtime.Immutable
import java.io.File

/** Explicit camera destination state. Do not infer live vs review from nullable UI. */
internal sealed interface ChatCameraState {
    data object RequestingPermission : ChatCameraState
    data object Live : ChatCameraState
    data class Reviewing(val capture: CapturedMedia) : ChatCameraState
    data object ExportingTrim : ChatCameraState
}

internal enum class CaptureMode { PHOTO, VIDEO }

@Immutable
internal data class CapturedMedia(
    val id: String,
    val mediaType: MediaType,
    val localFile: File,
    val durationSeconds: Double?,
    val caption: String = "",
)

@Immutable
internal data class VideoTrimRange(
    val startSeconds: Double,
    val endSeconds: Double,
) {
    val durationSeconds: Double get() = (endSeconds - startSeconds).coerceAtLeast(0.0)

    fun isFullRange(totalSeconds: Double, epsilon: Double = 0.05): Boolean =
        startSeconds <= epsilon && endSeconds >= (totalSeconds - epsilon)
}

internal fun minimumTrimDurationSeconds(durationSeconds: Double): Double =
    minOf(1.0, maxOf(0.3, durationSeconds * 0.05))

internal fun clampTrimRange(
    startSeconds: Double,
    endSeconds: Double,
    totalSeconds: Double,
): VideoTrimRange {
    val total = totalSeconds.coerceAtLeast(0.0)
    val minDuration = minimumTrimDurationSeconds(total)
    if (total <= 0.0) return VideoTrimRange(0.0, 0.0)
    if (total <= minDuration) return VideoTrimRange(0.0, total)

    var start = startSeconds.coerceIn(0.0, total)
    var end = endSeconds.coerceIn(0.0, total)
    if (end - start < minDuration) {
        if (start + minDuration <= total) {
            end = start + minDuration
        } else {
            start = (total - minDuration).coerceAtLeast(0.0)
            end = total
        }
    }
    return VideoTrimRange(start, end)
}

/** Moves only the in-point, keeping the out-point fixed like a conventional trimmer. */
internal fun moveTrimStart(
    range: VideoTrimRange,
    proposedStartSeconds: Double,
    totalSeconds: Double,
): VideoTrimRange {
    val total = totalSeconds.coerceAtLeast(0.0)
    if (total <= 0.0) return VideoTrimRange(0.0, 0.0)
    val end = range.endSeconds.coerceIn(0.0, total)
    val minDuration = minimumTrimDurationSeconds(total).coerceAtMost(end)
    return VideoTrimRange(
        startSeconds = proposedStartSeconds.coerceIn(0.0, (end - minDuration).coerceAtLeast(0.0)),
        endSeconds = end,
    )
}

/** Moves only the out-point, keeping the in-point fixed like a conventional trimmer. */
internal fun moveTrimEnd(
    range: VideoTrimRange,
    proposedEndSeconds: Double,
    totalSeconds: Double,
): VideoTrimRange {
    val total = totalSeconds.coerceAtLeast(0.0)
    if (total <= 0.0) return VideoTrimRange(0.0, 0.0)
    val start = range.startSeconds.coerceIn(0.0, total)
    val minDuration = minimumTrimDurationSeconds(total).coerceAtMost(total - start)
    return VideoTrimRange(
        startSeconds = start,
        endSeconds = proposedEndSeconds.coerceIn((start + minDuration).coerceAtMost(total), total),
    )
}

internal fun moveTrimWindow(
    range: VideoTrimRange,
    deltaSeconds: Double,
    totalSeconds: Double,
): VideoTrimRange {
    val duration = range.durationSeconds
    val minDuration = minimumTrimDurationSeconds(totalSeconds)
    val window = duration.coerceAtLeast(minDuration)
    val maxStart = (totalSeconds - window).coerceAtLeast(0.0)
    val start = (range.startSeconds + deltaSeconds).coerceIn(0.0, maxStart)
    return VideoTrimRange(start, start + window)
}
