package com.chatkit.compose

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AtomicFile
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NormalizedCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private enum class CropDragTarget { Move, TopLeft, TopRight, BottomLeft, BottomRight }

private data class CropAspect(val label: String, val ratio: Float?)

private val CropAspects = listOf(
    CropAspect("Free", null),
    CropAspect("1:1", 1f),
    CropAspect("4:3", 4f / 3f),
    CropAspect("16:9", 16f / 9f),
)

@Composable
internal fun PhotoCropEditor(
    source: Bitmap,
    destination: File,
    accentColor: Color,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    var bitmap by remember(source) { mutableStateOf(source) }
    var crop by remember(bitmap) { mutableStateOf(NormalizedCrop(0f, 0f, 1f, 1f)) }
    var selectedAspect by remember { mutableStateOf(CropAspects.first()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            saveCroppedPhoto(bitmap, crop, destination)
                        }
                        saving = false
                        if (saved) onSaved()
                    }
                },
            ) { Text("Done", color = accentColor) }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Crop photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            CropOverlay(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                crop = crop,
                aspectRatio = selectedAspect.ratio,
                onCropChanged = { crop = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (saving) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = accentColor)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !saving,
                onClick = {
                    bitmap = rotateBitmapClockwise(bitmap)
                    crop = NormalizedCrop(0f, 0f, 1f, 1f)
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = "Rotate clockwise",
                    tint = Color.White,
                )
            }
            CropAspects.forEach { choice ->
                TextButton(
                    enabled = !saving,
                    onClick = {
                        selectedAspect = choice
                        crop = choice.ratio?.let {
                            centeredCropForAspect(it, bitmap.width, bitmap.height)
                        } ?: NormalizedCrop(0f, 0f, 1f, 1f)
                    },
                ) {
                    Text(
                        choice.label,
                        color = if (choice == selectedAspect) accentColor else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun CropOverlay(
    bitmapWidth: Int,
    bitmapHeight: Int,
    crop: NormalizedCrop,
    aspectRatio: Float?,
    onCropChanged: (NormalizedCrop) -> Unit,
    modifier: Modifier,
) {
    var dragTarget by remember { mutableStateOf<CropDragTarget?>(null) }
    var dragStart by remember { mutableStateOf(crop) }
    var accumulatedDrag by remember { mutableStateOf(Offset.Zero) }
    val currentCrop by rememberUpdatedState(crop)
    Canvas(
        modifier = modifier.pointerInput(bitmapWidth, bitmapHeight, aspectRatio) {
            detectDragGestures(
                onDragStart = { point ->
                    val imageBounds = fittedImageBounds(size.width.toFloat(), size.height.toFloat(), bitmapWidth, bitmapHeight)
                    dragTarget = cropDragTarget(point, currentCrop, imageBounds)
                    dragStart = currentCrop
                    accumulatedDrag = Offset.Zero
                },
                onDragEnd = { dragTarget = null },
                onDragCancel = { dragTarget = null },
                onDrag = { change, amount ->
                    change.consume()
                    accumulatedDrag += amount
                    val target = dragTarget ?: return@detectDragGestures
                    val bounds = fittedImageBounds(size.width.toFloat(), size.height.toFloat(), bitmapWidth, bitmapHeight)
                    onCropChanged(
                        moveCrop(
                            start = dragStart,
                            target = target,
                            dx = accumulatedDrag.x / bounds.width,
                            dy = accumulatedDrag.y / bounds.height,
                            requestedAspect = aspectRatio,
                            bitmapWidth = bitmapWidth,
                            bitmapHeight = bitmapHeight,
                        ),
                    )
                },
            )
        },
    ) {
        val imageBounds = fittedImageBounds(size.width, size.height, bitmapWidth, bitmapHeight)
        val rect = crop.toRect(imageBounds)
        val shade = Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(rect)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.62f))
        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
        for (fraction in listOf(1f / 3f, 2f / 3f)) {
            drawLine(
                Color.White.copy(alpha = 0.48f),
                Offset(rect.left + rect.width * fraction, rect.top),
                Offset(rect.left + rect.width * fraction, rect.bottom),
                0.7.dp.toPx(),
            )
            drawLine(
                Color.White.copy(alpha = 0.48f),
                Offset(rect.left, rect.top + rect.height * fraction),
                Offset(rect.right, rect.top + rect.height * fraction),
                0.7.dp.toPx(),
            )
        }
        val handle = 18.dp.toPx()
        listOf(rect.topLeft, Offset(rect.right, rect.top), Offset(rect.left, rect.bottom), rect.bottomRight)
            .forEach { point ->
                drawLine(Color.White, Offset(point.x - handle / 2, point.y), Offset(point.x + handle / 2, point.y), 3.dp.toPx())
                drawLine(Color.White, Offset(point.x, point.y - handle / 2), Offset(point.x, point.y + handle / 2), 3.dp.toPx())
            }
    }
}

private fun fittedImageBounds(
    containerWidth: Float,
    containerHeight: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Rect {
    val scale = min(containerWidth / bitmapWidth.coerceAtLeast(1), containerHeight / bitmapHeight.coerceAtLeast(1))
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun NormalizedCrop.toRect(bounds: Rect): Rect = Rect(
    left = bounds.left + left * bounds.width,
    top = bounds.top + top * bounds.height,
    right = bounds.left + right * bounds.width,
    bottom = bounds.top + bottom * bounds.height,
)

private fun cropDragTarget(point: Offset, crop: NormalizedCrop, bounds: Rect): CropDragTarget? {
    val rect = crop.toRect(bounds)
    val hit = 42f
    val corners = listOf(
        CropDragTarget.TopLeft to rect.topLeft,
        CropDragTarget.TopRight to Offset(rect.right, rect.top),
        CropDragTarget.BottomLeft to Offset(rect.left, rect.bottom),
        CropDragTarget.BottomRight to rect.bottomRight,
    )
    corners.firstOrNull { (_, corner) -> abs(point.x - corner.x) <= hit && abs(point.y - corner.y) <= hit }
        ?.let { return it.first }
    return if (rect.contains(point)) CropDragTarget.Move else null
}

private fun moveCrop(
    start: NormalizedCrop,
    target: CropDragTarget,
    dx: Float,
    dy: Float,
    requestedAspect: Float?,
    bitmapWidth: Int,
    bitmapHeight: Int,
): NormalizedCrop {
    val minimum = 0.08f
    if (target == CropDragTarget.Move) {
        val left = (start.left + dx).coerceIn(0f, 1f - start.width)
        val top = (start.top + dy).coerceIn(0f, 1f - start.height)
        return NormalizedCrop(left, top, left + start.width, top + start.height)
    }

    var left = if (target == CropDragTarget.TopLeft || target == CropDragTarget.BottomLeft) {
        (start.left + dx).coerceIn(0f, start.right - minimum)
    } else start.left
    var right = if (target == CropDragTarget.TopRight || target == CropDragTarget.BottomRight) {
        (start.right + dx).coerceIn(start.left + minimum, 1f)
    } else start.right
    var top = if (target == CropDragTarget.TopLeft || target == CropDragTarget.TopRight) {
        (start.top + dy).coerceIn(0f, start.bottom - minimum)
    } else start.top
    var bottom = if (target == CropDragTarget.BottomLeft || target == CropDragTarget.BottomRight) {
        (start.bottom + dy).coerceIn(start.top + minimum, 1f)
    } else start.bottom

    if (requestedAspect != null) {
        val normalizedRatio = requestedAspect * bitmapHeight.coerceAtLeast(1) / bitmapWidth.coerceAtLeast(1)
        val width = right - left
        val height = width / normalizedRatio
        if (target == CropDragTarget.TopLeft || target == CropDragTarget.TopRight) {
            top = (bottom - height).coerceAtLeast(0f)
        } else {
            bottom = (top + height).coerceAtMost(1f)
        }
        val correctedWidth = (bottom - top) * normalizedRatio
        if (target == CropDragTarget.TopLeft || target == CropDragTarget.BottomLeft) {
            left = (right - correctedWidth).coerceAtLeast(0f)
        } else {
            right = (left + correctedWidth).coerceAtMost(1f)
        }
    }
    return NormalizedCrop(left, top, right, bottom)
}

internal fun centeredCropForAspect(
    requestedAspect: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
): NormalizedCrop {
    val sourceAspect = bitmapWidth.toFloat() / bitmapHeight.coerceAtLeast(1)
    return if (sourceAspect > requestedAspect) {
        val width = requestedAspect / sourceAspect
        val left = (1f - width) / 2f
        NormalizedCrop(left, 0f, left + width, 1f)
    } else {
        val height = sourceAspect / requestedAspect
        val top = (1f - height) / 2f
        NormalizedCrop(0f, top, 1f, top + height)
    }
}

private fun rotateBitmapClockwise(source: Bitmap): Bitmap =
    Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(90f) }, true)

internal fun saveCroppedPhoto(source: Bitmap, crop: NormalizedCrop, destination: File): Boolean {
    val left = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 1)
    val top = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1)
    val right = (crop.right * source.width).roundToInt().coerceIn(left + 1, source.width)
    val bottom = (crop.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    val atomic = AtomicFile(destination)
    var stream: java.io.FileOutputStream? = null
    return try {
        stream = atomic.startWrite()
        check(cropped.compress(Bitmap.CompressFormat.JPEG, 96, stream))
        atomic.finishWrite(stream)
        stream = null
        true
    } catch (_: Exception) {
        stream?.let(atomic::failWrite)
        false
    } finally {
        if (cropped !== source) cropped.recycle()
    }
}
