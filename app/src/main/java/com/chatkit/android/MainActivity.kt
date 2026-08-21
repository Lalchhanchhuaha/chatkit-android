package com.chatkit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                ChatKitSample()
            }
        }
    }
}

@Composable
private fun ChatKitSample() {
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
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text("Aisha", fontSize = 20.sp)
        }
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

@Preview(showBackground = true)
@Composable
private fun ChatKitPreview() {
    ChatkitTheme {
        ChatKitSample()
    }
}
