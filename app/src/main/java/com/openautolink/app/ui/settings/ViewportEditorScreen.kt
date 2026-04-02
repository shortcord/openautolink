package com.openautolink.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AspectRatio(val w: Int, val h: Int, val label: String) {
    RATIO_16_9(16, 9, "16:9"),
    RATIO_21_9(21, 9, "21:9"),
    RATIO_32_9(32, 9, "32:9"),
    RATIO_4_3(4, 3, "4:3"),
    RATIO_16_10(16, 10, "16:10"),
    RATIO_2_1(2, 1, "2:1");

    val value: Float get() = w.toFloat() / h.toFloat()
}

private data class ViewportPreset(
    val label: String,
    val ratio: AspectRatio?,
)

private val presets = listOf(
    ViewportPreset("Fill Display", null),
    ViewportPreset("16:9", AspectRatio.RATIO_16_9),
    ViewportPreset("21:9", AspectRatio.RATIO_21_9),
    ViewportPreset("32:9", AspectRatio.RATIO_32_9),
    ViewportPreset("4:3", AspectRatio.RATIO_4_3),
    ViewportPreset("16:10", AspectRatio.RATIO_16_10),
    ViewportPreset("2:1", AspectRatio.RATIO_2_1),
)

private const val MIN_WIDTH = 800
private const val MIN_HEIGHT = 480
private const val HANDLE_THICKNESS_DP = 24f
private val HANDLE_COLOR = Color(0xFF64FFDA)
private val HANDLE_BG_COLOR = Color(0x8064FFDA)
private val HANDLE_GRIP_COLOR = Color(0xFF004D40)
private val VIEWPORT_BORDER_COLOR = Color(0xFF64FFDA)
private val OUTSIDE_COLOR = Color.Black

/**
 * Find the nearest standard aspect ratio to the given width/height.
 */
private fun nearestAspectRatio(width: Int, height: Int): AspectRatio {
    if (height <= 0) return AspectRatio.RATIO_16_9
    val ratio = width.toFloat() / height.toFloat()
    return AspectRatio.entries.minBy { abs(it.value - ratio) }
}

/**
 * Snap dimensions to the nearest standard aspect ratio, keeping the larger axis fixed.
 */
private fun snapToRatio(
    width: Int,
    height: Int,
    maxWidth: Int,
    maxHeight: Int
): Pair<Int, Int> {
    val ratio = nearestAspectRatio(width, height)
    // Try fitting by height first
    val snappedWidth = (height * ratio.w / ratio.h).coerceIn(MIN_WIDTH, maxWidth)
    val snappedHeight = (snappedWidth * ratio.h / ratio.w).coerceIn(MIN_HEIGHT, maxHeight)
    return snappedWidth to snappedHeight
}

@Composable
fun ViewportEditorScreen(
    initialWidth: Int,
    initialHeight: Int,
    aspectRatioLocked: Boolean,
    onDone: (width: Int, height: Int, ratioLocked: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var ratioLocked by remember { mutableStateOf(aspectRatioLocked) }
    var showManualInput by remember { mutableStateOf(false) }
    var presetMenuExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("viewportEditorScreen")
    ) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx().roundToInt() }
        val maxHeightPx = with(density) { maxHeight.toPx().roundToInt() }

        // Viewport dimensions in pixels — start from saved or full display
        var vpWidth by remember {
            mutableIntStateOf(
                if (initialWidth > 0) initialWidth.coerceIn(MIN_WIDTH, maxWidthPx)
                else maxWidthPx
            )
        }
        var vpHeight by remember {
            mutableIntStateOf(
                if (initialHeight > 0) initialHeight.coerceIn(MIN_HEIGHT, maxHeightPx)
                else maxHeightPx
            )
        }

        // Computed label
        val currentRatio = nearestAspectRatio(vpWidth, vpHeight)
        val isExactRatio = abs(vpWidth.toFloat() / vpHeight - currentRatio.value) < 0.02f
        val dimensionLabel = if (vpWidth == maxWidthPx && vpHeight == maxHeightPx) {
            "$vpWidth × $vpHeight (Fill)"
        } else if (isExactRatio) {
            "$vpWidth × $vpHeight (${currentRatio.label})"
        } else {
            "$vpWidth × $vpHeight (custom)"
        }

        // Scale factor from physical pixels to composable coordinate space
        val scaleX = maxWidth.value / maxWidthPx
        val scaleY = maxHeight.value / maxHeightPx

        // Viewport rect in Dp space — anchored bottom-left
        val vpWidthDp = vpWidth * scaleX
        val vpHeightDp = vpHeight * scaleY
        val vpTopDp = maxHeight.value - vpHeightDp

        // --- Draw: dark overlay outside viewport ---
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top strip (above viewport)
            if (vpTopDp > 0) {
                drawRect(
                    color = OUTSIDE_COLOR,
                    topLeft = Offset.Zero,
                    size = Size(size.width, vpTopDp * density.density)
                )
            }
            // Right strip (right of viewport)
            val vpRightPx = vpWidthDp * density.density
            if (vpRightPx < size.width) {
                drawRect(
                    color = OUTSIDE_COLOR,
                    topLeft = Offset(vpRightPx, vpTopDp * density.density),
                    size = Size(size.width - vpRightPx, vpHeightDp * density.density)
                )
            }

            // Viewport border
            drawRect(
                color = VIEWPORT_BORDER_COLOR,
                topLeft = Offset(0f, vpTopDp * density.density),
                size = Size(vpWidthDp * density.density, vpHeightDp * density.density),
                style = Stroke(width = 2f * density.density)
            )
        }

        // Checkerboard pattern inside viewport area
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(vpWidthDp.dp, vpHeightDp.dp)
        ) {
            val tileSize = 20f * density.density
            val cols = (size.width / tileSize).roundToInt() + 1
            val rows = (size.height / tileSize).roundToInt() + 1
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val color = if ((r + c) % 2 == 0) Color(0xFF1A1A1A) else Color(0xFF2A2A2A)
                    drawRect(
                        color = color,
                        topLeft = Offset(c * tileSize, r * tileSize),
                        size = Size(
                            tileSize.coerceAtMost(size.width - c * tileSize),
                            tileSize.coerceAtMost(size.height - r * tileSize)
                        )
                    )
                }
            }
        }

        // --- Top drag handle: visible bar inside the viewport's top edge ---
        var dragAccumulatorTop by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    // Inside the viewport: just below the top edge
                    val topEdgePx = (vpHeight * scaleY * density.density).roundToInt()
                    val handlePx = (HANDLE_THICKNESS_DP * density.density).roundToInt()
                    IntOffset(0, -(topEdgePx - handlePx))
                }
                .size(vpWidthDp.dp, HANDLE_THICKNESS_DP.dp)
                .background(HANDLE_BG_COLOR, RoundedCornerShape(4.dp))
                .pointerInput(ratioLocked, maxWidthPx, maxHeightPx) {
                    detectDragGestures(
                        onDragStart = { dragAccumulatorTop = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulatorTop += dragAmount.y
                            val deltaPixels = (dragAccumulatorTop / (scaleY * density.density)).roundToInt()
                            if (deltaPixels != 0) {
                                val newHeight = (vpHeight - deltaPixels).coerceIn(MIN_HEIGHT, maxHeightPx)
                                vpHeight = newHeight
                                if (ratioLocked) {
                                    val ratio = nearestAspectRatio(vpWidth, vpHeight)
                                    vpWidth = (vpHeight * ratio.w / ratio.h).coerceIn(MIN_WIDTH, maxWidthPx)
                                }
                                dragAccumulatorTop = 0f
                            }
                        }
                    )
                }
                .testTag("topDragHandle")
        ) {
            // Grip indicator: 3 horizontal lines centered in the bar
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                val gripWidth = 60f * density.density
                val lineSpacing = 6f * density.density
                for (i in -1..1) {
                    drawLine(
                        color = HANDLE_GRIP_COLOR,
                        start = Offset(cx - gripWidth / 2, cy + i * lineSpacing),
                        end = Offset(cx + gripWidth / 2, cy + i * lineSpacing),
                        strokeWidth = 2.5f * density.density
                    )
                }
            }
        }

        // --- Right drag handle: visible bar inside the viewport's right edge ---
        var dragAccumulatorRight by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    // Inside the viewport: flush against the right edge
                    val rightEdgePx = (vpWidth * scaleX * density.density).roundToInt()
                    val handlePx = (HANDLE_THICKNESS_DP * density.density).roundToInt()
                    IntOffset(rightEdgePx - handlePx, 0)
                }
                .size(HANDLE_THICKNESS_DP.dp, vpHeightDp.dp)
                .background(HANDLE_BG_COLOR, RoundedCornerShape(4.dp))
                .pointerInput(ratioLocked, maxWidthPx, maxHeightPx) {
                    detectDragGestures(
                        onDragStart = { dragAccumulatorRight = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulatorRight += dragAmount.x
                            val deltaPixels = (dragAccumulatorRight / (scaleX * density.density)).roundToInt()
                            if (deltaPixels != 0) {
                                val newWidth = (vpWidth + deltaPixels).coerceIn(MIN_WIDTH, maxWidthPx)
                                vpWidth = newWidth
                                if (ratioLocked) {
                                    val ratio = nearestAspectRatio(vpWidth, vpHeight)
                                    vpHeight = (vpWidth * ratio.h / ratio.w).coerceIn(MIN_HEIGHT, maxHeightPx)
                                }
                                dragAccumulatorRight = 0f
                            }
                        }
                    )
                }
                .testTag("rightDragHandle")
        ) {
            // Grip indicator: 3 vertical lines centered in the bar
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                val gripHeight = 60f * density.density
                val lineSpacing = 6f * density.density
                for (i in -1..1) {
                    drawLine(
                        color = HANDLE_GRIP_COLOR,
                        start = Offset(cx + i * lineSpacing, cy - gripHeight / 2),
                        end = Offset(cx + i * lineSpacing, cy + gripHeight / 2),
                        strokeWidth = 2.5f * density.density
                    )
                }
            }
        }

        // --- Toolbar: top center ---
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC1E1E1E))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Preset dropdown
            Box {
                FilledTonalButton(
                    onClick = { presetMenuExpanded = true },
                    modifier = Modifier.testTag("presetButton"),
                ) {
                    Text("Preset")
                }
                DropdownMenu(
                    expanded = presetMenuExpanded,
                    onDismissRequest = { presetMenuExpanded = false },
                ) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                presetMenuExpanded = false
                                if (preset.ratio == null) {
                                    // Fill display
                                    vpWidth = maxWidthPx
                                    vpHeight = maxHeightPx
                                } else {
                                    // Compute max rect with this ratio
                                    val ratioVal = preset.ratio.value
                                    val fitByHeight = (maxHeightPx * ratioVal).roundToInt()
                                    if (fitByHeight <= maxWidthPx) {
                                        vpWidth = fitByHeight.coerceIn(MIN_WIDTH, maxWidthPx)
                                        vpHeight = maxHeightPx
                                    } else {
                                        vpWidth = maxWidthPx
                                        vpHeight = (maxWidthPx / ratioVal).roundToInt()
                                            .coerceIn(MIN_HEIGHT, maxHeightPx)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("preset_${preset.label}"),
                        )
                    }
                }
            }

            // Lock/unlock aspect ratio
            FilledTonalIconButton(
                onClick = { ratioLocked = !ratioLocked },
                modifier = Modifier.testTag("ratioLockButton"),
            ) {
                Icon(
                    imageVector = if (ratioLocked) Icons.Default.AspectRatio else Icons.Default.CropFree,
                    contentDescription = if (ratioLocked) "Aspect ratio locked" else "Free resize"
                )
            }

            // Save button
            FilledTonalButton(
                onClick = { onDone(vpWidth, vpHeight, ratioLocked) },
                modifier = Modifier.testTag("saveButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Save",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save")
            }
        }

        // --- Dimension label: bottom center, tappable ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            if (showManualInput) {
                ManualDimensionInput(
                    currentWidth = vpWidth,
                    currentHeight = vpHeight,
                    maxWidth = maxWidthPx,
                    maxHeight = maxHeightPx,
                    ratioLocked = ratioLocked,
                    onApply = { w, h ->
                        vpWidth = w
                        vpHeight = h
                        showManualInput = false
                    },
                    onDismiss = { showManualInput = false },
                )
            } else {
                Text(
                    text = dimensionLabel,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC1E1E1E))
                        .clickable { showManualInput = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("dimensionLabel"),
                )
            }
        }
    }
}

@Composable
private fun ManualDimensionInput(
    currentWidth: Int,
    currentHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    ratioLocked: Boolean,
    onApply: (width: Int, height: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var widthText by remember { mutableStateOf(currentWidth.toString()) }
    var heightText by remember { mutableStateOf(currentHeight.toString()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier = Modifier.testTag("manualDimensionInput"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { input ->
                        widthText = input
                        if (ratioLocked) {
                            input.toIntOrNull()?.let { w ->
                                val ratio = nearestAspectRatio(currentWidth, currentHeight)
                                heightText = (w * ratio.h / ratio.w).coerceIn(MIN_HEIGHT, maxHeight).toString()
                            }
                        }
                        errorMessage = null
                    },
                    label = { Text("Width") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.width(120.dp),
                )
                Text("×", fontSize = 18.sp, color = Color.White)
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { input ->
                        heightText = input
                        if (ratioLocked) {
                            input.toIntOrNull()?.let { h ->
                                val ratio = nearestAspectRatio(currentWidth, currentHeight)
                                widthText = (h * ratio.w / ratio.h).coerceIn(MIN_WIDTH, maxWidth).toString()
                            }
                        }
                        errorMessage = null
                    },
                    label = { Text("Height") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val w = widthText.toIntOrNull()
                            val h = heightText.toIntOrNull()
                            if (w != null && h != null && w in MIN_WIDTH..maxWidth && h in MIN_HEIGHT..maxHeight) {
                                onApply(w, h)
                            } else {
                                errorMessage = "Range: ${MIN_WIDTH}–$maxWidth × ${MIN_HEIGHT}–$maxHeight"
                            }
                        }
                    ),
                    modifier = Modifier.width(120.dp),
                )
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        val w = widthText.toIntOrNull()
                        val h = heightText.toIntOrNull()
                        if (w != null && h != null && w in MIN_WIDTH..maxWidth && h in MIN_HEIGHT..maxHeight) {
                            onApply(w, h)
                        } else {
                            errorMessage = "Range: ${MIN_WIDTH}–$maxWidth × ${MIN_HEIGHT}–$maxHeight"
                        }
                    },
                    modifier = Modifier.testTag("applyDimensionsButton"),
                ) {
                    Text("Apply")
                }
                FilledTonalButton(
                    onClick = onDismiss,
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
