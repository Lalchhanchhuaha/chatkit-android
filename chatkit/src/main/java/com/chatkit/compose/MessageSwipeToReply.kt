package com.chatkit.compose

import kotlin.math.exp

/**
 * Shared WhatsApp-style swipe-to-reply thresholds and progress mapping.
 * Mirrors iOS `MessageSwipeToReply`.
 */
internal object MessageSwipeToReply {
    const val ThresholdDp = 64f
    const val MaxPullDp = 92f
    const val AffordanceSideDp = 30f

    /**
     * Track the finger directly until the action is armed, then progressively
     * resist extra travel instead of stopping the bubble at a hard boundary.
     */
    fun resistedPull(visualTranslationPx: Float, thresholdPx: Float, maxPullPx: Float): Float {
        val rawPull = visualTranslationPx.coerceAtLeast(0f)
        if (rawPull <= thresholdPx) return rawPull

        val availableOvershoot = maxPullPx - thresholdPx
        val overshoot = rawPull - thresholdPx
        val resistedOvershoot = availableOvershoot * (1f - exp(-overshoot / 30f).toFloat())
        return thresholdPx + resistedOvershoot
    }

    fun progress(pullPx: Float, thresholdPx: Float): Float =
        (pullPx / thresholdPx).coerceIn(0f, 1f)

    fun didCrossThreshold(pullPx: Float, thresholdPx: Float): Boolean =
        pullPx >= thresholdPx
}
