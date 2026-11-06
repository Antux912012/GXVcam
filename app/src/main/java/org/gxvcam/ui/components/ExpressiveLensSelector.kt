package org.gxvcam.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.OpticalLens
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion
import kotlin.math.roundToInt

@Composable
fun ExpressiveLensSelector(
    activeLens: OpticalLens,
    currentZoomRatio: Float,
    onLensSelected: (OpticalLens) -> Unit,
    onZoomChanged: (Float) -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isDraggingSlider by remember { mutableStateOf(false) }
    var lastHapticStep by remember { mutableIntStateOf((currentZoomRatio * 10f).roundToInt()) }

    val pillContainerColor = if (isFull) Color(0x55000000) else DarkSurfaceContainerHigh
    val pillBorder = if (isFull) BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)) else null

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Continuous micrometric zoom ruler when dragging
        AnimatedVisibility(
            visible = isDraggingSlider,
            enter = fadeIn(GXVMotion.fastEffects()),
            exit = fadeOut(GXVMotion.fastEffects())
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = pillContainerColor,
                border = pillBorder,
                tonalElevation = if (isFull) 0.dp else 6.dp,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = String.format("%.1fx", currentZoomRatio),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // Floating M3 Pill Deck
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = pillContainerColor,
            border = pillBorder,
            tonalElevation = if (isFull) 0.dp else 4.dp,
            modifier = Modifier
                .height(48.dp)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        isDraggingSlider = true
                        val newZoom = (currentZoomRatio + (delta / 80f)).coerceIn(OpticalLens.MIN_ZOOM, OpticalLens.MAX_ZOOM)
                        val step = (newZoom * 10f).roundToInt()
                        if (step != lastHapticStep) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastHapticStep = step
                        }
                        onZoomChanged(newZoom)
                    },
                    onDragStopped = {
                        isDraggingSlider = false
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .wrapContentSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val presets = listOf(OpticalLens.UW, OpticalLens.MAIN_1X, OpticalLens.CROP_2X, OpticalLens.TELE_3X)

                presets.forEach { lens ->
                    val isSelected = activeLens == lens && !isDraggingSlider

                    val pillWidth by animateDpAsState(
                        targetValue = if (isSelected) 84.dp else 40.dp,
                        animationSpec = GXVMotion.spatialBouncy(),
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLensSelected(lens)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSelected) "${lens.label} • ${lens.focalLengthMm}mm" else lens.label,
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
            }
        }
    }
}
