package com.chatkit.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleMetricsTest {
    @Test
    fun maxBubbleWidthUsesScreenRatioWhenThemeUnspecified() {
        val width = ChatBubbleMetrics.maxBubbleWidth(360.dp, Dp.Unspecified)
        // (360 - 20) * 0.78 = 265.2
        assertEquals(265.2f, width.value, 0.01f)
    }

    @Test
    fun maxBubbleWidthRespectsThemeCap() {
        val width = ChatBubbleMetrics.maxBubbleWidth(360.dp, 200.dp)
        assertEquals(200.dp, width)
    }

    @Test
    fun maxBubbleWidthNeverBelowMinimum() {
        val width = ChatBubbleMetrics.maxBubbleWidth(80.dp, Dp.Unspecified)
        assertEquals(ChatBubbleMetrics.MinimumWidth, width)
    }

    @Test
    fun shortMessageKeepsFooterInline() {
        val layout = WhatsAppBubbleTextLayoutCalculator.measure(
            naturalTextWidthPx = 40f,
            wrappedTextWidthPx = 40f,
            lastLineWidthPx = 40f,
            textHeightPx = 22f,
            lineHeightPx = 22f,
            maxTextWidthPx = 240f,
            footerWidthPx = 60f,
        )
        assertFalse(layout.footerOnNewLine)
        assertTrue(layout.contentWidthPx > 40f)
    }

    @Test
    fun longSingleLineForcesFooterNewLine() {
        val layout = WhatsAppBubbleTextLayoutCalculator.measure(
            naturalTextWidthPx = 220f,
            wrappedTextWidthPx = 220f,
            lastLineWidthPx = 220f,
            textHeightPx = 22f,
            lineHeightPx = 22f,
            maxTextWidthPx = 240f,
            footerWidthPx = 70f,
        )
        assertTrue(layout.footerOnNewLine)
        assertEquals(240f, layout.contentWidthPx, 0.01f)
    }

    @Test
    fun wrappedMessageKeepsFooterOnLastLineWhenSpaceAllows() {
        val layout = WhatsAppBubbleTextLayoutCalculator.measure(
            naturalTextWidthPx = 400f,
            wrappedTextWidthPx = 240f,
            lastLineWidthPx = 80f,
            textHeightPx = 66f,
            lineHeightPx = 22f,
            maxTextWidthPx = 240f,
            footerWidthPx = 60f,
        )
        assertFalse(layout.footerOnNewLine)
    }

    @Test
    fun footerSpacerUsesNonBreakingPrefix() {
        val spacer = WhatsAppBubbleTextLayoutCalculator.footerSpacerString(24f, 8f)
        assertTrue(spacer.startsWith("\u00A0"))
        assertTrue(spacer.contains('\u2007'))
    }
}
