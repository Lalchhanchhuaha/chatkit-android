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
import com.chatkit.compose.ChatAttachment
import com.chatkit.compose.ChatConfig
import com.chatkit.compose.ChatMessage
import com.chatkit.compose.ChatScreen
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
            messages = messages,
            config = ChatConfig(showSenderNames = true, showDeliveryStatus = true),
            isTyping = isTyping,
            typingIndicatorText = "Aisha is typing…",
            onOptimisticMessage = { local ->
                if (messages.none { it.id == local.id }) {
                    messages += local
                }
            },
            onRetryMessage = { id ->
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0) {
                    messages[index] = messages[index].copy(deliveryStatus = DeliveryStatus.Pending)
                }
            },
            onVoiceRecorded = { _, _ ->
                // Host would upload here; sample leaves the optimistic uploading bubble.
            },
            onSubmit = { draft ->
                isTyping = false
                // Media/voice optimistic rows are already appended via onOptimisticMessage.
                // Only append a new host message for text/documents without a prior optimistic row.
                if (draft.media.isEmpty() && draft.documents.isEmpty()) {
                    val attachments = draft.documents.mapIndexed { index, uri ->
                        ChatAttachment(
                            id = uri.toString(),
                            fileName = uri.lastPathSegment ?: "document-$index",
                            mimeType = "application/octet-stream",
                            localUri = uri,
                        )
                    }
                    if (draft.text.isNotBlank() || attachments.isNotEmpty()) {
                        messages += ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = draft.text,
                            timestamp = Instant.now(),
                            direction = MessageDirection.Outgoing,
                            deliveryStatus = DeliveryStatus.Sent,
                            attachments = attachments,
                        )
                    }
                } else {
                    // Upgrade the latest optimistic media row to uploaded for the demo.
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
