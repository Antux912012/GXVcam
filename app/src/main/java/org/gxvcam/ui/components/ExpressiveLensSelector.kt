package org.gxvcam.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.gxvcam.ui.state.OpticalLens
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion
import kotlin.math.abs
import kotlin.math.roundToInt

// Piecewise zoom mapping: 0.6x to 3.0x is expanded for maximum precision on 1.1x, 1.2x...
private const val X_AT_3 = (3.0f - 0.6f) * 240f // 576f
private const val X_AT_10 = X_AT_3 + (10.0f - 3.0f) * 48f // 912f
private const val TOTAL_RULER_WIDTH_DP = X_AT_10 + (20.0f - 10.0f) * 24f // 1152f

private fun zoomToRulerX(zoom: Float): Float {
    return when {
        zoom <= 3.0f -> (zoom - 0.6f) * 240f
        zoom <= 10.0f -> X_AT_3 + (zoom - 3.0f) * 48f
        else -> X_AT_10 + (zoom - 10.0f) * 24f
    }
}

private fun rulerXToZoom(x: Float): Float {
    return when {
        x <= X_AT_3 -> (0.6f + (x / 240f)).coerceIn(0.6f, 3.0f)
        x <= X_AT_10 -> (3.0f + ((x - X_AT_3) / 48f)).coerceIn(3.0f, 10.0f)
        else -> (10.0f + ((x - X_AT_10) / 24f)).coerceIn(10.0f, 20.0f)
    }
}

private val RULER_TICKS: List<Float> = buildList {
    // 0.6 to 3.0 in steps of 0.1
    var z = 0.6f
    while (z <= 3.05f) {
        add((z * 10f).roundToInt() / 10f)
        z += 0.1f
    }
    // 3.0 to 10.0 in steps of 0.5
    z = 3.5f
    while (z <= 10.05f) {
        add((z * 10f).roundToInt() / 10f)
        z += 0.5f
    }
    // 10.0 to 20.0 in steps of 1.0
    z = 11.0f
    while (z <= 20.05f) {
        add((z * 10f).roundToInt() / 10f)
        z += 1.0f
    }
}.distinct()

private val MAJOR_STOPS = listOf(0.6f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f, 10.0f, 20.0f)
private val QUICK_CHIPS = listOf(0.6f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f, 5.0f, 10.0f, 20.0f)

@Composable
fun ExpressiveLensSelector(
    activeLens: OpticalLens,
    currentZoomRatio: Float,
    onLensSelected: (OpticalLens) -> Unit,
    onZoomChanged: (Float) -> Unit,
    onZoomStep: (Float) -> Unit = onZoomChanged,
    isExpanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    isFull: Boolean = false,
    isBackCamera: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    var lastHapticStep by remember { mutableIntStateOf((currentZoomRatio * 10f).roundToInt()) }

    val pillContainerColor = if (isFull) Color(0x66000000) else DarkSurfaceContainerHigh
    val pillBorder = BorderStroke(1.dp, Color.White.copy(alpha = if (isFull) 0.22f else 0.12f))
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Continuous Dial & Precision Arc Ruler Deck (rear camera only)
        AnimatedVisibility(
            visible = isExpanded && isBackCamera,
            enter = fadeIn(GXVMotion.fastEffects()) + expandVertically(GXVMotion.spatialBouncy()),
            exit = fadeOut(GXVMotion.fastEffects()) + shrinkVertically(GXVMotion.fastEffects())
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = pillContainerColor,
                border = pillBorder,
                tonalElevation = if (isFull) 0.dp else 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header: Zoom Readout & Sensor Description & Collapse Chevron
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            val formattedZoom = if (currentZoomRatio >= 10f) {
                                String.format(java.util.Locale.US, "%.0fx", currentZoomRatio)
                            } else {
                                String.format(java.util.Locale.US, "%.1fx", currentZoomRatio)
                            }
                            Text(
                                text = formattedZoom,
                                color = primaryColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp
                            )
                            Text(
                                text = OpticalLens.sensorDescriptionForZoom(currentZoomRatio),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        IconButton(
                            onClick = { onExpandedChange(false) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapse zoom slider",
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Intermediate Quick Jump Chips (0.6x, 1.0x, 1.2x, 1.5x, 2.0x, 3.0x, 5.0x, 10x, 20x)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QUICK_CHIPS.forEach { chipZoom ->
                            val isChipActive = abs(currentZoomRatio - chipZoom) < 0.05f
                            val label = when {
                                chipZoom == 0.6f -> ".6"
                                chipZoom == chipZoom.toInt().toFloat() -> "${chipZoom.toInt()}x"
                                else -> "${chipZoom}x"
                            }
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (isChipActive) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.08f)
                                    )
                                    .clickable {
                                        tactile.triggerSnap()
                                        lastInteractionTime = System.currentTimeMillis()
                                        onZoomStep(chipZoom)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isChipActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isChipActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Precision Drag Ruler with Minus & Plus Micro-Steppers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Minus 0.1x Micro-step button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    tactile.triggerTick()
                                    lastInteractionTime = System.currentTimeMillis()
                                    val next = ((currentZoomRatio - 0.1f) * 10f).roundToInt() / 10f
                                    onZoomStep(next.coerceIn(OpticalLens.MIN_ZOOM, OpticalLens.MAX_ZOOM))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        // Center Continuous Draggable Canvas Ruler
                        val density = LocalDensity.current.density
                        val primaryArgb = primaryColor.toArgb()
                        val textPaint = remember(density) {
                            Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 9f * density
                                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                                textAlign = Paint.Align.CENTER
                                isAntiAlias = true
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .padding(horizontal = 4.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            lastInteractionTime = System.currentTimeMillis()
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            lastInteractionTime = System.currentTimeMillis()
                                            val deltaDp = -dragAmount.x / density
                                            val currentX = zoomToRulerX(currentZoomRatio)
                                            val newX = (currentX + deltaDp).coerceIn(0f, TOTAL_RULER_WIDTH_DP)
                                            val newZoom = rulerXToZoom(newX)
                                            val step = (newZoom * 10f).roundToInt()
                                            if (step != lastHapticStep) {
                                                val roundedZoom = step / 10f
                                                if (MAJOR_STOPS.any { abs(it - roundedZoom) < 0.05f }) {
                                                    tactile.triggerSnap()
                                                } else {
                                                    tactile.triggerTick()
                                                }
                                                lastHapticStep = step
                                            }
                                            onZoomChanged((newZoom * 10f).roundToInt() / 10f)
                                        },
                                        onDragEnd = {
                                            lastInteractionTime = System.currentTimeMillis()
                                        }
                                    )
                                }
                        ) {
                            val centerX = size.width / 2f
                            val currentRulerX = zoomToRulerX(currentZoomRatio)

                            // Draw ruler tick marks
                            for (tick in RULER_TICKS) {
                                val tickScreenX = centerX + (zoomToRulerX(tick) - currentRulerX) * density
                                if (tickScreenX in -40f..(size.width + 40f)) {
                                    val isMajor = MAJOR_STOPS.any { abs(it - tick) < 0.05f }
                                    val isHalf = ((tick * 10f).roundToInt() % 5 == 0)

                                    if (isMajor) {
                                        val isCurrentStop = abs(tick - currentZoomRatio) < 0.05f
                                        drawLine(
                                            color = if (isCurrentStop) primaryColor else Color.White.copy(alpha = 0.9f),
                                            start = Offset(tickScreenX, 4.dp.toPx()),
                                            end = Offset(tickScreenX, 22.dp.toPx()),
                                            strokeWidth = 2.5.dp.toPx()
                                        )
                                        val label = when {
                                            tick == 0.6f -> ".6"
                                            tick == tick.toInt().toFloat() -> "${tick.toInt()}"
                                            else -> "$tick"
                                        }
                                        drawIntoCanvas { canvas ->
                                            textPaint.color = if (isCurrentStop) primaryArgb else android.graphics.Color.WHITE
                                            canvas.nativeCanvas.drawText(label, tickScreenX, 34.dp.toPx(), textPaint)
                                        }
                                    } else if (isHalf) {
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.55f),
                                            start = Offset(tickScreenX, 8.dp.toPx()),
                                            end = Offset(tickScreenX, 19.dp.toPx()),
                                            strokeWidth = 1.6.dp.toPx()
                                        )
                                    } else {
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.28f),
                                            start = Offset(tickScreenX, 11.dp.toPx()),
                                            end = Offset(tickScreenX, 17.dp.toPx()),
                                            strokeWidth = 1.1.dp.toPx()
                                        )
                                    }
                                }
                            }

                            // Center hairline needle (Glowing Primary indicator)
                            drawLine(
                                color = primaryColor,
                                start = Offset(centerX, 0f),
                                end = Offset(centerX, 28.dp.toPx()),
                                strokeWidth = 3.dp.toPx()
                            )
                        }

                        // Plus 0.1x Micro-step button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    tactile.triggerTick()
                                    lastInteractionTime = System.currentTimeMillis()
                                    val next = ((currentZoomRatio + 0.1f) * 10f).roundToInt() / 10f
                                    onZoomStep(next.coerceIn(OpticalLens.MIN_ZOOM, OpticalLens.MAX_ZOOM))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // Floating Compact M3 Pill Deck (.6, 1x, 2x, 3x)
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = pillContainerColor,
            border = pillBorder,
            tonalElevation = if (isFull) 0.dp else 4.dp,
            modifier = Modifier.height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .wrapContentSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isBackCamera) {
                    val presets = listOf(OpticalLens.UW, OpticalLens.MAIN_1X, OpticalLens.CROP_2X, OpticalLens.TELE_3X)

                    presets.forEach { lens ->
                        val isSelected = activeLens == lens

                        // Format pill label: if current zoom is intermediate (e.g. 1.2x), reflect it directly
                        val pillLabel = if (isSelected) {
                            val formattedZoom = if (currentZoomRatio >= 10f) {
                                String.format(java.util.Locale.US, "%.0fx", currentZoomRatio)
                            } else {
                                String.format(java.util.Locale.US, "%.1fx", currentZoomRatio)
                            }
                            val focalMm = OpticalLens.focalLengthForZoom(currentZoomRatio)
                            "$formattedZoom • ${focalMm}mm"
                        } else {
                            lens.label
                        }

                        val pillWidth by animateDpAsState(
                            targetValue = if (isSelected) 88.dp else 40.dp,
                            animationSpec = GXVMotion.pillMorph(),
                            label = "lensPillWidth"
                        )

                        Box(
                            modifier = Modifier
                                .width(pillWidth)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                                .clickable {
                                    if (isSelected) {
                                        // Tapping already-selected lens toggles the continuous zoom ruler
                                        tactile.triggerSnap()
                                        onExpandedChange(!isExpanded)
                                        lastInteractionTime = System.currentTimeMillis()
                                    } else {
                                        // Tapping different lens snaps to its preset
                                        tactile.triggerSnap()
                                        lastInteractionTime = System.currentTimeMillis()
                                        onLensSelected(lens)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pillLabel,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    Color.White.copy(alpha = 0.85f)
                                },
                                fontSize = if (isSelected) 11.sp else 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }

                    // Ruler Expansion Toggle button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isExpanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            )
                            .clickable {
                                tactile.triggerSnap()
                                onExpandedChange(!isExpanded)
                                lastInteractionTime = System.currentTimeMillis()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Expand Continuous Zoom Wheel",
                            tint = if (isExpanded) MaterialTheme.colorScheme.onSecondaryContainer else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    // Front Camera: 1.0x (normal view angle) and 1.4x (portrait mode crop)
                    val frontOptions = listOf(1.0f to "1x", 1.4f to "1.4x")
                    frontOptions.forEach { (zoomStop, label) ->
                        val isSelected = (zoomStop == 1.0f && currentZoomRatio < 1.2f) ||
                                (zoomStop == 1.4f && currentZoomRatio >= 1.2f)

                        val pillWidth by animateDpAsState(
                            targetValue = if (isSelected) 60.dp else 42.dp,
                            animationSpec = GXVMotion.pillMorph(),
                            label = "frontLensPillWidth"
                        )

                        Box(
                            modifier = Modifier
                                .width(pillWidth)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                                .clickable {
                                    tactile.triggerSnap()
                                    onZoomStep(zoomStop)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    Color.White.copy(alpha = 0.85f)
                                },
                                fontSize = if (isSelected) 12.sp else 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
