package com.chatkit.compose

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Network state displayed by the state-driven [ChatScreen] overload. */
public enum class ChatConnectionState { Connected, Connecting, Disconnected }

/** Recoverable failure supplied by the host's repository or ViewModel. */
@Immutable
public data class ChatError(
    public val message: String,
    public val canRetry: Boolean = true,
)

/**
 * Complete render state for a conversation.
 *
 * This mirrors the bound/stateless split used by mature chat SDKs: a ViewModel or controller owns
 * this state, while ChatKit only renders it and emits [ChatAction] values.
 */
@Immutable
public data class ChatUiState(
    public val messages: List<ChatMessage> = emptyList(),
    public val connectionState: ChatConnectionState = ChatConnectionState.Connected,
    public val isInitialLoading: Boolean = false,
    public val canLoadPreviousMessages: Boolean = false,
    public val isLoadingPreviousMessages: Boolean = false,
    public val isTyping: Boolean = false,
    public val typingIndicatorText: String = "Typing…",
    public val emptyMessage: String = "No messages yet",
    public val error: ChatError? = null,
)

/** Every operation emitted by the state-driven chat screen. */
public sealed interface ChatAction {
    public data class Submit(public val draft: ChatDraft) : ChatAction
    public data class OptimisticMessage(public val message: ChatMessage) : ChatAction
    public data class RetryMessage(public val messageId: String) : ChatAction
    public data class EditMessage(public val messageId: String, public val text: String) : ChatAction
    public data class DeleteMessage(public val messageId: String) : ChatAction
    public data class CancelAttachmentUpload(public val attachment: ChatAttachment) : ChatAction
    public data class VoiceRecorded(public val uri: Uri, public val durationMillis: Long) : ChatAction
    public data object LoadPreviousMessages : ChatAction
    public data object RetryLoading : ChatAction
    public data object DismissError : ChatAction
}

/**
 * High-level chat surface for hosts that keep state in a ViewModel or controller.
 * Use the message-list [ChatScreen] overload when every callback should be wired separately.
 */
@Composable
public fun ChatScreen(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
    config: ChatConfig = ChatConfig(),
    colors: ChatColors = ChatDefaults.colors(),
    dimensions: ChatDimensions = ChatDimensions(),
    attachmentResolver: AttachmentResolver = AttachmentResolver.None,
    deliveryStatusContent: (@Composable (status: DeliveryStatus, onRetry: () -> Unit) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ConnectionBanner(state.connectionState)
        state.error?.let { error ->
            ErrorBanner(
                error = error,
                onRetry = { onAction(ChatAction.RetryLoading) },
                onDismiss = { onAction(ChatAction.DismissError) },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ChatScreen(
                messages = state.messages,
                modifier = Modifier.fillMaxSize(),
                config = config,
                colors = colors,
                dimensions = dimensions,
                attachmentResolver = attachmentResolver,
                isTyping = state.isTyping,
                typingIndicatorText = state.typingIndicatorText,
                onSubmit = { onAction(ChatAction.Submit(it)) },
                onVoiceRecorded = { uri, duration ->
                    onAction(ChatAction.VoiceRecorded(uri, duration))
                },
                onOptimisticMessage = { onAction(ChatAction.OptimisticMessage(it)) },
                onCancelAttachmentUpload = {
                    onAction(ChatAction.CancelAttachmentUpload(it))
                },
                onRetryMessage = { onAction(ChatAction.RetryMessage(it)) },
                onEditMessage = { id, text -> onAction(ChatAction.EditMessage(id, text)) },
                onDeleteMessage = { onAction(ChatAction.DeleteMessage(it)) },
                onLoadPreviousMessages = if (
                    state.canLoadPreviousMessages && !state.isLoadingPreviousMessages
                ) {
                    { onAction(ChatAction.LoadPreviousMessages) }
                } else {
                    null
                },
                deliveryStatusContent = deliveryStatusContent,
            )

            if (state.isInitialLoading && state.messages.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (!state.isInitialLoading && state.messages.isEmpty()) {
                Text(
                    text = state.emptyMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (state.isLoadingPreviousMessages) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionBanner(connectionState: ChatConnectionState) {
    val text = when (connectionState) {
        ChatConnectionState.Connected -> return
        ChatConnectionState.Connecting -> "Connecting…"
        ChatConnectionState.Disconnected -> "Waiting for network"
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ErrorBanner(error: ChatError, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(error.message, color = MaterialTheme.colorScheme.onErrorContainer)
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (error.canRetry) Button(onClick = onRetry) { Text("Retry") }
                Button(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
