package com.chatkit.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val CancelArmDp = 40.dp
private val InstantCancelDp = 110.dp
private val LockArmDp = 50.dp
private const val WaveformBarCount = 24

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
        animationSpec = tween(180),
        label = "mic-scale",
    )

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

                    var gestureCancelled = false
                    var cancelArmed = false
                    var lockArmed = false
                    var wasCancelArmed = false
                    var wasLockArmed = false
                    var dragX = 0f
                    var dragY = 0f

                    onGestureActiveChanged(true)
                    onDragOffsetChanged(0f)
                    onVerticalDragOffsetChanged(0f)
                    onCancelArmedChanged(false)
                    onLockArmedChanged(false)

                    if (!onPressStart()) {
                        onGestureActiveChanged(false)
                        return@awaitEachGesture
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            change.consume()
                            break
                        }
                        if (gestureCancelled) {
                            change.consume()
                            continue
                        }

                        val delta = change.positionChange()
                        dragX = (dragX + delta.x).coerceAtMost(0f)
                        dragY = (dragY + delta.y).coerceAtMost(0f)
                        change.consume()

                        val absX = abs(dragX)
                        val absY = abs(dragY)
                        val leftEnoughToCancel = dragX <= -cancelArmPx
                        val upEnoughToLock = dragY <= -lockArmPx
                        val cancelDominant = leftEnoughToCancel && absX >= absY * 0.65f
                        val lockDominant = upEnoughToLock && absY > absX && !cancelDominant

                        onDragOffsetChanged(dragX)
                        onVerticalDragOffsetChanged(dragY)
                        cancelArmed = cancelDominant
                        lockArmed = lockDominant
                        onCancelArmedChanged(cancelArmed)
                        onLockArmedChanged(lockArmed)

                        if (cancelArmed && !wasCancelArmed) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        if (lockArmed && !wasLockArmed) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        wasCancelArmed = cancelArmed
                        wasLockArmed = lockArmed

                        if (dragX <= -instantCancelPx) {
                            gestureCancelled = true
                            cancelArmed = true
                            onCancelArmedChanged(true)
                            onCancel()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            break
                        }
                    }

                    onDragOffsetChanged(0f)
                    onVerticalDragOffsetChanged(0f)
                    onCancelArmedChanged(false)
                    onLockArmedChanged(false)
                    onGestureActiveChanged(false)

                    if (gestureCancelled) return@awaitEachGesture
                    when {
                        cancelArmed -> {
                            onCancel()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        lockArmed -> {
                            onLock()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        else -> onFinish()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(theme.composerButtonBackgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.mic),
                contentDescription = null,
                tint = theme.composerIconColor,
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
                .background(theme.composerButtonBackgroundColor)
                .clickable(onClick = onSend)
                .semantics { contentDescription = "Send voice recording" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = theme.composerIconColor,
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
