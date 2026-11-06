package org.gxvcam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    gridType: GridType = GridType.RULE_OF_THIRDS,
    showHorizon: Boolean,
    levelHorizonDegrees: Float,
    currentZoom: Float,
    isAeAfLocked: Boolean = false,
    onEvent: (GXVcamEvent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Tap-to-focus indicator & AE/AF Lock state
    var focusPosition by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }
    var focusEvSlider by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(focusPosition, isAeAfLocked) {
        if (focusPosition != null) {
            showFocusRing = true
            if (!isAeAfLocked) {
                delay(4000)
                showFocusRing = false
            }
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
                detectTapGestures(
                    onTap = { offset ->
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
                    },
                    onLongPress = { offset ->
                        focusPosition = offset
                        showFocusRing = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEvent(
                            GXVcamEvent.SetAeAfLock(
                                locked = true,
                                x = offset.x,
                                y = offset.y
                            )
                        )
                    }
                )
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

            // Sony Alpha Inspired Composition Grids
            if (gridEnabled && gridType != GridType.NONE) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridColor = Color.White.copy(alpha = 0.28f)
                    val stroke = 1.dp.toPx()

                    when (gridType) {
                        GridType.RULE_OF_THIRDS -> {
                            // 3x3 Standard Thirds
                            drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = stroke)
                            drawLine(gridColor, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = stroke)
                        }
                        GridType.SQUARE -> {
                            // 4x4 Square Grid (Sony Alpha)
                            for (i in 1..3) {
                                val x = w * (i / 4f)
                                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = stroke)
                            }
                            for (i in 1..3) {
                                val y = h * (i / 4f)
                                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke)
                            }
                        }
                        GridType.DIAG_SQUARE -> {
                            // Diag. + Square Grid (Sony Alpha Signature)
                            for (i in 1..3) {
                                val x = w * (i / 4f)
                                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = stroke)
                            }
                            for (i in 1..3) {
                                val y = h * (i / 4f)
                                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke)
                            }
                            // Corner-to-corner diagonal perspective lines
                            val diagColor = gridColor.copy(alpha = 0.22f)
                            drawLine(diagColor, Offset(0f, 0f), Offset(w, h), strokeWidth = stroke)
                            drawLine(diagColor, Offset(w, 0f), Offset(0f, h), strokeWidth = stroke)
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

            // Calibrated Gyroscopic Horizon Leveler
            if (showHorizon) {
                val horizonColor by animateColorAsState(
                    targetValue = if (isLevel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.45f),
                    animationSpec = GXVMotion.fastEffects(),
                    label = "horizonColor",
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(60.dp)
                            .rotate(-levelHorizonDegrees),
                    ) {
                        val centerY = size.height / 2f
                        // Left & Right horizon wings
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

            // Professional Focus Reticle, AE/AF Lock & Vertical Exposure Slider
            if (showFocusRing && focusPosition != null) {
                val pos = focusPosition!!
                val ringScale by animateFloatAsState(
                    targetValue = if (showFocusRing) 1.0f else 0.7f,
                    animationSpec = GXVMotion.spatialBouncy(),
                    label = "focusRingScale",
                )

                val density = LocalDensity.current
                val ringHalfSizePx = with(density) { 38.dp.toPx() }
                val sliderOffsetXPx = with(density) { 46.dp.toPx() }
                val sliderOffsetYPx = with(density) { 55.dp.toPx() }

                val activeFocusColor = if (isAeAfLocked) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary

                // 1. Focus Reticle Box with Sony Alpha corner brackets
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x - ringHalfSizePx).roundToInt(),
                                (pos.y - ringHalfSizePx).roundToInt(),
                            )
                        }
                        .size(76.dp)
                        .scale(ringScale),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 2.dp.toPx()
                        val cornerLen = 14.dp.toPx()
                        val w = size.width
                        val h = size.height

                        // Top-Left corner
                        drawLine(activeFocusColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeWidth = stroke)
                        drawLine(activeFocusColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeWidth = stroke)

                        // Top-Right corner
                        drawLine(activeFocusColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeWidth = stroke)
                        drawLine(activeFocusColor, Offset(w, 0f), Offset(w, cornerLen), strokeWidth = stroke)

                        // Bottom-Left corner
                        drawLine(activeFocusColor, Offset(0f, h), Offset(cornerLen, h), strokeWidth = stroke)
                        drawLine(activeFocusColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeWidth = stroke)

                        // Bottom-Right corner
                        drawLine(activeFocusColor, Offset(w, h), Offset(w - cornerLen, h), strokeWidth = stroke)
                        drawLine(activeFocusColor, Offset(w, h), Offset(w, h - cornerLen), strokeWidth = stroke)

                        // Center spot
                        drawCircle(activeFocusColor, radius = 2.5.dp.toPx(), center = Offset(w / 2f, h / 2f))
                    }
                }

                // 2. AE/AF Lock Badge Pill (Above Reticle)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAeAfLocked) Color(0xDDFFB74D) else Color(0xAA000000),
                    border = BorderStroke(1.dp, if (isAeAfLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x - with(density) { 50.dp.toPx() }).roundToInt(),
                                (pos.y - with(density) { 62.dp.toPx() }).roundToInt()
                            )
                        }
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEvent(
                                GXVcamEvent.SetAeAfLock(
                                    locked = !isAeAfLocked,
                                    x = pos.x,
                                    y = pos.y
                                )
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isAeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "AE/AF Lock",
                            tint = if (isAeAfLocked) Color.Black else Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (isAeAfLocked) "AE/AF LOCKED" else "AE-L / AF-L",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAeAfLocked) Color.Black else Color.White
                        )
                    }
                }

                // 3. Vertical Quick Exposure Bias Drag Slider (Adjacent to Focus Reticle)
                var dragAccumulator by remember { mutableFloatStateOf(0f) }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x + sliderOffsetXPx).roundToInt(),
                                (pos.y - sliderOffsetYPx).roundToInt(),
                            )
                        }
                        .width(36.dp)
                        .height(110.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { dragAccumulator = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    // Dragging UP decreases Y, which should INCREASE exposure bias
                                    dragAccumulator -= dragAmount
                                    val newEv = (focusEvSlider + dragAccumulator / 60f).coerceIn(-4f, 4f)
                                    val roundedEv = (Math.round(newEv * 3f) / 3f)
                                    focusEvSlider = roundedEv
                                    onEvent(GXVcamEvent.SetTapFocusEv(roundedEv))
                                }
                            )
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Icon(
                            imageVector = Icons.Default.WbSunny,
                            contentDescription = "EV Bias",
                            tint = activeFocusColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (focusEvSlider >= 0) String.format("+%.1f", focusEvSlider) else String.format("%.1f", focusEvSlider),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (focusEvSlider != 0f) activeFocusColor else Color.White
                        )
                        Canvas(modifier = Modifier.width(14.dp).height(28.dp)) {
                            val strokeW = 1.dp.toPx()
                            drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = strokeW * 1.5f)
                            drawLine(Color.White.copy(alpha = 0.2f), Offset(3.dp.toPx(), size.height * 0.2f), Offset(size.width - 3.dp.toPx(), size.height * 0.2f), strokeWidth = strokeW)
                            drawLine(Color.White.copy(alpha = 0.2f), Offset(3.dp.toPx(), size.height * 0.8f), Offset(size.width - 3.dp.toPx(), size.height * 0.8f), strokeWidth = strokeW)
                        }
                    }
                }
            }
        }
    }
}
