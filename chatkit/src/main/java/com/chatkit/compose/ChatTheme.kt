package com.chatkit.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public data class ChatTheme(
    val backgroundColor: Color = Color.White,
    val incomingBubbleColor: Color = Color(0xFFE8EDF3),
    val outgoingBubbleColor: Color = Color(0xFF1A3D63),
    val accentColor: Color = Color(0xFF1A3D63),
    val composerBarColor: Color = Color.White,
    val composerFieldBackground: Color = Color(0xFFFAFBFC),
    val composerFieldBorderColor: Color = Color(0xFFDDE3EA),
    val incomingTextColor: Color = Color(0xFF1F2D3D),
    val outgoingTextColor: Color = Color.White,
    val incomingTimestampColor: Color = Color(0xFF9AA5B1),
    val outgoingTimestampColor: Color = Color(0xFFB8C5D6),
    val dateSeparatorBackground: Color = Color(0xFFE1E8ED),
    val dateSeparatorTextColor: Color = Color(0xFF667781),
    val bubbleCornerRadius: Dp = 14.dp,
    /** Theme cap; [Dp.Unspecified] uses screen-ratio sizing like iOS ChatKit. */
    val messageMaximumWidth: Dp = Dp.Unspecified,
    val showsDeliveryStatus: Boolean = false,
    val attachmentPanelBackgroundColor: Color = Color.White,
    val attachmentTileBackgroundColor: Color = Color(0xFFF2F2F7),
    val thumbnailPlaceholderBackgroundColor: Color = Color(0xFFF2F2F7),
    val incomingBubbleBorderColor: Color = Color(0xFFE1E8ED),
    val accentContentColor: Color = Color.White,
    val readReceiptColor: Color = Color(0xFF007AFF),
    val typingIndicatorBubbleColor: Color = Color(0xFFE8EDF3),
    val typingIndicatorTextColor: Color = Color(0xFF9AA5B1),
    val composerButtonBackgroundColor: Color = Color(0xFFEFF3F6),
    val composerIconColor: Color = Color(0xFF5F7186),
)
