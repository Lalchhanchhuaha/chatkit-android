package com.chatkit.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * WhatsApp-style delivery ticks matching iOS ChatKit `DeliveryReceiptMark`:
 * - Pending / Sent → single tick (muted timestamp color)
 * - Delivered → double tick (muted)
 * - Read → double tick (blue / [ChatTheme.readReceiptColor])
 * - Failed → error mark
 */
@Composable
internal fun DeliveryReceiptMark(
    status: DeliveryStatus,
    theme: ChatTheme,
    timestampColor: Color,
    onRetry: () -> Unit = {},
) {
    val displayed = status.displayed
    when (displayed) {
        DeliveryStatus.None -> Unit
        DeliveryStatus.Failed ->
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Failed. Tap to retry.",
                tint = Color(0xFFFF3B30),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRetry),
            )
        DeliveryStatus.Pending, DeliveryStatus.Sent ->
            TickCanvas(
                doubleTick = false,
                color = timestampColor,
                contentDescription = "Sent",
            )
        DeliveryStatus.Delivered ->
            TickCanvas(
                doubleTick = true,
                color = timestampColor,
                contentDescription = "Delivered",
            )
        DeliveryStatus.Read ->
            TickCanvas(
                doubleTick = true,
                color = theme.readReceiptColor,
                contentDescription = "Read",
            )
    }
}

/** Pending displays as Sent (single tick), matching iOS `displayedDeliveryStatus`. */
internal val DeliveryStatus.displayed: DeliveryStatus
    get() = displayedDeliveryStatus

@Composable
private fun TickCanvas(
    doubleTick: Boolean,
    color: Color,
    contentDescription: String,
) {
    // iOS frame: single 18×18, double 26×20 — drawn in a 16×12 design space.
    val width = if (doubleTick) 26.dp else 18.dp
    val height = if (doubleTick) 20.dp else 18.dp
    Canvas(
        modifier = Modifier
            .size(width, height)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val scale = min(size.width / 16f, size.height / 12f)
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        val strokeWidth = 1.8f * scale
        if (doubleTick) {
            drawLine(color, p(0.5f, 6.5f), p(3.8f, 9.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color, p(3.8f, 9.5f), p(8.2f, 4.8f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color, p(5.5f, 6.5f), p(8.8f, 9.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color, p(8.8f, 9.5f), p(15f, 2.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        } else {
            drawLine(color, p(1f, 6f), p(4.5f, 9.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color, p(4.5f, 9.5f), p(11f, 2.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}
