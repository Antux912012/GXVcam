package org.gxvcam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GridType
import org.gxvcam.ui.theme.GXVMotion
import org.gxvcam.ui.theme.OledBlack
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GXVViewfinderContainer(
    aspectRatio: AspectRatio,
    gridEnabled: Boolean,
    levelHorizonDegrees: Float,
    currentZoom: Float,
    onEvent: (GXVcamEvent) -> Unit,
    modifier: Modifier = Modifier,
    gridType: GridType = GridType.RULE_OF_THIRDS,
    showHorizon: Boolean = true,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Tap-to-focus state
    var focusPosition by remember { mutableStateOf<Offset?>(null) }
    var focusEvSlider by remember { mutableFloatStateOf(0f) }
    var showFocusRing by remember { mutableStateOf(false) }

    // Auto-dismiss focus ring after 3.5 seconds of inactivity
    LaunchedEffect(focusPosition) {
        if (focusPosition != null) {
            showFocusRing = true
            delay(3500)
            showFocusRing = false
        }
    }

    // Horizon leveler haptic snap trigger
    var wasLevel by remember { mutableStateOf(false) }
    val isLevel = abs(levelHorizonDegrees) < 1.0f
    LaunchedEffect(isLevel) {
        if (isLevel && !wasLevel) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasLevel = isLevel
    }

    // M3 Expressive Corner shape & Aspect ratio animation
    val cornerRadius = 28.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        onEvent(GXVcamEvent.OnZoomChanged(currentZoom * zoom))
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    focusPosition = offset
                    focusEvSlider = 0f
                    showFocusRing = true
                    if (containerSize.width > 0 && containerSize.height > 0) {
                        onEvent(
                            GXVcamEvent.OnTapToFocus(
                                x = offset.x,
                                y = offset.y,
                                width = containerSize.width,
                                height = containerSize.height
                            )
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Animated Viewfinder Aspect Ratio Box
        val targetRatio = when (aspectRatio) {
            AspectRatio.RATIO_4_3 -> 3f / 4f // Portrait 4:3
            AspectRatio.RATIO_16_9 -> 9f / 16f // Portrait 16:9
            AspectRatio.RATIO_1_1 -> 1.0f // Square 1:1
            AspectRatio.RATIO_3_2 -> 2f / 3f // 3:2
            AspectRatio.RATIO_21_9 -> 9f / 21f // 21:9
        }

        val animatedAspectRatio by animateFloatAsState(
            targetValue = targetRatio,
            animationSpec = GXVMotion.spatialSmooth(),
            label = "viewfinderRatio"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(animatedAspectRatio)
                .clip(RoundedCornerShape(cornerRadius))
                .clipToBounds()
        ) {
            // Camera Preview Content
            content()

            // Composition Grid
            if (gridEnabled && gridType != GridType.NONE) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridColor = Color.White.copy(alpha = 0.28f)
                    val stroke = 1.dp.toPx()

                    when (gridType) {
                        GridType.RULE_OF_THIRDS -> {
                            drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = stroke)
                        }
                        GridType.GOLDEN_RATIO -> {
                            val phi1 = 0.382f
                            val phi2 = 0.618f
                            drawLine(gridColor, Offset(w * phi1, 0f), Offset(w * phi1, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(w * phi2, 0f), Offset(w * phi2, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, h * phi1), Offset(w, h * phi1), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, h * phi2), Offset(w, h * phi2), strokeWidth = stroke)
                        }
                        GridType.CROSSHAIR -> {
                            drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = stroke)
                            drawCircle(
                                color = gridColor,
                                radius = 24.dp.toPx(),
                                center = Offset(w / 2f, h / 2f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                            )
                        }
                        GridType.NONE -> {}
                    }
                }
            }

            // Gyroscopic Horizon Leveler
            if (showHorizon) {
                val horizonColor by animateColorAsState(
                    targetValue = if (isLevel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.45f),
                    animationSpec = GXVMotion.fastEffects(),
                    label = "horizonColor",
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(140.dp)
                        .height(24.dp)
                        .rotate(-levelHorizonDegrees),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerY = size.height / 2f
                        drawLine(
                            color = horizonColor,
                            start = Offset(0f, centerY),
                            end = Offset(size.width * 0.38f, centerY),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = horizonColor,
                            start = Offset(size.width * 0.62f, centerY),
                            end = Offset(size.width, centerY),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = horizonColor,
                            radius = 3.dp.toPx(),
                            center = Offset(size.width / 2f, centerY),
                        )
                    }
                }
            }

            // M3 Expressive Focus Indicator Ring & Vertical EV Slider
            if (showFocusRing && focusPosition != null) {
                val pos = focusPosition!!
                val ringScale by animateFloatAsState(
                    targetValue = if (showFocusRing) 1.0f else 0.7f,
                    animationSpec = GXVMotion.spatialBouncy(),
                    label = "focusRingScale",
                )

                val density = LocalDensity.current
                val ringHalfSizePx = with(density) { 40.dp.toPx() }
                val sliderOffsetXPx = with(density) { 46.dp.toPx() }
                val sliderOffsetYPx = with(density) { 50.dp.toPx() }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x - ringHalfSizePx).roundToInt(),
                                (pos.y - ringHalfSizePx).roundToInt(),
                            )
                        }
                        .size(80.dp)
                        .scale(ringScale)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                )

                // Vertical quick exposure bias slider adjacent to the focus ring
                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x + sliderOffsetXPx).roundToInt(),
                                (pos.y - sliderOffsetYPx).roundToInt(),
                            )
                        }
                        .width(36.dp)
                        .height(100.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = "EV Bias",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Slider(
                        value = focusEvSlider,
                        onValueChange = {
                            focusEvSlider = it
                            onEvent(GXVcamEvent.OnProSettingChanged { s -> s.copy(evIndex = it.toInt()) })
                        },
                        valueRange = -4f..4f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier
                            .fillMaxHeight(0.8f)
                            .width(28.dp),
                    )
                }
            }
        }
    }
}
