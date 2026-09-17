package com.chatkit.compose

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AtomicFile
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
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

private enum class CropDragTarget {
    Move, TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left,
}

private enum class CropAspectKind { Freeform, Original, Square, FourThree, SixteenNine }

private data class CropAspect(val kind: CropAspectKind, val label: String, val ratio: Float?)

private fun cropAspects(bitmap: Bitmap): List<CropAspect> {
    val portrait = bitmap.height >= bitmap.width
    return listOf(
        CropAspect(CropAspectKind.Freeform, "Freeform", null),
        CropAspect(
            CropAspectKind.Original,
            "Original",
            bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1),
        ),
        CropAspect(CropAspectKind.Square, "Square", 1f),
        CropAspect(
            CropAspectKind.FourThree,
            if (portrait) "3:4" else "4:3",
            if (portrait) 3f / 4f else 4f / 3f,
        ),
        CropAspect(
            CropAspectKind.SixteenNine,
            if (portrait) "9:16" else "16:9",
            if (portrait) 9f / 16f else 16f / 9f,
        ),
    )
}

@Composable
internal fun PhotoCropEditor(
    source: Bitmap,
    destination: File,
    accentColor: Color,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    var bitmap by remember(source) { mutableStateOf(source) }
    var crop by remember(source) { mutableStateOf(NormalizedCrop(0f, 0f, 1f, 1f)) }
    var selectedAspectKind by remember { mutableStateOf(CropAspectKind.Freeform) }
    var aspectMenuExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val aspects = cropAspects(bitmap)
    val selectedAspect = aspects.first { it.kind == selectedAspectKind }
    val headerButtonColors = ButtonDefaults.textButtonColors(
        contentColor = Color.White,
        disabledContentColor = Color.White.copy(alpha = 0.55f),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
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

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // safeDrawing includes both the status bar and display cutout. The
                // dialog is edge-to-edge, so statusBarsPadding alone is insufficient
                // on devices whose cutout inset is taller than the status bar.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !saving,
                onClick = onCancel,
                colors = headerButtonColors,
            ) {
                Text("Cancel", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !saving,
                colors = headerButtonColors,
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
            ) {
                Text("Done", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 28.dp, end = 28.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CropRoundButton(
                contentDescription = "Rotate clockwise",
                enabled = !saving,
                onClick = {
                    bitmap = rotateBitmapClockwise(bitmap)
                    val rotatedAspect = cropAspects(bitmap).first { it.kind == selectedAspectKind }
                    crop = rotatedAspect.ratio?.let {
                        centeredCropForAspect(it, bitmap.width, bitmap.height)
                    } ?: NormalizedCrop(0f, 0f, 1f, 1f)
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, tint = Color.White)
            }

            TextButton(
                enabled = !saving,
                onClick = {
                    bitmap = source
                    selectedAspectKind = CropAspectKind.Freeform
                    crop = NormalizedCrop(0f, 0f, 1f, 1f)
                },
            ) {
                Text("Reset", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            Box {
                CropRoundButton(
                    contentDescription = "Crop aspect ratio",
                    enabled = !saving,
                    onClick = { aspectMenuExpanded = true },
                ) {
                    Icon(Icons.Default.AspectRatio, contentDescription = null, tint = Color.White)
                }
                DropdownMenu(
                    expanded = aspectMenuExpanded,
                    onDismissRequest = { aspectMenuExpanded = false },
                ) {
                    aspects.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.label) },
                            trailingIcon = if (choice.kind == selectedAspectKind) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                selectedAspectKind = choice.kind
                                crop = choice.ratio?.let {
                                    centeredCropForAspect(it, bitmap.width, bitmap.height)
                                } ?: NormalizedCrop(0f, 0f, 1f, 1f)
                                aspectMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = accentColor)
            }
        }
    }
}

@Composable
private fun CropRoundButton(
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.48f))
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
    val currentCrop by rememberUpdatedState(crop)
    val currentOnCropChanged by rememberUpdatedState(onCropChanged)
    val density = LocalDensity.current
    val rootView = LocalView.current
    val cornerHitRadiusPx = with(density) { 48.dp.toPx() }
    val edgeHitRadiusPx = with(density) { 40.dp.toPx() }
    Canvas(
        modifier = modifier
            .cropHandleSystemGestureExclusion(
                bitmapWidth = bitmapWidth,
                bitmapHeight = bitmapHeight,
                crop = crop,
            )
            .pointerInput(
                bitmapWidth,
                bitmapHeight,
                aspectRatio,
                cornerHitRadiusPx,
                edgeHitRadiusPx,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val bounds = fittedImageBounds(
                        size.width.toFloat(),
                        size.height.toFloat(),
                        bitmapWidth,
                        bitmapHeight,
                    )
                    val target = cropDragTarget(
                        point = down.position,
                        crop = currentCrop,
                        bounds = bounds,
                        cornerHitRadius = cornerHitRadiusPx,
                        edgeHitRadius = edgeHitRadiusPx,
                    ) ?: return@awaitEachGesture

                    // Claim a handle touch on ACTION_DOWN. Waiting for touch slop lets
                    // Android's edge-back recognizer win before Compose starts dragging.
                    down.consume()
                    (rootView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    val dragStart = currentCrop
                    var accumulatedDrag = Offset.Zero
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            accumulatedDrag += change.positionChange()
                            currentOnCropChanged(
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
                        }
                    } finally {
                        (rootView.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    }
                }
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
        val handle = 22.dp.toPx()
        val stroke = 3.dp.toPx()
        val corners = listOf(
            Triple(rect.topLeft, 1f, 1f),
            Triple(Offset(rect.right, rect.top), -1f, 1f),
            Triple(Offset(rect.left, rect.bottom), 1f, -1f),
            Triple(rect.bottomRight, -1f, -1f),
        )
        corners.forEach { (point, horizontalSign, verticalSign) ->
            drawLine(Color.White, point, Offset(point.x + horizontalSign * handle, point.y), stroke)
            drawLine(Color.White, point, Offset(point.x, point.y + verticalSign * handle), stroke)
        }
        val edgeHandle = 16.dp.toPx()
        drawLine(Color.White, Offset(rect.center.x - edgeHandle, rect.top), Offset(rect.center.x + edgeHandle, rect.top), stroke)
        drawLine(Color.White, Offset(rect.center.x - edgeHandle, rect.bottom), Offset(rect.center.x + edgeHandle, rect.bottom), stroke)
        drawLine(Color.White, Offset(rect.left, rect.center.y - edgeHandle), Offset(rect.left, rect.center.y + edgeHandle), stroke)
        drawLine(Color.White, Offset(rect.right, rect.center.y - edgeHandle), Offset(rect.right, rect.center.y + edgeHandle), stroke)
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

private fun cropDragTarget(
    point: Offset,
    crop: NormalizedCrop,
    bounds: Rect,
    cornerHitRadius: Float,
    edgeHitRadius: Float,
): CropDragTarget? {
    val rect = crop.toRect(bounds)
    val corners = listOf(
        CropDragTarget.TopLeft to rect.topLeft,
        CropDragTarget.TopRight to Offset(rect.right, rect.top),
        CropDragTarget.BottomLeft to Offset(rect.left, rect.bottom),
        CropDragTarget.BottomRight to rect.bottomRight,
    )
    corners.firstOrNull { (_, corner) ->
        abs(point.x - corner.x) <= cornerHitRadius &&
            abs(point.y - corner.y) <= cornerHitRadius
    }
        ?.let { return it.first }
    if (point.x in (rect.left - edgeHitRadius)..(rect.right + edgeHitRadius)) {
        if (abs(point.y - rect.top) <= edgeHitRadius) return CropDragTarget.Top
        if (abs(point.y - rect.bottom) <= edgeHitRadius) return CropDragTarget.Bottom
    }
    if (point.y in (rect.top - edgeHitRadius)..(rect.bottom + edgeHitRadius)) {
        if (abs(point.x - rect.left) <= edgeHitRadius) return CropDragTarget.Left
        if (abs(point.x - rect.right) <= edgeHitRadius) return CropDragTarget.Right
    }
    return if (rect.contains(point)) CropDragTarget.Move else null
}

/**
 * Reserves only the six horizontal-edge handle areas from Android's back gesture.
 * Keeping each rectangle 64dp tall stays within the platform's per-edge exclusion budget.
 */
private fun Modifier.cropHandleSystemGestureExclusion(
    bitmapWidth: Int,
    bitmapHeight: Int,
    crop: NormalizedCrop,
): Modifier = composed {
    val view = LocalView.current
    val density = LocalDensity.current
    val radiusPx = with(density) { 32.dp.roundToPx() }
    DisposableEffect(view) {
        onDispose { ViewCompat.setSystemGestureExclusionRects(view, emptyList()) }
    }
    Modifier.onGloballyPositioned { coordinates ->
        if (!coordinates.isAttached || view.width == 0 || view.height == 0) {
            return@onGloballyPositioned
        }
        val imageBounds = fittedImageBounds(
            coordinates.size.width.toFloat(),
            coordinates.size.height.toFloat(),
            bitmapWidth,
            bitmapHeight,
        )
        val cropRect = crop.toRect(imageBounds)
        val localHandleCenters = listOf(
            cropRect.topLeft,
            Offset(cropRect.left, cropRect.center.y),
            Offset(cropRect.left, cropRect.bottom),
            Offset(cropRect.right, cropRect.top),
            Offset(cropRect.right, cropRect.center.y),
            cropRect.bottomRight,
        )
        val viewLocation = IntArray(2)
        view.getLocationInWindow(viewLocation)
        val overlayBounds = coordinates.boundsInWindow()
        val offsetX = overlayBounds.left - viewLocation[0]
        val offsetY = overlayBounds.top - viewLocation[1]
        val exclusions = localHandleCenters.mapNotNull { center ->
            val centerX = (offsetX + center.x).roundToInt()
            val centerY = (offsetY + center.y).roundToInt()
            android.graphics.Rect(
                (centerX - radiusPx).coerceAtLeast(0),
                (centerY - radiusPx).coerceAtLeast(0),
                (centerX + radiusPx).coerceAtMost(view.width),
                (centerY + radiusPx).coerceAtMost(view.height),
            ).takeUnless { it.isEmpty }
        }
        ViewCompat.setSystemGestureExclusionRects(view, exclusions)
    }
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

    var left = if (
        target == CropDragTarget.TopLeft || target == CropDragTarget.BottomLeft ||
        target == CropDragTarget.Left
    ) {
        (start.left + dx).coerceIn(0f, start.right - minimum)
    } else start.left
    var right = if (
        target == CropDragTarget.TopRight || target == CropDragTarget.BottomRight ||
        target == CropDragTarget.Right
    ) {
        (start.right + dx).coerceIn(start.left + minimum, 1f)
    } else start.right
    var top = if (
        target == CropDragTarget.TopLeft || target == CropDragTarget.TopRight ||
        target == CropDragTarget.Top
    ) {
        (start.top + dy).coerceIn(0f, start.bottom - minimum)
    } else start.top
    var bottom = if (
        target == CropDragTarget.BottomLeft || target == CropDragTarget.BottomRight ||
        target == CropDragTarget.Bottom
    ) {
        (start.bottom + dy).coerceIn(start.top + minimum, 1f)
    } else start.bottom

    if (requestedAspect != null) {
        val normalizedRatio = requestedAspect * bitmapHeight.coerceAtLeast(1) / bitmapWidth.coerceAtLeast(1)
        if (target == CropDragTarget.Top || target == CropDragTarget.Bottom) {
            var height = bottom - top
            var width = height * normalizedRatio
            if (width > 1f) {
                width = 1f
                height = width / normalizedRatio
                if (target == CropDragTarget.Top) top = bottom - height else bottom = top + height
            }
            val centerX = (start.left + start.right) / 2f
            left = (centerX - width / 2f).coerceIn(0f, 1f - width)
            right = left + width
            return NormalizedCrop(left, top, right, bottom)
        }
        if (target == CropDragTarget.Left || target == CropDragTarget.Right) {
            var width = right - left
            var height = width / normalizedRatio
            if (height > 1f) {
                height = 1f
                width = height * normalizedRatio
                if (target == CropDragTarget.Left) left = right - width else right = left + width
            }
            val centerY = (start.top + start.bottom) / 2f
            top = (centerY - height / 2f).coerceIn(0f, 1f - height)
            bottom = top + height
            return NormalizedCrop(left, top, right, bottom)
        }
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
