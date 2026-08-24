package com.chatkit.compose

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    val durationMillis: Long
        get() = if (isRecording) (System.currentTimeMillis() - startedAt).coerceAtLeast(0L) else 0L

    /**
     * Normalized meter level `0.08…1` matching iOS ChatKit (`linearPower * 4`, floor 0.08).
     * Uses [MediaRecorder.getMaxAmplitude] (0…32767).
     */
    val meterLevel: Float
        get() {
            val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            val normalized = (amp / 32767f).coerceIn(0f, 1f)
            return maxOf(0.08f, minOf(1f, normalized * 4f))
        }

    fun start(): Boolean = runCatching {
        val file = File.createTempFile("chatkit-voice-", ".m4a", context.cacheDir)
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        output = file
        recorder = mediaRecorder
        startedAt = System.currentTimeMillis()
        true
    }.getOrElse {
        release(deleteOutput = true)
        false
    }

    fun finish(): Recording? {
        val file = output ?: return null
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        return runCatching {
            recorder?.stop()
            release(deleteOutput = false)
            Recording(Uri.fromFile(file), duration)
        }.getOrElse {
            release(deleteOutput = true)
            null
        }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        release(deleteOutput = true)
    }

    private fun release(deleteOutput: Boolean) {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        if (deleteOutput) output?.delete()
        output = null
        startedAt = 0L
    }

    data class Recording(val uri: Uri, val durationMillis: Long)
}
