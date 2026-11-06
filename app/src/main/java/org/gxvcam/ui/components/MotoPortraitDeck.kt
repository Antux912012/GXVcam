package org.gxvcam.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.MotoPortraitLens
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion
import kotlin.math.roundToInt

private val PORTRAIT_F_STOPS = listOf(1.2f, 1.4f, 1.8f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 16.0f)

@Composable
fun MotoPortraitDeck(
    activeLens: MotoPortraitLens,
    aperture: Float,
    isApertureExpanded: Boolean,
    onLensSelected: (MotoPortraitLens) -> Unit,
    onApertureChanged: (Float) -> Unit,
    onToggleApertureExpanded: (Boolean) -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val tactile = rememberTactileFeedback()
    val containerColor = if (isFull) Color(0x66000000) else DarkSurfaceContainerHigh
    val containerBorder = if (isFull) BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)) else null
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Expandable Bokeh Aperture Slider
        AnimatedVisibility(
            visible = isApertureExpanded,
            enter = fadeIn(GXVMotion.fastEffects()) + expandVertically(GXVMotion.spatialBouncy()),
            exit = fadeOut(GXVMotion.fastEffects()) + shrinkVertically(GXVMotion.fastEffects())
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = containerColor,
                border = containerBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PORTRAIT BOKEH BLUR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                        Text(
                            text = String.format(java.util.Locale.US, "ƒ/%.1f", aperture),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Row of F-Stops
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PORTRAIT_F_STOPS.forEach { f ->
                            val isSelected = (aperture == f)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) primaryColor else Color.Transparent)
                                    .clickable {
                                        tactile.triggerTick()
                                        onApertureChanged(f)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (f == 16f) "16" else String.format(java.util.Locale.US, "%.1f", f),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Aperture Pill Button & 4-Prime Segment Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Aperture Toggle Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isApertureExpanded) primaryColor else containerColor,
                border = if (!isApertureExpanded) containerBorder else null,
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleApertureExpanded(!isApertureExpanded)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "ƒ",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isApertureExpanded) Color.Black else primaryColor
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", aperture),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isApertureExpanded) Color.Black else Color.White
                    )
                }
            }

            // Motorola 4-Prime Focal Length Deck (24mm, 35mm, 50mm, 85mm)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = containerColor,
                border = containerBorder,
                modifier = Modifier.height(42.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MotoPortraitLens.entries.forEach { pLens ->
                        val isSelected = (activeLens == pLens)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) primaryColor else Color.Transparent)
                                .clickable {
                                    tactile.triggerTick()
                                    onLensSelected(pLens)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = pLens.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
