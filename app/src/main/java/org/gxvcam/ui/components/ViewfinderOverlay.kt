package org.gxvcam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GridType
import org.gxvcam.ui.state.OpticalLens
import org.gxvcam.ui.theme.GXVMotion
import org.gxvcam.ui.theme.OledBlack
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun GXVViewfinderContainer(
    aspectRatio: AspectRatio,
    gridEnabled: Boolean,
    gridType: GridType = GridType.RULE_OF_THIRDS,
    showHorizon: Boolean,
    levelHorizonDegrees: Float,
    deviceCardinalAngle: Int = 0,
    isHorizonPlanar: Boolean = false,
    currentZoom: Float,
    isBackCamera: Boolean = true,
    showSplitAeAfLocks: Boolean = false,
    isAeLocked: Boolean = false,
    isAfLocked: Boolean = false,
    isAeAfLocked: Boolean = isAeLocked && isAfLocked,
    onEvent: (GXVcamEvent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val tactile = rememberTactileFeedback()
    val haptic = LocalHapticFeedback.current

    // Discrete 1/3 EV stops from +3.0 to -3.0 EV
    val evSteps = remember {
        listOf(
            3.0f, 2.7f, 2.3f, 2.0f, 1.7f, 1.3f, 1.0f, 0.7f, 0.3f,
            0.0f,
            -0.3f, -0.7f, -1.0f, -1.3f, -1.7f, -2.0f, -2.3f, -2.7f, -3.0f
        )
    }

    fun triggerNiagaraTick() {
        tactile.triggerScrollTick()
    }

    // Tap-to-focus indicator & AE/AF Lock state
    var focusPosition by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }
    var focusEvSlider by remember { mutableFloatStateOf(0f) }
    var isEvExpanded by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    var lastVibratedIndex by remember { mutableIntStateOf(9) } // 0.0 is index 9
    var currentScrubY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val anyLocked = isAeLocked || isAfLocked || isAeAfLocked

    // Auto-dismiss focus ring if not locked and not scrubbing EV
    LaunchedEffect(focusPosition, anyLocked, isEvExpanded) {
        if (focusPosition != null) {
            showFocusRing = true
            if (!anyLocked && !isEvExpanded) {
                delay(4000)
                showFocusRing = false
            }
        }
    }

    // Auto-collapse expanded EV bar after 3.0s of inactivity
    LaunchedEffect(isEvExpanded, lastInteractionTime) {
        if (isEvExpanded) {
            delay(3200)
            isEvExpanded = false
        }
    }

    // Horizon leveler haptic snap trigger (suppressed when phone is planar / tilted > 63°)
    var wasLevel by remember { mutableStateOf(false) }
    val isLevel = !isHorizonPlanar && abs(levelHorizonDegrees) < 1.0f
    LaunchedEffect(isLevel) {
        if (isLevel && !wasLevel) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasLevel = isLevel
    }

    val cornerRadius = 28.dp
    val latestZoom by rememberUpdatedState(currentZoom)
    val latestContainerSize by rememberUpdatedState(containerSize)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack),
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

        val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 9f / 20f
        val matchHeightFirst = animatedAspectRatio < containerRatio

        Box(
            modifier = Modifier
                .then(if (matchHeightFirst) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                .aspectRatio(animatedAspectRatio, matchHeightConstraintsFirst = matchHeightFirst)
                .clip(RoundedCornerShape(cornerRadius))
                .clipToBounds()
                .onSizeChanged { containerSize = it }
                .pointerInput(isBackCamera) {
                    var lastZoomTickStep = (currentZoom * 10f).roundToInt()
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            val base = latestZoom
                            val minZoom = if (isBackCamera) OpticalLens.MIN_ZOOM else 1.0f
                            val maxZoom = if (isBackCamera) OpticalLens.MAX_ZOOM else 4.0f
                            val targetZoom = (base * zoom).coerceIn(minZoom, maxZoom)
                            val step = (targetZoom * 10f).roundToInt()
                            if (step != lastZoomTickStep) {
                                if (step == 10 || (isBackCamera && (step == 6 || step == 20 || step == 30))) {
                                    tactile.triggerScrollSnap()
                                } else {
                                    tactile.triggerScrollTick()
                                }
                                lastZoomTickStep = step
                            }
                            onEvent(GXVcamEvent.OnZoomChanged(targetZoom))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            focusPosition = offset
                            focusEvSlider = 0f
                            isEvExpanded = false
                            lastVibratedIndex = 9 // reset to 0.0 EV
                            showFocusRing = true
                            val size = latestContainerSize
                            if (size.width > 0 && size.height > 0) {
                                onEvent(
                                    GXVcamEvent.OnTapToFocus(
                                        x = offset.x,
                                        y = offset.y,
                                        width = size.width,
                                        height = size.height
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
                                    locked = !anyLocked,
                                    x = offset.x,
                                    y = offset.y
                                )
                            )
                        }
                    )
                }
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

            // Calibrated Gyroscopic Horizon Leveler (Portrait & Landscape aware)
            if (showHorizon) {
                val targetScreenAngle = -(deviceCardinalAngle.toFloat() + levelHorizonDegrees)
                var unwrappedScreenAngle by remember { mutableFloatStateOf(targetScreenAngle) }
                SideEffect {
                    val delta = ((targetScreenAngle - unwrappedScreenAngle + 540f) % 360f + 360f) % 360f - 180f
                    unwrappedScreenAngle += delta
                }
                val animatedHorizonAngle by animateFloatAsState(
                    targetValue = unwrappedScreenAngle,
                    animationSpec = GXVMotion.spatialSmooth(),
                    label = "horizonRotation",
                )

                val targetCardinalScreenAngle = -deviceCardinalAngle.toFloat()
                var unwrappedCardinalAngle by remember { mutableFloatStateOf(targetCardinalScreenAngle) }
                SideEffect {
                    val delta = ((targetCardinalScreenAngle - unwrappedCardinalAngle + 540f) % 360f + 360f) % 360f - 180f
                    unwrappedCardinalAngle += delta
                }
                val animatedCardinalAngle by animateFloatAsState(
                    targetValue = unwrappedCardinalAngle,
                    animationSpec = GXVMotion.spatialSmooth(),
                    label = "horizonCardinalRotation",
                )

                val horizonAlpha = if (isHorizonPlanar) 0.20f else if (isLevel) 0.95f else 0.65f
                val horizonColor by animateColorAsState(
                    targetValue = if (isLevel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = horizonAlpha),
                    animationSpec = GXVMotion.fastEffects(),
                    label = "horizonColor",
                )
                val referenceColor = Color.White.copy(alpha = if (isHorizonPlanar) 0.14f else 0.35f)

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier.size(220.dp),
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val wingOuter = size.width * 0.44f
                        val wingInner = size.width * 0.12f

                        // Fixed cardinal horizon reference ticks
                        rotate(degrees = animatedCardinalAngle, pivot = center) {
                            val tickOuter = wingOuter + 8.dp.toPx()
                            val tickInner = wingOuter + 1.dp.toPx()
                            drawLine(
                                color = referenceColor,
                                start = Offset(center.x - tickOuter, center.y),
                                end = Offset(center.x - tickInner, center.y),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = referenceColor,
                                start = Offset(center.x + tickInner, center.y),
                                end = Offset(center.x + tickOuter, center.y),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }

                        // Moving horizon wings + center pivot dot
                        rotate(degrees = animatedHorizonAngle, pivot = center) {
                            drawLine(
                                color = horizonColor,
                                start = Offset(center.x - wingOuter, center.y),
                                end = Offset(center.x - wingInner, center.y),
                                strokeWidth = if (isLevel) 2.5.dp.toPx() else 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = horizonColor,
                                start = Offset(center.x + wingInner, center.y),
                                end = Offset(center.x + wingOuter, center.y),
                                strokeWidth = if (isLevel) 2.5.dp.toPx() else 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            drawCircle(
                                color = horizonColor,
                                radius = if (isLevel) 3.5.dp.toPx() else 2.5.dp.toPx(),
                                center = center,
                            )
                        }
                    }

                    if (!isHorizonPlanar) {
                        val roundedDeg = levelHorizonDegrees.roundToInt()
                        val degLabel = if (isLevel) "0°" else String.format(Locale.US, "%+d°", roundedDeg)
                        Text(
                            text = degLabel,
                            fontSize = 10.sp,
                            fontWeight = if (isLevel) FontWeight.Bold else FontWeight.Medium,
                            color = horizonColor,
                            modifier = Modifier.graphicsLayer {
                                rotationZ = animatedCardinalAngle
                                val rad = Math.toRadians(animatedCardinalAngle.toDouble())
                                val shiftPx = 22.dp.toPx()
                                translationX = (-sin(rad) * shiftPx).toFloat()
                                translationY = (cos(rad) * shiftPx).toFloat()
                            },
                        )
                    }
                }
            }

            // Professional Focus Reticle, AE/AF Lock & Niagara Expandable Exposure Bar
            if (showFocusRing && focusPosition != null) {
                val pos = focusPosition!!
                val ringScale by animateFloatAsState(
                    targetValue = if (showFocusRing) 1.0f else 0.7f,
                    animationSpec = GXVMotion.spatialBouncy(),
                    label = "focusRingScale",
                )

                val density = LocalDensity.current
                val ringHalfSizePx = with(density) { 38.dp.toPx() }

                val isNearRightEdge = containerSize.width > 0 && pos.x > (containerSize.width * 0.72f)
                val sliderOffsetXPx = with(density) {
                    if (isNearRightEdge) {
                        if (isEvExpanded) -135.dp.toPx() else -52.dp.toPx()
                    } else {
                        48.dp.toPx()
                    }
                }

                val activeFocusColor = if (anyLocked) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary

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

                // 2. Lock Badges (Split AE-L / AF-L in Pro Mode; iOS-style AE/AF LOCK pill only when locked in Photo)
                if (showSplitAeAfLocks) {
                    Row(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - with(density) { 56.dp.toPx() }).roundToInt(),
                                    (pos.y - with(density) { 64.dp.toPx() }).roundToInt()
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // AE-L Lock Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAeLocked) Color(0xDDFFB74D) else Color(0xAA000000),
                            border = BorderStroke(1.dp, if (isAeLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEvent(
                                    GXVcamEvent.SetAeLock(
                                        locked = !isAeLocked,
                                        x = pos.x,
                                        y = pos.y
                                    )
                                )
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAeLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "AE Lock",
                                    tint = if (isAeLocked) Color.Black else Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "AE-L",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = if (isAeLocked) Color.Black else Color.White
                                )
                            }
                        }

                        // AF-L Lock Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAfLocked) Color(0xDDFFB74D) else Color(0xAA000000),
                            border = BorderStroke(1.dp, if (isAfLocked) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEvent(
                                    GXVcamEvent.SetAfLock(
                                        locked = !isAfLocked,
                                        x = pos.x,
                                        y = pos.y
                                    )
                                )
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "AF Lock",
                                    tint = if (isAfLocked) Color.Black else Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "AF-L",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = if (isAfLocked) Color.Black else Color.White
                                )
                            }
                        }
                    }
                } else if (anyLocked) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xDDFFB74D),
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - with(density) { 44.dp.toPx() }).roundToInt(),
                                    (pos.y - with(density) { 62.dp.toPx() }).roundToInt()
                                )
                            }
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEvent(GXVcamEvent.SetAeAfLock(locked = false, x = pos.x, y = pos.y))
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "AE/AF Locked",
                                tint = Color.Black,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "AE/AF LOCK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }

                // 3. Niagara Launcher-Style Expandable Exposure Compensation Bar
                val animatedBarHeight by animateDpAsState(
                    targetValue = if (isEvExpanded) 220.dp else 72.dp,
                    animationSpec = GXVMotion.spatialBouncy(),
                    label = "evBarHeight"
                )
                val animatedBarWidth by animateDpAsState(
                    targetValue = if (isEvExpanded) 66.dp else 38.dp,
                    animationSpec = GXVMotion.spatialSmooth(),
                    label = "evBarWidth"
                )

                val barHeightPx = with(density) { animatedBarHeight.toPx() }
                val clampedOffsetY = if (containerSize.height > 0) {
                    (pos.y - barHeightPx / 2f).coerceIn(
                        with(density) { 70.dp.toPx() },
                        (containerSize.height - barHeightPx - with(density) { 90.dp.toPx() }).coerceAtLeast(0f)
                    )
                } else {
                    pos.y - barHeightPx / 2f
                }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x + sliderOffsetXPx).roundToInt(),
                                clampedOffsetY.roundToInt()
                            )
                        }
                        .width(animatedBarWidth)
                        .height(animatedBarHeight)
                        .clip(RoundedCornerShape(if (isEvExpanded) 24.dp else 19.dp))
                        .background(if (isEvExpanded) Color(0xF0141414) else Color.Black.copy(alpha = 0.65f))
                        .border(
                            1.dp,
                            if (isEvExpanded) activeFocusColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.20f),
                            RoundedCornerShape(if (isEvExpanded) 24.dp else 19.dp)
                        )
                        .clickable(enabled = !isEvExpanded) {
                            isEvExpanded = true
                            lastInteractionTime = System.currentTimeMillis()
                            // Set initial touch center at current EV
                            val currentIdx = evSteps.indexOfFirst { abs(it - focusEvSlider) < 0.15f }.coerceAtLeast(0)
                            currentScrubY = (currentIdx.toFloat() / (evSteps.size - 1)) * barHeightPx
                            triggerNiagaraTick()
                        }
                        .pointerInput(isEvExpanded) {
                            if (isEvExpanded) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        lastInteractionTime = System.currentTimeMillis()
                                        currentScrubY = offset.y
                                        val idx = ((offset.y / barHeightPx) * (evSteps.size - 1)).roundToInt().coerceIn(0, evSteps.size - 1)
                                        if (idx != lastVibratedIndex) {
                                            lastVibratedIndex = idx
                                            triggerNiagaraTick()
                                            val ev = evSteps[idx]
                                            focusEvSlider = ev
                                            onEvent(GXVcamEvent.SetTapFocusEv(ev))
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        lastInteractionTime = System.currentTimeMillis()
                                        val y = change.position.y
                                        currentScrubY = y
                                        val idx = ((y / barHeightPx) * (evSteps.size - 1)).roundToInt().coerceIn(0, evSteps.size - 1)
                                        if (idx != lastVibratedIndex) {
                                            lastVibratedIndex = idx
                                            triggerNiagaraTick()
                                            val ev = evSteps[idx]
                                            focusEvSlider = ev
                                            onEvent(GXVcamEvent.SetTapFocusEv(ev))
                                        }
                                    },
                                    onDragEnd = {
                                        lastInteractionTime = System.currentTimeMillis()
                                    }
                                )
                            }
                        }
                        .padding(vertical = if (isEvExpanded) 8.dp else 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isEvExpanded) {
                        // Compact Mode: Sun Icon + Live EV Readout + Expansion cue
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = "EV Compensation",
                                tint = if (focusEvSlider != 0f) activeFocusColor else Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (focusEvSlider >= 0) String.format(Locale.US, "+%.1f", focusEvSlider) else String.format(Locale.US, "%.1f", focusEvSlider),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (focusEvSlider != 0f) activeFocusColor else Color.White
                            )
                            Canvas(modifier = Modifier.width(12.dp).height(12.dp)) {
                                drawCircle(Color.White.copy(alpha = 0.5f), radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, size.height * 0.2f))
                                drawCircle(Color.White.copy(alpha = 0.5f), radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, size.height * 0.5f))
                                drawCircle(Color.White.copy(alpha = 0.5f), radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, size.height * 0.8f))
                            }
                        }
                    } else {
                        // Expanded Mode: Niagara Launcher Style Fluid Alphabet/Step Wave Bar
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Step indicators with Niagara Wave displacement
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val waveRadiusPx = with(density) { 55.dp.toPx() }

                                evSteps.forEachIndexed { i, step ->
                                    val stepY = (i.toFloat() / (evSteps.size - 1)) * barHeightPx
                                    val dist = abs(stepY - currentScrubY)
                                    val isInWave = dist < waveRadiusPx
                                    val waveFactor = if (isInWave) {
                                        ((1f + cos(Math.PI * (dist / waveRadiusPx))) / 2f).toFloat()
                                    } else {
                                        0f
                                    }

                                    val isMajor = (step == 3.0f || step == 2.0f || step == 1.0f || step == 0.0f || step == -1.0f || step == -2.0f || step == -3.0f)
                                    val isSelected = (i == lastVibratedIndex)

                                    val waveShift = waveFactor * with(density) { 14.dp.toPx() }
                                    val textScale = 1f + waveFactor * 0.40f

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Start,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                translationX = waveShift
                                                scaleX = textScale
                                                scaleY = textScale
                                            }
                                    ) {
                                        // Left tick line
                                        Box(
                                            modifier = Modifier
                                                .width(if (isMajor || isSelected) 12.dp else 6.dp)
                                                .height(if (isSelected) 2.5.dp else 1.5.dp)
                                                .background(
                                                    if (isSelected) activeFocusColor
                                                    else if (isMajor) Color.White.copy(alpha = 0.85f)
                                                    else Color.White.copy(alpha = 0.35f),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )

                                        Spacer(modifier = Modifier.width(4.dp))

                                        // Value Label (Always show for major stops or when magnified by Niagara wave)
                                        if (isMajor || waveFactor > 0.45f) {
                                            Text(
                                                text = if (step == 0f) "0" else if (isMajor) String.format(Locale.US, "%+.0f", step) else String.format(Locale.US, "%+.1f", step),
                                                fontSize = if (isSelected) 11.sp else 9.sp,
                                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
                                                color = if (isSelected) activeFocusColor else Color.White.copy(alpha = 0.75f + waveFactor * 0.25f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Niagara Floating Magnifier Bubble following finger
                            val bubbleOffsetXPx = with(density) {
                                if (isNearRightEdge) -62.dp.toPx() else 54.dp.toPx()
                            }
                            val clampedBubbleY = currentScrubY.coerceIn(
                                with(density) { 12.dp.toPx() },
                                (barHeightPx - with(density) { 12.dp.toPx() })
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = activeFocusColor,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            bubbleOffsetXPx.roundToInt(),
                                            (clampedBubbleY - with(density) { 15.dp.toPx() }).roundToInt()
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WbSunny,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = if (focusEvSlider >= 0) String.format(Locale.US, "+%.1f", focusEvSlider) else String.format(Locale.US, "%.1f", focusEvSlider),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
