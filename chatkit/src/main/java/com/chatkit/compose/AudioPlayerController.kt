package com.chatkit.compose

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Conversation-scoped player which guarantees that only one voice attachment plays at a time. */
internal class AudioPlayerController(private val context: Context) {
    private var player: MediaPlayer? = null

    var activeAttachmentId: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    fun toggle(attachmentId: String, uri: Uri) {
        if (activeAttachmentId == attachmentId && player != null) {
            if (isPlaying) {
                player?.pause()
                isPlaying = false
            } else {
                player?.start()
                isPlaying = true
            }
            return
        }
        release()
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                mediaPlayer.setDataSource(context, uri)
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false
                    it.seekTo(0)
                }
                mediaPlayer.prepare()
                mediaPlayer.start()
                player = mediaPlayer
                activeAttachmentId = attachmentId
                isPlaying = true
            }
        }.onFailure { release() }
    }

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        activeAttachmentId = null
        isPlaying = false
    }
}
