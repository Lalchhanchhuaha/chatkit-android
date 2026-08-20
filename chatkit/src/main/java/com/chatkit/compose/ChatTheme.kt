package com.chatkit.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public data class ChatTheme(
    val backgroundColor: Color = Color.White,
    val incomingBubbleColor: Color = Color(0xFFF7F7F7),
    val outgoingBubbleColor: Color = Color(0xFF1F486F),
    val accentColor: Color = Color(0xFF1F486F),
    val composerBarColor: Color = Color.White,
    val composerFieldBackground: Color = Color.White,
    val composerFieldBorderColor: Color = Color(0xFFD1D1D6),
    val incomingTextColor: Color = Color(0xFF1A1A1A),
    val outgoingTextColor: Color = Color.White,
    val incomingTimestampColor: Color = Color(0xFF000000).copy(alpha = 0.45f),
    val outgoingTimestampColor: Color = Color.White.copy(alpha = 0.82f),
    val dateSeparatorBackground: Color = Color(0xFFEDEDEF),
    val dateSeparatorTextColor: Color = Color(0xFF737378),
    val bubbleCornerRadius: Dp = 14.dp,
    val messageMaximumWidth: Dp = 280.dp,
    val showsDeliveryStatus: Boolean = false,
    val attachmentPanelBackgroundColor: Color = Color.White,
    val attachmentTileBackgroundColor: Color = Color(0xFFF2F2F7),
    val thumbnailPlaceholderBackgroundColor: Color = Color(0xFFF2F2F7),
    val incomingBubbleBorderColor: Color = Color.Black.copy(alpha = 0.08f),
    val accentContentColor: Color = Color.White,
    val readReceiptColor: Color = Color(0xFF007AFF),
    val typingIndicatorBubbleColor: Color = Color(0xFFF7F7F7),
    val typingIndicatorTextColor: Color = Color(0xFF8E8E93),
)
