package com.chatkit.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun VoiceMicButton(
    theme: ChatTheme,
    enabled: Boolean,
    isActive: Boolean,
    onGestureActiveChanged: (Boolean) -> Unit,
    onLockArmedChanged: (Boolean) -> Unit,
    onDragOffsetChanged: (Float) -> Unit,
    onPressStart: () -> Boolean,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 80.dp.toPx() }
    val lockThresholdPx = with(density) { 70.dp.toPx() }

    Box(
        modifier = modifier
            .size(44.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Hold to record voice message"
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var cancelled = false
                    var lockArmed = false
                    var totalX = 0f
                    var totalY = 0f
                    onGestureActiveChanged(true)
                    onDragOffsetChanged(0f)
                    onLockArmedChanged(false)
                    if (!onPressStart()) {
                        onGestureActiveChanged(false)
                        return@awaitEachGesture
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            change.consume()
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        totalX = dx.coerceAtMost(0f)
                        totalY = dy.coerceAtMost(0f)
                        onDragOffsetChanged(totalX)
                        val vertical = abs(totalY) > abs(totalX)
                        lockArmed = vertical && totalY <= -lockThresholdPx
                        onLockArmedChanged(lockArmed)
                        if (!vertical && totalX <= -cancelThresholdPx) {
                            cancelled = true
                            onCancel()
                            change.consume()
                            break
                        }
                        change.consume()
                    }
                    onGestureActiveChanged(false)
                    onDragOffsetChanged(0f)
                    onLockArmedChanged(false)
                    if (cancelled) return@awaitEachGesture
                    if (lockArmed) {
                        onLock()
                    } else {
                        onFinish()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFFB3261E) else theme.accentColor),
            contentAlignment = Alignment.Center,
        ) {
            Text("●", color = theme.accentContentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun VoiceRecordingOverlay(
    theme: ChatTheme,
    durationMillis: Long,
    isLocked: Boolean,
    isLockArmed: Boolean,
    dragOffsetX: Float,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isLocked) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(theme.composerBarColor)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "🗑",
                color = Color(0xFFB3261E),
                fontSize = 20.sp,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onDiscard)
                    .padding(6.dp)
                    .semantics { contentDescription = "Discard voice recording" },
            )
            Text("🔒", color = theme.accentColor, fontSize = 14.sp)
            Text(
                formatVoiceDuration(durationMillis),
                color = theme.incomingTimestampColor,
                fontSize = 12.sp,
            )
            Box(Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(theme.accentColor)
                    .clickable(onClick = onSend)
                    .semantics { contentDescription = "Send voice recording" },
                contentAlignment = Alignment.Center,
            ) {
                Text("➤", color = theme.accentContentColor, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(theme.composerBarColor)
                .padding(horizontal = 10.dp)
                .offset { IntOffset((dragOffsetX * 0.12f).roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.Red))
            Text(
                formatVoiceDuration(durationMillis),
                color = theme.incomingTimestampColor,
                fontSize = 12.sp,
            )
            Box(Modifier.weight(1f))
            Text(
                "‹ Slide left to cancel",
                color = if (dragOffsetX <= -55f) Color(0xFFB3261E) else theme.incomingTimestampColor,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Text(
                if (isLockArmed) "🔓↑" else "🔒↑",
                color = if (isLockArmed) theme.accentColor else theme.incomingTimestampColor,
                fontSize = 12.sp,
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
            delay(100)
        }
    }
    return duration
}

internal fun formatVoiceDuration(durationMillis: Long): String {
    val seconds = (durationMillis / 1000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
