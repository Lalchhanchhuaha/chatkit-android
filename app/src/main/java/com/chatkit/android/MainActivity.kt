package com.chatkit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatkit.android.ui.theme.ChatkitTheme
import com.chatkit.compose.ChatAction
import com.chatkit.compose.ChatConfig
import com.chatkit.compose.ChatMessage
import com.chatkit.compose.ChatScreen
import com.chatkit.compose.ChatUiState
import com.chatkit.compose.DeliveryStatus
import com.chatkit.compose.MessageDirection
import com.chatkit.compose.TransferState
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatkitTheme {
                ChatKitSample(onBack = ::finish)
            }
        }
    }
}

@Composable
private fun ChatKitSample(onBack: () -> Unit = {}) {
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                id = "welcome",
                text = "Hey! This conversation UI is coming from the reusable ChatKit module.",
                direction = MessageDirection.Incoming,
                senderName = "Aisha",
                timestamp = Instant.now().minusSeconds(86_400),
            ),
            ChatMessage(
                id = "reply",
                text = "Nice — the app only owns this message list and the callbacks.",
                timestamp = Instant.now(),
                direction = MessageDirection.Outgoing,
                deliveryStatus = DeliveryStatus.Read,
            ),
        )
    }
    var isTyping by remember { mutableStateOf(false) }

    Column {
        MessageDetailHeader(name = "Aisha", subtitle = "District", onBack = onBack)
        ChatScreen(
            state = ChatUiState(
                messages = messages.toList(),
                isTyping = isTyping,
                typingIndicatorText = "Aisha is typing…",
            ),
            config = ChatConfig(showSenderNames = true, showDeliveryStatus = true),
            onAction = { action ->
                when (action) {
                    is ChatAction.OptimisticMessage -> if (messages.none { it.id == action.message.id }) {
                        messages += action.message
                    }
                    is ChatAction.Submit -> {
                        isTyping = false
                        val draft = action.draft
                        if (draft.media.isEmpty() && draft.documents.isEmpty()) {
                            if (draft.text.isNotBlank()) {
                                val repliedMessage = messages.firstOrNull {
                                    it.id == draft.replyToMessageId
                                }
                                messages += ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    text = draft.text,
                                    timestamp = Instant.now(),
                                    direction = MessageDirection.Outgoing,
                                    deliveryStatus = DeliveryStatus.Sent,
                                    replyToMessageId = repliedMessage?.id,
                                    replyToMessageText = repliedMessage?.text,
                                    replyToSenderName = repliedMessage?.senderName,
                                )
                            }
                        } else {
                            val index = messages.indexOfLast { message ->
                                message.attachments.any { it.transferState is TransferState.Uploading }
                            }
                            if (index >= 0) {
                                val current = messages[index]
                                messages[index] = current.copy(
                                    text = draft.text,
                                    deliveryStatus = DeliveryStatus.Sent,
                                    attachments = current.attachments.map {
                                        it.copy(transferState = TransferState.Uploaded)
                                    },
                                )
                            }
                        }
                    }
                    is ChatAction.RetryMessage -> {
                        val index = messages.indexOfFirst { it.id == action.messageId }
                        if (index >= 0) messages[index] = messages[index].copy(
                            deliveryStatus = DeliveryStatus.Pending,
                        )
                    }
                    is ChatAction.EditMessage -> {
                        val index = messages.indexOfFirst { it.id == action.messageId }
                        if (index >= 0) messages[index] = messages[index].copy(
                            text = action.text,
                            isEdited = true,
                        )
                    }
                    is ChatAction.DeleteMessage -> messages.removeAll { it.id == action.messageId }
                    is ChatAction.CancelAttachmentUpload -> Unit
                    is ChatAction.VoiceRecorded -> Unit
                    ChatAction.LoadPreviousMessages -> Unit
                    ChatAction.RetryLoading -> Unit
                    ChatAction.DismissError -> Unit
                }
            },
        )
    }
}

@Composable
private fun MessageDetailHeader(
    name: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A3D63), Color(0xFF0A1931)),
                ),
            )
            .statusBarsPadding()
            .padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8F5E9)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color(0xFFDCE4EF),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatKitPreview() {
    ChatkitTheme {
        ChatKitSample()
    }
}
