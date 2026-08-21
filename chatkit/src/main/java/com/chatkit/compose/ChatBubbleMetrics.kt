package com.chatkit.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Shared WhatsApp / iOS ChatKit bubble width rules. */
internal object ChatBubbleMetrics {
    const val MaxWidthRatio: Float = 0.78f
    val HorizontalMargin: Dp = 20.dp
    val MinimumWidth: Dp = 120.dp

    /**
     * @param containerWidth Full transcript row width before bubble margins.
     * @param themeMaximum Theme cap; [Dp.Unspecified] means use ratio only.
     */
    fun maxBubbleWidth(containerWidth: Dp, themeMaximum: Dp): Dp {
        val width = containerWidth.takeIf { it > 0.dp } ?: 360.dp
        val ratioCap = ((width - HorizontalMargin) * MaxWidthRatio).coerceAtLeast(MinimumWidth)
        if (themeMaximum == Dp.Unspecified || themeMaximum <= 0.dp) return ratioCap
        return minOf(themeMaximum, ratioCap).coerceAtLeast(MinimumWidth)
    }
}

/**
 * WhatsApp-style text + trailing timestamp sizing (ported from iOS ChatKit).
 *
 * - Grow the bubble up to max width before wrapping.
 * - Short messages keep text + footer on one line when they fit.
 * - When the footer cannot fit on the last line, place it on a new line.
 */
internal data class WhatsAppBubbleTextLayout(
    val contentWidthPx: Float,
    val spacerWidthPx: Float,
    val footerOnNewLine: Boolean,
)

internal object WhatsAppBubbleTextLayoutCalculator {
    private const val FooterGapPx = 8f

    fun measure(
        naturalTextWidthPx: Float,
        wrappedTextWidthPx: Float,
        lastLineWidthPx: Float,
        textHeightPx: Float,
        lineHeightPx: Float,
        maxTextWidthPx: Float,
        footerWidthPx: Float,
        forceSingleLine: Boolean = false,
    ): WhatsAppBubbleTextLayout {
        val maxWidth = max(1f, maxTextWidthPx)
        val spacerWidth = max(ceil(footerWidthPx), 1f)
        val naturalWidth = max(1f, ceil(naturalTextWidthPx))
        val withFooterWidth = naturalWidth + FooterGapPx + spacerWidth

        val bodyIsSingleLineAtMax = forceSingleLine ||
            naturalWidth <= maxWidth + 0.5f ||
            ceil(textHeightPx) <= lineHeightPx + 3f

        val contentWidth: Float
        val footerOnNewLine: Boolean

        if (bodyIsSingleLineAtMax) {
            if (withFooterWidth <= maxWidth + 0.5f) {
                contentWidth = withFooterWidth
                footerOnNewLine = false
            } else {
                contentWidth = maxWidth
                footerOnNewLine = true
            }
        } else {
            val wrappedWidth = max(1f, ceil(wrappedTextWidthPx))
            val neededOnLastLine = lastLineWidthPx + FooterGapPx + spacerWidth
            if (neededOnLastLine <= maxWidth + 0.5f) {
                contentWidth = max(wrappedWidth, neededOnLastLine)
                footerOnNewLine = false
            } else {
                contentWidth = maxWidth
                footerOnNewLine = true
            }
        }

        return WhatsAppBubbleTextLayout(
            contentWidthPx = min(maxWidth, max(contentWidth, 1f)),
            spacerWidthPx = spacerWidth,
            footerOnNewLine = footerOnNewLine,
        )
    }

    /** Clear figure-space run that reserves footer width without forcing a break. */
    fun footerSpacerString(widthPx: Float, figureWidthPx: Float): String {
        val figure = '\u2007'
        val unit = max(1f, figureWidthPx)
        val count = max(1, ceil(widthPx / unit).toInt())
        return "\u00A0" + figure.toString().repeat(count)
    }
}
