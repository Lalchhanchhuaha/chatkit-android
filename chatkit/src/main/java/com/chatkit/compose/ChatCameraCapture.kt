package com.chatkit.compose

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Full-screen chat camera: live capture with CameraX, then inline review with caption/send.
 * Submission is delivered only after the dialog dismisses.
 */
@Composable
internal fun ChatCameraCaptureDialog(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    sessionKey: Int,
    onDismiss: () -> Unit,
    onCaptured: (CapturedMedia) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ChatCameraViewModel = viewModel(
        key = "chat-camera-$sessionKey",
        factory = ChatCameraViewModel.Factory(application, showsVideoAttachments),
    )
    var dialogVisible by remember(sessionKey) { mutableStateOf(true) }
    var pendingSubmit by remember(sessionKey) { mutableStateOf<CapturedMedia?>(null) }
    val submitted = remember(sessionKey) { AtomicBoolean(false) }

    if (dialogVisible) {
        Dialog(
            onDismissRequest = {
                viewModel.cancelAndCleanup()
                dialogVisible = false
                onDismiss()
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            ChatCameraDestination(
                theme = theme,
                showsVideoAttachments = showsVideoAttachments,
                viewModel = viewModel,
                onClose = {
                    viewModel.cancelAndCleanup()
                    dialogVisible = false
                    onDismiss()
                },
                onSubmitCapture = { capture ->
                    if (!submitted.compareAndSet(false, true)) return@ChatCameraDestination
                    pendingSubmit = capture
                    dialogVisible = false
                },
            )
        }
    }

    // Deliver after the dialog is gone so teardown cannot re-enter the capture callback.
    LaunchedEffect(sessionKey, dialogVisible, pendingSubmit) {
        val capture = pendingSubmit ?: return@LaunchedEffect
        if (dialogVisible) return@LaunchedEffect
        onCaptured(capture)
        pendingSubmit = null
        onDismiss()
    }
}

@Composable
private fun ChatCameraDestination(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    viewModel: ChatCameraViewModel,
    onClose: () -> Unit,
    onSubmitCapture: (CapturedMedia) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(dialogWindow) {
        val previousMode = dialogWindow?.attributes?.softInputMode
        // Edge-to-edge dialog still needs adjustResize so IME insets are dispatched.
        dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val controller = dialogWindow?.let { WindowInsetsControllerCompat(it, view) }
        val previousAppearance = controller?.isAppearanceLightStatusBars
        if (dialogWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
        }
        onDispose {
            if (previousMode != null) {
                dialogWindow.setSoftInputMode(previousMode)
            }
            if (previousAppearance != null) {
                controller.isAppearanceLightStatusBars = previousAppearance
                controller.isAppearanceLightNavigationBars = previousAppearance
            }
        }
    }

    // Keep activity bars consistent when the dialog is shown.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val previousAppearance = controller?.isAppearanceLightStatusBars
        WindowCompat.setDecorFitsSystemWindows(window ?: return@DisposableEffect onDispose {}, false)
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (previousAppearance != null) {
                controller.isAppearanceLightStatusBars = previousAppearance
                controller.isAppearanceLightNavigationBars = previousAppearance
            }
        }
    }

    BackHandler {
        when (viewModel.cameraState) {
            is ChatCameraState.Reviewing, ChatCameraState.ExportingTrim -> viewModel.retake()
            else -> onClose()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            val permanently = activity?.let {
                !it.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            } == true
            viewModel.onPermissionDenied(permanently)
        }
    }

    LaunchedEffect(Unit) {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> viewModel.onPermissionGranted()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val state = viewModel.cameraState) {
            ChatCameraState.RequestingPermission -> {
                if (viewModel.permissionDeniedPermanently) {
                    CameraAccessNeededDialog(
                        onSettings = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                            onClose()
                        },
                        onCancel = onClose,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            ChatCameraState.Live -> {
                LiveCameraScreen(
                    theme = theme,
                    showsVideoAttachments = showsVideoAttachments,
                    viewModel = viewModel,
                    onClose = onClose,
                )
            }
            is ChatCameraState.Reviewing -> {
                ReviewScreen(
                    theme = theme,
                    capture = state.capture,
                    viewModel = viewModel,
                    onRetake = { viewModel.retake() },
                    onSend = {
                        viewModel.submit(onSubmitCapture)
                    },
                )
            }
            ChatCameraState.ExportingTrim -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = theme.accentColor)
                        Spacer(Modifier.height(12.dp))
                        Text("Preparing video…", color = Color.White)
                    }
                }
            }
        }

        if (viewModel.cameraUnavailable) {
            CameraUnavailableDialog(onCancel = onClose)
        }
    }
}

@Composable
private fun LiveCameraScreen(
    theme: ChatTheme,
    showsVideoAttachments: Boolean,
    viewModel: ChatCameraViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var rebinding by remember { mutableStateOf(false) }
    var torchSupported by remember { mutableStateOf(false) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var hasUltraWide by remember { mutableStateOf(false) }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* silent video allowed when denied */ }

    fun bindCamera(provider: ProcessCameraProvider, preview: PreviewView) {
        if (rebinding) return
        rebinding = true
        try {
            activeRecording?.stop()
            activeRecording = null
            viewModel.updateRecording(false)
            provider.unbindAll()

            val selector = if (viewModel.lensFacingFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val photoMode = viewModel.captureMode == CaptureMode.PHOTO
            // Match iOS: 4:3 photo / 16:9 video use cases (portrait viewfinder is 3:4 / 9:16).
            val sensorAspect = if (photoMode) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(
                        sensorAspect,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO,
                    ),
                )
                .build()
            val previewUseCase = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.surfaceProvider = preview.surfaceProvider }

            val groupBuilder = UseCaseGroup.Builder().addUseCase(previewUseCase)
            // Share PreviewView's viewport so capture is cropped to exactly what is shown.
            preview.viewPort?.let(groupBuilder::setViewPort)

            if (photoMode) {
                val image = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                imageCapture = image
                videoCapture = null
                groupBuilder.addUseCase(image)
            } else {
                imageCapture = null
                if (showsVideoAttachments) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    val video = VideoCapture.withOutput(recorder)
                    videoCapture = video
                    groupBuilder.addUseCase(video)
                } else {
                    videoCapture = null
                }
            }

            val bound = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                groupBuilder.build(),
            )
            camera = bound
            torchSupported = bound.cameraInfo.hasFlashUnit()
            val zoomState = bound.cameraInfo.zoomState.value
            minZoom = zoomState?.minZoomRatio ?: 1f
            maxZoom = (zoomState?.maxZoomRatio ?: 1f).coerceAtMost(8f)
            currentZoom = zoomState?.zoomRatio ?: 1f
            hasUltraWide = minZoom <= 0.6f
            // Prefer normal 1x when available.
            if (!viewModel.lensFacingFront && abs(currentZoom - 1f) > 0.05f && minZoom <= 1f && maxZoom >= 1f) {
                runCatching { bound.cameraControl.setZoomRatio(1f) }
                currentZoom = 1f
            }
            if (viewModel.flashEnabled && torchSupported && viewModel.captureMode == CaptureMode.PHOTO) {
                // Photo flash is applied per capture; torch used for video.
            }
        } catch (_: Exception) {
            viewModel.markCameraUnavailable()
        } finally {
            rebinding = false
        }
    }

    LaunchedEffect(previewView, viewModel.lensFacingFront, viewModel.captureMode, showsVideoAttachments) {
        val preview = previewView ?: return@LaunchedEffect
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        // Wait until PreviewView has a real size so viewPort matches the on-screen frame.
        preview.post {
            bindCamera(provider, preview)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeRecording?.stop()
            activeRecording = null
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            cameraExecutor.shutdown()
        }
    }

    val photoMode = viewModel.captureMode == CaptureMode.PHOTO
    // Portrait viewfinder ratios matching iOS ChatKit (sensor 4:3 / 16:9).
    val viewfinderAspectRatio = if (photoMode) 3f / 4f else 9f / 16f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraChromeButton(
                contentDescription = "Close",
                onClick = {
                    activeRecording?.stop()
                    onClose()
                },
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (torchSupported) {
                CameraChromeButton(
                    contentDescription = if (viewModel.flashEnabled) "Flash on" else "Flash off",
                    onClick = {
                        viewModel.toggleFlash()
                        val cam = camera
                        if (viewModel.isRecording && cam != null && cam.cameraInfo.hasFlashUnit()) {
                            runCatching {
                                cam.cameraControl.enableTorch(viewModel.flashEnabled)
                            }
                        }
                    },
                ) {
                    Icon(
                        if (viewModel.flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CameraChromeButton(
                contentDescription = "Flip camera",
                onClick = {
                    if (!rebinding && !viewModel.isRecording) {
                        viewModel.toggleLensFacing()
                    }
                },
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = null, tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // Container is already 3:4 / 9:16; FILL + shared ViewPort makes
                        // the JPEG/MP4 match the visible viewfinder.
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val scaleDetector = ScaleGestureDetector(
                            ctx,
                            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                override fun onScale(detector: ScaleGestureDetector): Boolean {
                                    val cam = camera ?: return false
                                    val next = (currentZoom * detector.scaleFactor)
                                        .coerceIn(minZoom, maxZoom)
                                    runCatching { cam.cameraControl.setZoomRatio(next) }
                                    currentZoom = next
                                    return true
                                }
                            },
                        )
                        setOnTouchListener { v, event ->
                            scaleDetector.onTouchEvent(event)
                            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                                val cam = camera ?: return@setOnTouchListener false
                                val factory = meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                runCatching { cam.cameraControl.startFocusAndMetering(action) }
                                v.performClick()
                            }
                            true
                        }
                        previewView = this
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(viewfinderAspectRatio)
                    .semantics { contentDescription = "Camera viewfinder" },
            )

            if (hasUltraWide && !viewModel.lensFacingFront) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LensChip(
                        label = "0.5×",
                        selected = currentZoom <= 0.7f,
                        onClick = {
                            camera?.let {
                                runCatching { it.cameraControl.setZoomRatio(minZoom.coerceAtMost(0.5f)) }
                                currentZoom = minZoom.coerceAtMost(0.5f)
                            }
                        },
                    )
                    LensChip(
                        label = "1×",
                        selected = currentZoom in 0.85f..1.25f,
                        onClick = {
                            camera?.let {
                                runCatching { it.cameraControl.setZoomRatio(1f) }
                                currentZoom = 1f
                            }
                        },
                    )
                }
            }
        }

        if (showsVideoAttachments) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeChip(
                    label = "Photo",
                    selected = viewModel.captureMode == CaptureMode.PHOTO,
                    enabled = !viewModel.isRecording && !rebinding,
                    onClick = { viewModel.updateCaptureMode(CaptureMode.PHOTO) },
                )
                ModeChip(
                    label = "Video",
                    selected = viewModel.captureMode == CaptureMode.VIDEO,
                    enabled = !viewModel.isRecording && !rebinding,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        viewModel.updateCaptureMode(CaptureMode.VIDEO)
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            ShutterButton(
                isVideoMode = viewModel.captureMode == CaptureMode.VIDEO,
                isRecording = viewModel.isRecording,
                onClick = {
                    if (rebinding) return@ShutterButton
                    when (viewModel.captureMode) {
                        CaptureMode.PHOTO -> {
                            val capture = imageCapture ?: return@ShutterButton
                            val (id, file) = ChatCameraFiles.photoFile(context.cacheDir)
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            if (viewModel.flashEnabled && torchSupported) {
                                capture.flashMode = ImageCapture.FLASH_MODE_ON
                            } else {
                                capture.flashMode = ImageCapture.FLASH_MODE_OFF
                            }
                            capture.takePicture(
                                options,
                                cameraExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(
                                        outputFileResults: ImageCapture.OutputFileResults,
                                    ) {
                                        scope.launch(Dispatchers.Main) {
                                            viewModel.onPhotoCaptured(id, file)
                                            runCatching {
                                                ProcessCameraProvider.getInstance(context).get()
                                                    .unbindAll()
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        ChatCameraFiles.deleteQuietly(file)
                                    }
                                },
                            )
                        }
                        CaptureMode.VIDEO -> {
                            val recording = activeRecording
                            if (recording != null) {
                                recording.stop()
                                return@ShutterButton
                            }
                            val capture = videoCapture ?: return@ShutterButton
                            val (id, file) = ChatCameraFiles.videoFile(context.cacheDir)
                            val pending = AtomicBoolean(true)
                            var builder = capture.output
                                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                builder = builder.withAudioEnabled()
                            }
                            activeRecording = builder.start(mainExecutor) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> {
                                        viewModel.updateRecording(true)
                                        val cam = camera
                                        if (viewModel.flashEnabled &&
                                            cam != null &&
                                            cam.cameraInfo.hasFlashUnit()
                                        ) {
                                            runCatching { cam.cameraControl.enableTorch(true) }
                                        }
                                    }
                                    is VideoRecordEvent.Finalize -> {
                                        viewModel.updateRecording(false)
                                        activeRecording = null
                                        runCatching { camera?.cameraControl?.enableTorch(false) }
                                        if (event.hasError()) {
                                            ChatCameraFiles.deleteQuietly(file)
                                        } else if (pending.compareAndSet(true, false)) {
                                            viewModel.onVideoCaptured(id, file)
                                            runCatching {
                                                ProcessCameraProvider.getInstance(context).get()
                                                    .unbindAll()
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ReviewScreen(
    theme: ChatTheme,
    capture: CapturedMedia,
    viewModel: ChatCameraViewModel,
    onRetake: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val sendEnabled = !viewModel.submitInFlight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            // Lift the caption/send row above the keyboard without double-counting nav bars.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onRetake,
                modifier = Modifier.semantics { contentDescription = "Retake" },
            ) {
                Text("Retake", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (capture.mediaType) {
                MediaType.Photo -> {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, capture.localFile) {
                        value = withContext(Dispatchers.IO) {
                            decodeBitmapRespectingExif(
                                context,
                                capture.localFile.toUri(),
                                maxSide = 2048,
                            )
                        }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Captured photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                MediaType.Video -> {
                    VideoReviewPlayer(
                        file = capture.localFile,
                        trimRange = viewModel.trimRange,
                        totalSeconds = capture.durationSeconds ?: 0.0,
                        onTrimChanged = { start, end ->
                            viewModel.updateTrim(start, end, capture.durationSeconds ?: 0.0)
                        },
                        onTrimMoved = { delta ->
                            viewModel.moveTrim(delta, capture.durationSeconds ?: 0.0)
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = viewModel.caption,
                    onValueChange = viewModel::updateCaption,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(theme.accentColor),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (viewModel.caption.isEmpty()) {
                            Text("Write a message…", color = Color.White.copy(alpha = 0.55f))
                        }
                        inner()
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (sendEnabled) theme.accentColor else theme.accentColor.copy(alpha = 0.45f))
                    .semantics {
                        role = Role.Button
                        contentDescription = "Send"
                    }
                    .clickable(enabled = sendEnabled, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = theme.accentContentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoReviewPlayer(
    file: java.io.File,
    trimRange: VideoTrimRange,
    totalSeconds: Double,
    onTrimChanged: (Double, Double) -> Unit,
    onTrimMoved: (Double) -> Unit,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(file) {
        onDispose { player.release() }
    }

    LaunchedEffect(trimRange.startSeconds, trimRange.endSeconds) {
        val startMs = (trimRange.startSeconds * 1000).toLong()
        val endMs = (trimRange.endSeconds * 1000).toLong()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build(),
                )
                .build(),
        )
        player.prepare()
        player.seekTo(0)
        isPlaying = false
    }

    val frames by produceState(emptyList(), file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            VideoTrimExporter.filmstripFrames(file, frameCount = 10)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isPlaying) "Pause video" else "Play video"
                    }
                    .clickable {
                        if (player.isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            if (player.playbackState == Player.STATE_ENDED) {
                                player.seekTo(0)
                            }
                            player.play()
                            isPlaying = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Text(
            text = formatDuration(trimRange.durationSeconds),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        if (totalSeconds > 0.0) {
            VideoTrimBar(
                frames = frames,
                totalSeconds = totalSeconds,
                range = trimRange,
                onTrimChanged = onTrimChanged,
                onTrimMoved = onTrimMoved,
                onSeekBoundary = { seconds ->
                    player.seekTo((seconds * 1000).toLong().coerceAtLeast(0L))
                    isPlaying = false
                    player.pause()
                },
                trimModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun VideoTrimBar(
    frames: List<android.graphics.Bitmap>,
    totalSeconds: Double,
    range: VideoTrimRange,
    onTrimChanged: (Double, Double) -> Unit,
    onTrimMoved: (Double) -> Unit,
    onSeekBoundary: (Double) -> Unit,
    trimModifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = trimModifier.height(56.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val startFraction = (range.startSeconds / totalSeconds).toFloat().coerceIn(0f, 1f)
        val endFraction = (range.endSeconds / totalSeconds).toFloat().coerceIn(0f, 1f)

        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxSize(),
        ) {
            if (frames.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray),
                )
            } else {
                for (frame in frames) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(start = maxWidth * startFraction, end = maxWidth * (1f - endFraction))
                .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                .pointerInput(totalSeconds, range) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = (dragAmount.x / widthPx) * totalSeconds
                        onTrimMoved(delta)
                    }
                },
        )

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = (maxWidth * startFraction - 12.dp).coerceAtLeast(0.dp))
                .size(width = 24.dp, height = 56.dp)
                .semantics { contentDescription = "Trim start" }
                .pointerInput(totalSeconds, range.endSeconds) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = ((change.position.x + size.width / 2f) / widthPx)
                            .coerceIn(0f, 1f)
                        val seconds = fraction * totalSeconds
                        onTrimChanged(seconds, range.endSeconds)
                        onSeekBoundary(seconds)
                    }
                }
                .background(Color.White, RoundedCornerShape(4.dp)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = (maxWidth * endFraction - 12.dp).coerceAtLeast(0.dp))
                .size(width = 24.dp, height = 56.dp)
                .semantics { contentDescription = "Trim end" }
                .pointerInput(totalSeconds, range.startSeconds) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = ((change.position.x + size.width / 2f) / widthPx)
                            .coerceIn(0f, 1f)
                        val seconds = fraction * totalSeconds
                        onTrimChanged(range.startSeconds, seconds)
                        onSeekBoundary(seconds)
                    }
                }
                .background(Color.White, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun ShutterButton(
    isVideoMode: Boolean,
    isRecording: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Shutter"
            }
            .clickable(onClick = onClick)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isRecording) 28.dp else 56.dp)
                .clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape)
                .background(
                    when {
                        isRecording -> Color.Red
                        isVideoMode -> Color.Red
                        else -> Color.White
                    },
                ),
        )
    }
}

@Composable
private fun CameraChromeButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun LensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Yellow else Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun CameraAccessNeededDialog(onSettings: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Camera Access Needed") },
        text = { Text("Allow camera access in Settings to take photos and videos.") },
        confirmButton = { TextButton(onClick = onSettings) { Text("Settings") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CameraUnavailableDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Camera Unavailable") },
        text = { Text("The camera is unavailable on this device right now.") },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
