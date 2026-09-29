package com.chatkit.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val CancelArmDp = 40.dp
private val InstantCancelDp = 110.dp
private val LockArmDp = 50.dp
private const val WaveformBarCount = 24

internal data class VoiceGestureDecision(
    val dragX: Float,
    val dragY: Float,
    val cancelArmed: Boolean,
    val lockArmed: Boolean,
    val cancelsImmediately: Boolean,
)

/** iOS ChatKit's direction-aware cancel/lock thresholds, kept pure for regression tests. */
internal fun voiceGestureDecision(
    dragX: Float,
    dragY: Float,
    cancelArmPx: Float,
    lockArmPx: Float,
    instantCancelPx: Float,
): VoiceGestureDecision {
    val horizontal = dragX.coerceAtMost(0f)
    val vertical = dragY.coerceAtMost(0f)
    val absX = abs(horizontal)
    val absY = abs(vertical)
    val cancelDominant = horizontal <= -cancelArmPx && absX >= absY * 0.65f
    val lockDominant = vertical <= -lockArmPx && absY > absX && !cancelDominant
    return VoiceGestureDecision(
        dragX = horizontal,
        dragY = vertical,
        cancelArmed = cancelDominant,
        lockArmed = lockDominant,
        cancelsImmediately = horizontal <= -instantCancelPx,
    )
}

/**
 * Trailing mic control matching iOS ChatKit:
 * 36dp accent circle inside a 44dp hit target; scales to 1.16 while recording.
 *
 * Drag uses [positionChange] accumulation so mid-gesture composer layout swaps
 * (idle → unlocked status) cannot corrupt slide-to-cancel / slide-to-lock deltas.
 */
@Composable
internal fun VoiceMicButton(
    theme: ChatTheme,
    enabled: Boolean,
    isActive: Boolean,
    onGestureActiveChanged: (Boolean) -> Unit,
    onCancelArmedChanged: (Boolean) -> Unit,
    onLockArmedChanged: (Boolean) -> Unit,
    onDragOffsetChanged: (Float) -> Unit,
    onVerticalDragOffsetChanged: (Float) -> Unit,
    onPressStart: () -> Boolean,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val cancelArmPx = with(density) { CancelArmDp.toPx() }
    val instantCancelPx = with(density) { InstantCancelDp.toPx() }
    val lockArmPx = with(density) { LockArmDp.toPx() }
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.16f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 650f),
        label = "mic-scale",
    )
    val currentOnGestureActiveChanged by rememberUpdatedState(onGestureActiveChanged)
    val currentOnCancelArmedChanged by rememberUpdatedState(onCancelArmedChanged)
    val currentOnLockArmedChanged by rememberUpdatedState(onLockArmedChanged)
    val currentOnDragOffsetChanged by rememberUpdatedState(onDragOffsetChanged)
    val currentOnVerticalDragOffsetChanged by rememberUpdatedState(onVerticalDragOffsetChanged)
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnLock by rememberUpdatedState(onLock)
    val currentOnFinish by rememberUpdatedState(onFinish)

    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                role = Role.Button
                contentDescription = if (isActive) {
                    "Recording voice message"
                } else {
                    "Hold to record voice message"
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    var recordingStarted = false
                    var actionCompleted = false
                    var releasedNormally = false
                    var decision = voiceGestureDecision(
                        dragX = 0f,
                        dragY = 0f,
                        cancelArmPx = cancelArmPx,
                        lockArmPx = lockArmPx,
                        instantCancelPx = instantCancelPx,
                    )
                    var wasCancelArmed = false
                    var wasLockArmed = false
                    var dragX = 0f
                    var dragY = 0f

                    currentOnGestureActiveChanged(true)
                    currentOnDragOffsetChanged(0f)
                    currentOnVerticalDragOffsetChanged(0f)
                    currentOnCancelArmedChanged(false)
                    currentOnLockArmedChanged(false)

                    try {
                        recordingStarted = currentOnPressStart()
                        if (!recordingStarted) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            if (!change.pressed) {
                                releasedNormally = change.changedToUp()
                                change.consume()
                                break
                            }

                            val delta = change.positionChange()
                            dragX += delta.x
                            dragY += delta.y
                            change.consume()

                            decision = voiceGestureDecision(
                                dragX = dragX,
                                dragY = dragY,
                                cancelArmPx = cancelArmPx,
                                lockArmPx = lockArmPx,
                                instantCancelPx = instantCancelPx,
                            )
                            currentOnDragOffsetChanged(decision.dragX)
                            currentOnVerticalDragOffsetChanged(decision.dragY)
                            currentOnCancelArmedChanged(decision.cancelArmed)
                            currentOnLockArmedChanged(decision.lockArmed)

                            if (decision.cancelArmed && !wasCancelArmed) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            if (decision.lockArmed && !wasLockArmed) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            wasCancelArmed = decision.cancelArmed
                            wasLockArmed = decision.lockArmed

                            if (decision.cancelsImmediately) {
                                currentOnCancelArmedChanged(true)
                                currentOnCancel()
                                actionCompleted = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                break
                            }
                        }

                        if (!actionCompleted) {
                            when {
                                !releasedNormally -> currentOnCancel()
                                decision.cancelArmed -> {
                                    currentOnCancel()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                decision.lockArmed -> {
                                    currentOnLock()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                else -> currentOnFinish()
                            }
                            actionCompleted = true
                        }
                    } catch (cancelled: CancellationException) {
                        if (recordingStarted && !actionCompleted) {
                            currentOnCancel()
                        }
                        throw cancelled
                    } finally {
                        currentOnDragOffsetChanged(0f)
                        currentOnVerticalDragOffsetChanged(0f)
                        currentOnCancelArmedChanged(false)
                        currentOnLockArmedChanged(false)
                        currentOnGestureActiveChanged(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(theme.accentColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.mic),
                contentDescription = null,
                tint = theme.accentContentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Unlocked hold-to-record status row (iOS `unlockedVoiceRecordingStatus`). */
@Composable
internal fun UnlockedVoiceRecordingStatus(
    theme: ChatTheme,
    durationMillis: Long,
    level: Float,
    isCancelArmed: Boolean,
    dragOffsetX: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val slidePx = max(dragOffsetX * 0.55f, with(density) { (-92).dp.toPx() })

    // Clip so slide-to-cancel never draws under the trailing mic.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clipToBounds(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .graphicsLayer {
                    alpha = if (isCancelArmed) 0.72f else 1f
                    translationX = slidePx
                }
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.Red),
            )
            Text(
                text = formatVoiceDuration(durationMillis),
                color = theme.incomingTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
            // width(0) + weight forces the waveform to take only leftover space
            // so duration / cancel text cannot overlap it.
            VoiceWaveform(
                level = level,
                timeSeconds = durationMillis / 1000.0,
                color = theme.accentColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(24.dp)
                    .widthIn(min = 0.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.widthIn(max = 128.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = if (isCancelArmed) Color.Red else theme.incomingTimestampColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Slide left to cancel",
                    color = if (isCancelArmed) Color.Red else theme.incomingTimestampColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Locked recording bar (iOS `lockedVoiceRecordingStatus`). */
@Composable
internal fun LockedVoiceRecordingStatus(
    theme: ChatTheme,
    durationMillis: Long,
    level: Float,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onDiscard)
                .semantics { contentDescription = "Discard voice recording" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(22.dp),
            )
        }
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Recording locked",
            tint = theme.accentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = formatVoiceDuration(durationMillis),
            color = theme.incomingTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        VoiceWaveform(
            level = level,
            timeSeconds = durationMillis / 1000.0,
            color = theme.accentColor,
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .widthIn(min = 0.dp),
        )
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(theme.accentColor)
                .clickable(onClick = onSend)
                .semantics { contentDescription = "Send voice recording" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = theme.accentContentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Floating pad above the mic (iOS `voiceSlideToLockPad`). */
@Composable
internal fun VoiceSlideToLockPad(
    theme: ChatTheme,
    isLockArmed: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(180)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.92f, animationSpec = tween(120)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .shadow(10.dp, RoundedCornerShape(50), ambientColor = Color.Black.copy(alpha = 0.16f))
                .clip(RoundedCornerShape(50))
                .background(theme.composerBarColor)
                .padding(horizontal = 10.dp)
                .padding(top = 10.dp, bottom = 12.dp)
                .semantics {
                    contentDescription = if (isLockArmed) {
                        "Release to lock recording"
                    } else {
                        "Slide up to lock recording"
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f))
                    .clip(CircleShape)
                    .background(if (isLockArmed) theme.accentColor else theme.composerBarColor)
                    .then(
                        if (isLockArmed) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, theme.accentColor.copy(alpha = 0.18f), CircleShape)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isLockArmed) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (isLockArmed) theme.accentContentColor else theme.accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = if (isLockArmed) {
                    theme.accentColor.copy(alpha = 0.45f)
                } else {
                    theme.incomingTimestampColor
                },
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { alpha = if (isLockArmed) 0.55f else 1f },
            )
        }
    }
}

@Composable
private fun VoiceWaveform(
    level: Float,
    timeSeconds: Double,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        val spacing = 2.dp.toPx()
        val barWidth = 2.dp.toPx()
        val barCount = WaveformBarCount
        val totalBarsWidth = barCount * barWidth + (barCount - 1) * spacing
        val startX = 0f
        val midY = size.height / 2f
        val clampedLevel = max(0.15f, level)
        val available = size.width
        val scaleX = if (totalBarsWidth > available && totalBarsWidth > 0f) {
            available / totalBarsWidth
        } else {
            1f
        }
        for (index in 0 until barCount) {
            val phase = sin((index * 0.72) + (timeSeconds * 9))
            val normalizedPhase = ((phase + 1.0) / 2.0).toFloat()
            val barHeight = (4.dp.toPx() + (18.dp.toPx() * clampedLevel * normalizedPhase))
                .coerceAtMost(size.height)
            val left = startX + index * (barWidth + spacing) * scaleX
            val drawnWidth = barWidth * scaleX
            drawRoundRect(
                color = color,
                topLeft = Offset(left, midY - barHeight / 2f),
                size = Size(drawnWidth, barHeight),
                cornerRadius = CornerRadius(drawnWidth / 2f, drawnWidth / 2f),
            )
        }
    }
}

@Composable
internal fun rememberVoiceDurationMillis(isRecording: Boolean, recorder: VoiceRecorder): Long {
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            duration = 0L
            return@LaunchedEffect
        }
        while (true) {
            duration = recorder.durationMillis
            delay(80)
        }
    }
    return duration
}

@Composable
internal fun rememberVoiceLevel(isRecording: Boolean, recorder: VoiceRecorder): Float {
    var level by remember { mutableFloatStateOf(0.08f) }
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            level = 0.08f
            return@LaunchedEffect
        }
        while (true) {
            level = recorder.meterLevel
            delay(80)
        }
    }
    return level
}

internal fun formatVoiceDuration(durationMillis: Long): String {
    val seconds = (durationMillis / 1000L).toInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** iOS `voiceLockPadLift` magnitude in px. */
internal fun voiceLockPadLiftPx(verticalDragOffsetPx: Float, density: androidx.compose.ui.unit.Density): Float {
    val travel = abs(min(verticalDragOffsetPx, 0f))
    return min(travel * 0.35f, with(density) { 28.dp.toPx() })
}

/** iOS overlays the pad on the full chat with bottom = 66 + lift above the mic. */
internal fun voiceLockPadBottomPadding(liftPx: Float, density: androidx.compose.ui.unit.Density): Dp {
    return 66.dp + with(density) { liftPx.toDp() }
}
