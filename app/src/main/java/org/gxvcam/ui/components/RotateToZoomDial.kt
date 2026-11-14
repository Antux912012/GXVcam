package org.gxvcam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.OpticalLens
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

val ROTARY_ZOOM_DETENTS = listOf(0.6f, 1.0f, 1.5f, 2.0f, 3.0f, 6.0f)

/**
 * Pure rotary zoom math helper for the "Rotate to Zoom" cinema dial.
 *
 * Maps angular finger drag around `(centerX, centerY)` into logarithmic zoom steps
 * with magnetic snap detents at `[0.6f, 1.0f, 1.5f, 2.0f, 3.0f, 6.0f]`.
 */
fun computeRotaryZoomStep(
    touchX: Float,
    touchY: Float,
    centerX: Float = 0f,
    centerY: Float = 0f,
    previousAngleRad: Float? = null,
    currentZoom: Float = 1.0f,
    minZoom: Float = OpticalLens.MIN_ZOOM,
    maxZoom: Float = OpticalLens.MAX_ZOOM
): Pair<Float, Float> {
    val angleRad = atan2((touchY - centerY).toDouble(), (touchX - centerX).toDouble()).toFloat()
    if (previousAngleRad == null) {
        return currentZoom.coerceIn(minZoom, maxZoom) to angleRad
    }

    var deltaAngle = angleRad - previousAngleRad
    while (deltaAngle > Math.PI.toFloat()) deltaAngle -= (2f * Math.PI.toFloat())
    while (deltaAngle < -Math.PI.toFloat()) deltaAngle += (2f * Math.PI.toFloat())

    // One full 360-degree turn spans roughly ln(20 / 0.6) ~= 3.5 log units
    val logZoom = ln(currentZoom.coerceIn(minZoom, maxZoom).toDouble()) + deltaAngle * 0.85
    val rawZoom = exp(logZoom).toFloat().coerceIn(minZoom, maxZoom)

    // Soft magnetic detent snap within 3% of a Signature 27 prime stop
    val snapped = ROTARY_ZOOM_DETENTS.firstOrNull { stop ->
        abs(rawZoom - stop) / stop < 0.025f
    } ?: rawZoom

    return snapped.coerceIn(minZoom, maxZoom) to angleRad
}

@Composable
fun RotateToZoomDial(
    currentZoomRatio: Float,
    isBackCamera: Boolean = true,
    onZoomChanged: (Float) -> Unit,
    onZoomDetentSelected: (Float) -> Unit = onZoomChanged,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()
    val primaryColor = MaterialTheme.colorScheme.primary
    val minZoom = if (isBackCamera) OpticalLens.MIN_ZOOM else 1.0f
    val maxZoom = if (isBackCamera) OpticalLens.MAX_ZOOM else 4.0f

    var dialSize by remember { mutableStateOf(IntSize.Zero) }
    var lastDetent by remember { mutableFloatStateOf(currentZoomRatio) }
    val latestZoom by rememberUpdatedState(currentZoomRatio)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Quick prime detent pills (0.6x, 1x, 1.5x, 2x, 3x, 6x)
        val detents = if (isBackCamera) ROTARY_ZOOM_DETENTS else listOf(1.0f, 1.4f, 2.0f)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            detents.forEach { stop ->
                val isSelected = abs(currentZoomRatio - stop) < 0.06f
                val label = when {
                    stop == 0.6f -> ".6"
                    stop == stop.toInt().toFloat() -> "${stop.toInt()}x"
                    else -> "${stop}x"
                }
                Surface(
                    onClick = {
                        tactile.triggerScrollSnap()
                        onZoomDetentSelected(stop)
                    },
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Tactile Rotary Cinema Wheel
        Box(
            modifier = Modifier
                .size(156.dp)
                .onSizeChanged { dialSize = it }
                .pointerInput(isBackCamera) {
                    var prevAngle: Float? = null
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val cx = dialSize.width / 2f
                            val cy = dialSize.height / 2f
                            val (_, angle) = computeRotaryZoomStep(
                                touchX = startOffset.x,
                                touchY = startOffset.y,
                                centerX = cx,
                                centerY = cy,
                                previousAngleRad = null,
                                currentZoom = latestZoom,
                                minZoom = minZoom,
                                maxZoom = maxZoom
                            )
                            prevAngle = angle
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val cx = dialSize.width / 2f
                            val cy = dialSize.height / 2f
                            val (nextZoom, angle) = computeRotaryZoomStep(
                                touchX = change.position.x,
                                touchY = change.position.y,
                                centerX = cx,
                                centerY = cy,
                                previousAngleRad = prevAngle,
                                currentZoom = latestZoom,
                                minZoom = minZoom,
                                maxZoom = maxZoom
                            )
                            prevAngle = angle

                            val nearestDetent = ROTARY_ZOOM_DETENTS.firstOrNull { abs(it - nextZoom) < 0.03f }
                            if (nearestDetent != null && abs(nearestDetent - lastDetent) > 0.05f) {
                                tactile.triggerScrollSnap()
                                lastDetent = nearestDetent
                            } else if (abs(nextZoom - lastDetent) >= 0.1f) {
                                tactile.triggerScrollTick()
                                lastDetent = nextZoom
                            }
                            onZoomChanged(nextZoom)
                        },
                        onDragEnd = { prevAngle = null },
                        onDragCancel = { prevAngle = null }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f - 10.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)

                drawCircle(
                    color = Color.White.copy(alpha = 0.10f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 16.dp.toPx())
                )

                val progress = ((ln(currentZoomRatio.coerceIn(minZoom, maxZoom)) - ln(minZoom)) /
                    (ln(maxZoom) - ln(minZoom))).coerceIn(0f, 1f)
                val rotationOffset = progress * (2f * Math.PI.toFloat())

                for (i in 0 until 36) {
                    val theta = (i / 36f) * (2f * Math.PI.toFloat()) - rotationOffset
                    val isMajor = (i % 6 == 0)
                    val innerR = radius - if (isMajor) 12.dp.toPx() else 7.dp.toPx()
                    val outerR = radius + 4.dp.toPx()
                    drawLine(
                        color = if (isMajor) primaryColor else Color.White.copy(alpha = 0.45f),
                        start = Offset(center.x + innerR * cos(theta), center.y + innerR * sin(theta)),
                        end = Offset(center.x + outerR * cos(theta), center.y + outerR * sin(theta)),
                        strokeWidth = if (isMajor) 2.5.dp.toPx() else 1.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Top needle
                drawLine(
                    color = primaryColor,
                    start = Offset(center.x, center.y - radius - 8.dp.toPx()),
                    end = Offset(center.x, center.y - radius + 10.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xAA000000)
            ) {
                val zoomText = if (currentZoomRatio >= 10f) {
                    String.format(Locale.US, "%.0fx", currentZoomRatio)
                } else {
                    String.format(Locale.US, "%.1fx", currentZoomRatio)
                }
                Text(
                    text = zoomText,
                    color = primaryColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
