package com.chatkit.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class ChatCameraViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val allowsVideo: Boolean,
) : AndroidViewModel(application) {

    var cameraState: ChatCameraState by mutableStateOf(initialState())
        private set

    var captureMode: CaptureMode by mutableStateOf(
        savedStateHandle["captureMode"] ?: CaptureMode.PHOTO,
    )
        private set

    var caption: String by mutableStateOf(savedStateHandle["caption"] ?: "")
        private set

    var trimRange: VideoTrimRange by mutableStateOf(
        VideoTrimRange(
            startSeconds = savedStateHandle["trimStart"] ?: 0.0,
            endSeconds = savedStateHandle["trimEnd"] ?: 0.0,
        ),
    )
        private set

    var flashEnabled: Boolean by mutableStateOf(false)
        private set

    var isRecording: Boolean by mutableStateOf(false)
        private set

    var lensFacingFront: Boolean by mutableStateOf(false)
        private set

    var permissionDeniedPermanently: Boolean by mutableStateOf(false)
        private set

    var cameraUnavailable: Boolean by mutableStateOf(false)
        private set

    var submitInFlight: Boolean by mutableStateOf(false)
        private set

    private var abandonedFiles = mutableListOf<File>()

    init {
        if (!allowsVideo && captureMode == CaptureMode.VIDEO) {
            updateCaptureMode(CaptureMode.PHOTO)
        }
    }

    private fun initialState(): ChatCameraState {
        val path = savedStateHandle.get<String>("reviewPath")
        val id = savedStateHandle.get<String>("reviewId")
        val typeName = savedStateHandle.get<String>("reviewType")
        val duration = savedStateHandle.get<Double>("reviewDuration")
        if (path != null && id != null && typeName != null) {
            val file = File(path)
            if (file.exists()) {
                val type = runCatching { MediaType.valueOf(typeName) }.getOrDefault(MediaType.Photo)
                return ChatCameraState.Reviewing(
                    CapturedMedia(
                        id = id,
                        mediaType = type,
                        localFile = file,
                        durationSeconds = duration,
                        caption = savedStateHandle["caption"] ?: "",
                    ),
                )
            }
        }
        return ChatCameraState.RequestingPermission
    }

    fun onPermissionGranted() {
        if (cameraState is ChatCameraState.RequestingPermission) {
            cameraState = ChatCameraState.Live
        }
        permissionDeniedPermanently = false
    }

    fun onPermissionDenied(permanently: Boolean) {
        permissionDeniedPermanently = permanently
        cameraState = ChatCameraState.RequestingPermission
    }

    fun markCameraUnavailable() {
        cameraUnavailable = true
    }

    fun updateCaptureMode(mode: CaptureMode) {
        if (!allowsVideo && mode == CaptureMode.VIDEO) return
        captureMode = mode
        savedStateHandle["captureMode"] = mode
    }

    fun toggleFlash() {
        flashEnabled = !flashEnabled
    }

    fun toggleLensFacing() {
        lensFacingFront = !lensFacingFront
    }

    fun updateRecording(recording: Boolean) {
        isRecording = recording
    }

    fun updateCaption(value: String) {
        caption = value
        savedStateHandle["caption"] = value
        val reviewing = cameraState as? ChatCameraState.Reviewing ?: return
        cameraState = ChatCameraState.Reviewing(reviewing.capture.copy(caption = value))
    }

    fun updateTrim(startSeconds: Double, endSeconds: Double, totalSeconds: Double) {
        trimRange = clampTrimRange(startSeconds, endSeconds, totalSeconds)
        savedStateHandle["trimStart"] = trimRange.startSeconds
        savedStateHandle["trimEnd"] = trimRange.endSeconds
    }

    fun moveTrim(deltaSeconds: Double, totalSeconds: Double) {
        trimRange = moveTrimWindow(trimRange, deltaSeconds, totalSeconds)
        savedStateHandle["trimStart"] = trimRange.startSeconds
        savedStateHandle["trimEnd"] = trimRange.endSeconds
    }

    fun onPhotoCaptured(id: String, file: File) {
        enterReview(
            CapturedMedia(
                id = id,
                mediaType = MediaType.Photo,
                localFile = file,
                durationSeconds = null,
                caption = "",
            ),
        )
    }

    fun onVideoCaptured(id: String, file: File) {
        isRecording = false
        val duration = ChatCameraFiles.durationSeconds(file) ?: 0.0
        enterReview(
            CapturedMedia(
                id = id,
                mediaType = MediaType.Video,
                localFile = file,
                durationSeconds = duration,
                caption = "",
            ),
        )
        trimRange = VideoTrimRange(0.0, duration)
        savedStateHandle["trimStart"] = 0.0
        savedStateHandle["trimEnd"] = duration
    }

    private fun enterReview(capture: CapturedMedia) {
        caption = ""
        savedStateHandle["caption"] = ""
        savedStateHandle["reviewPath"] = capture.localFile.absolutePath
        savedStateHandle["reviewId"] = capture.id
        savedStateHandle["reviewType"] = capture.mediaType.name
        capture.durationSeconds?.let { savedStateHandle["reviewDuration"] = it }
            ?: savedStateHandle.remove<Double>("reviewDuration")
        cameraState = ChatCameraState.Reviewing(capture)
    }

    fun retake() {
        val reviewing = cameraState as? ChatCameraState.Reviewing
        reviewing?.let {
            ChatCameraFiles.deleteQuietly(it.capture.localFile)
            clearReviewSavedState()
        }
        caption = ""
        trimRange = VideoTrimRange(0.0, 0.0)
        savedStateHandle["caption"] = ""
        savedStateHandle["trimStart"] = 0.0
        savedStateHandle["trimEnd"] = 0.0
        cameraState = ChatCameraState.Live
    }

    fun cancelAndCleanup() {
        when (val state = cameraState) {
            is ChatCameraState.Reviewing -> ChatCameraFiles.deleteQuietly(state.capture.localFile)
            else -> Unit
        }
        abandonedFiles.forEach(ChatCameraFiles::deleteQuietly)
        abandonedFiles.clear()
        clearReviewSavedState()
    }

    fun trackAbandoned(file: File) {
        abandonedFiles += file
    }

    /**
     * Exports a trim when needed, then invokes [onReady] exactly once with the final capture.
     * Returns false if a submission is already in flight.
     */
    fun submit(
        onReady: (CapturedMedia) -> Unit,
    ): Boolean {
        if (submitInFlight) return false
        val reviewing = cameraState as? ChatCameraState.Reviewing ?: return false
        submitInFlight = true

        val capture = reviewing.capture.copy(caption = caption.trim())
        if (capture.mediaType != MediaType.Video) {
            clearReviewSavedState()
            onReady(capture)
            return true
        }

        val total = capture.durationSeconds ?: 0.0
        if (trimRange.isFullRange(total)) {
            clearReviewSavedState()
            onReady(capture.copy(durationSeconds = total))
            return true
        }

        cameraState = ChatCameraState.ExportingTrim
        viewModelScope.launch {
            val (id, outFile) = ChatCameraFiles.videoFile(getApplication<Application>().cacheDir)
            val ok = withContext(Dispatchers.IO) {
                VideoTrimExporter.export(
                    source = capture.localFile,
                    destination = outFile,
                    startSeconds = trimRange.startSeconds,
                    endSeconds = trimRange.endSeconds,
                )
            }
            if (!ok) {
                ChatCameraFiles.deleteQuietly(outFile)
                // Fall back to the original file if remux fails.
                clearReviewSavedState()
                onReady(capture)
                return@launch
            }
            ChatCameraFiles.deleteQuietly(capture.localFile)
            val duration = trimRange.durationSeconds
            clearReviewSavedState()
            onReady(
                CapturedMedia(
                    id = id,
                    mediaType = MediaType.Video,
                    localFile = outFile,
                    durationSeconds = duration,
                    caption = capture.caption,
                ),
            )
        }
        return true
    }

    private fun clearReviewSavedState() {
        savedStateHandle.remove<String>("reviewPath")
        savedStateHandle.remove<String>("reviewId")
        savedStateHandle.remove<String>("reviewType")
        savedStateHandle.remove<Double>("reviewDuration")
    }

    class Factory(
        private val application: Application,
        private val allowsVideo: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return ChatCameraViewModel(
                application = application,
                savedStateHandle = extras.createSavedStateHandle(),
                allowsVideo = allowsVideo,
            ) as T
        }
    }
}
