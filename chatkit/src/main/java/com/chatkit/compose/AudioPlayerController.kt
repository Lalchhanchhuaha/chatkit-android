package com.chatkit.compose

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Conversation-scoped player which guarantees that only one voice attachment plays at a time. */
internal class AudioPlayerController(private val context: Context) {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            val mediaPlayer = player ?: return
            if (!isPlaying) return
            positionMillis = mediaPlayer.currentPosition.toLong().coerceAtLeast(0L)
            val total = mediaPlayer.duration.takeIf { it > 0 }?.toLong() ?: durationMillis
            if (total > 0L) {
                durationMillis = total
                progress = (positionMillis.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
            handler.postDelayed(this, 50L)
        }
    }

    var activeAttachmentId: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMillis: Long by mutableLongStateOf(0L)
        private set
    var durationMillis: Long by mutableLongStateOf(0L)
        private set
    var progress: Float by mutableFloatStateOf(0f)
        private set

    fun toggle(attachmentId: String, uri: Uri, knownDurationMillis: Long = 0L) {
        if (activeAttachmentId == attachmentId && player != null) {
            if (isPlaying) {
                player?.pause()
                isPlaying = false
                stopTicker()
            } else {
                player?.start()
                isPlaying = true
                startTicker()
            }
            return
        }
        release()
        if (knownDurationMillis > 0L) {
            durationMillis = knownDurationMillis
        }
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                mediaPlayer.setDataSource(context, uri)
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false
                    positionMillis = 0L
                    progress = 0f
                    it.seekTo(0)
                    stopTicker()
                }
                mediaPlayer.prepare()
                val preparedDuration = mediaPlayer.duration.takeIf { it > 0 }?.toLong()
                if (preparedDuration != null) {
                    durationMillis = preparedDuration
                }
                mediaPlayer.start()
                player = mediaPlayer
                activeAttachmentId = attachmentId
                isPlaying = true
                positionMillis = 0L
                progress = 0f
                startTicker()
            }
        }.onFailure { release() }
    }

    fun release() {
        stopTicker()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        activeAttachmentId = null
        isPlaying = false
        positionMillis = 0L
        durationMillis = 0L
        progress = 0f
    }

    private fun startTicker() {
        stopTicker()
        handler.post(progressTicker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(progressTicker)
    }
}
