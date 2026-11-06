package org.gxvcam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.gxvcam.ui.state.*
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion

@Composable
fun AgcLutCarousel(
    selectedProfile: AgcColorProfile,
    onSelectProfile: (AgcColorProfile) -> Unit,
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgcColorProfile.entries.forEach { profile ->
            val isSelected = profile == selectedProfile
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else if (isFull) Color.White.copy(alpha = 0.20f) else Color.Transparent,
                animationSpec = GXVMotion.fastEffects(),
                label = "lutBorder"
            )
            val borderWidth by animateDpAsState(
                targetValue = if (isSelected) 2.dp else if (isFull) 1.dp else 0.dp,
                animationSpec = GXVMotion.spatialBouncyDp,
                label = "lutBorderWidth"
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isFull) 0.85f else 0.9f)
                        else if (isFull) Color(0x55000000)
                        else DarkSurfaceContainerHigh
                    )
                    .border(borderWidth, borderColor, CircleShape)
                    .clickable { onSelectProfile(profile) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgcLibPatcherBottomSheet(
    agcSettings: AgcLibPatcherSettings,
    onUpdateAgc: (AgcLibPatcherSettings) -> Unit,
    onDismiss: () -> Unit,
    onResetToDefaults: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GCam / BigKaka AGC LibPatcher",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Real-time ISP tuning & multi-frame pipeline",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                TextButton(onClick = onResetToDefaults) {
                    Text(text = "Reset", color = MaterialTheme.colorScheme.primary)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Live Viewfinder LUT Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Live Viewfinder Grading",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Real-time GPU color filter on preview",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = agcSettings.isLiveViewfinderLutEnabled,
                    onCheckedChange = {
                        onUpdateAgc(agcSettings.copy(isLiveViewfinderLutEnabled = it))
                    }
                )
            }

            // Profile Carousel
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Color Grading Profile",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                AgcLutCarousel(
                    selectedProfile = agcSettings.activeProfile,
                    onSelectProfile = { profile ->
                        onUpdateAgc(
                            agcSettings.copy(
                                activeProfile = profile,
                                saturation = profile.saturationBoost,
                                contrast = profile.contrastCurve,
                                highlightKnee = profile.highlightKnee,
                                shadowLift = profile.shadowLift,
                                sharpnessLevel = profile.acutance,
                                vignetteStrength = profile.vignetteStrength,
                                rGainShift = 1.0f,
                                gGainShift = 1.0f,
                                bGainShift = 1.0f
                            )
                        )
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Tone & Contrast Section
            Text(
                text = "Tone & Contrast Tuning",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            PatcherSliderItem(
                label = "Saturation Boost",
                value = agcSettings.saturation,
                valueRange = 0.50f..2.00f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(saturation = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            PatcherSliderItem(
                label = "Contrast Curve",
                value = agcSettings.contrast,
                valueRange = 0.50f..2.00f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(contrast = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            PatcherSliderItem(
                label = "Highlight Knee (Roll-off)",
                value = agcSettings.highlightKnee,
                valueRange = 0.50f..0.95f,
                displayFormat = "%.2f",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(highlightKnee = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            PatcherSliderItem(
                label = "Shadow Lift",
                value = agcSettings.shadowLift,
                valueRange = 0.00f..0.40f,
                displayFormat = "%.2f",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(shadowLift = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            PatcherSliderItem(
                label = "Vignette Strength",
                value = agcSettings.vignetteStrength,
                valueRange = 0.00f..0.80f,
                displayFormat = "%.2f",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(vignetteStrength = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Sensor White Balance Shifts
            Text(
                text = "Sensor White Balance Gain Shifts",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            PatcherSliderItem(
                label = "Red Gain Shift",
                value = agcSettings.rGainShift,
                valueRange = 0.80f..1.20f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(rGainShift = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            PatcherSliderItem(
                label = "Green Gain Shift",
                value = agcSettings.gGainShift,
                valueRange = 0.80f..1.20f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(gGainShift = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            PatcherSliderItem(
                label = "Blue Gain Shift",
                value = agcSettings.bGainShift,
                valueRange = 0.80f..1.20f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(bGainShift = it, activeProfile = AgcColorProfile.CUSTOM))
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Acutance & Denoise
            Text(
                text = "Acutance & Noise Reduction",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            PatcherSliderItem(
                label = "Sharpness / Acutance",
                value = agcSettings.sharpnessLevel,
                valueRange = 0.50f..2.50f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(sharpnessLevel = it))
                }
            )

            PatcherSliderItem(
                label = "Luma Denoise (Spatial)",
                value = agcSettings.lumaDenoiseLevel,
                valueRange = 0.00f..2.00f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(lumaDenoiseLevel = it))
                }
            )

            PatcherSliderItem(
                label = "Chroma Denoise",
                value = agcSettings.chromaDenoiseLevel,
                valueRange = 0.00f..2.00f,
                displayFormat = "%.2fx",
                onValueChange = {
                    onUpdateAgc(agcSettings.copy(chromaDenoiseLevel = it))
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Multi-Frame & Capture Engine
            Text(
                text = "Capture Engine & HDR+",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "ZSL HDR+ Multi-Frame", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Dimensity 7300 zero shutter lag multi-frame burst",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = agcSettings.hdrNetMultiFrameBoost,
                    onCheckedChange = { onUpdateAgc(agcSettings.copy(hdrNetMultiFrameBoost = it)) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "HDR+ Frame Count", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Number of underexposed frames merged for dynamic range",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(4, 8, 12, 16).forEach { count ->
                        FilterChip(
                            selected = agcSettings.hdrFrameCount == count,
                            onClick = { onUpdateAgc(agcSettings.copy(hdrFrameCount = count)) },
                            label = { Text(text = "$count") }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Highlight Preservation", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Prevent harsh digital clipping in bright specular highlights",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = agcSettings.disableToneCurveClip,
                    onCheckedChange = { onUpdateAgc(agcSettings.copy(disableToneCurveClip = it)) }
                )
            }
        }
    }
}

@Composable
private fun PatcherSliderItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    displayFormat: String = "%.2f",
    tactile: TactileFeedbackController = rememberTactileFeedback()
) {
    var lastTickStep by remember { mutableIntStateOf(0) }
    val totalSpan = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val numSteps = 40

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = displayFormat.format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = { newVal ->
                val currentStep = ((newVal - valueRange.start) / totalSpan * numSteps).roundToInt()
                if (currentStep != lastTickStep) {
                    tactile.triggerTick()
                    lastTickStep = currentStep
                }
                onValueChange(newVal)
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
fun MotoVideoQuickBar(
    videoSettings: MotoVideoSettings,
    onUpdateVideo: (MotoVideoSettings) -> Unit,
    modifier: Modifier = Modifier,
    tactile: TactileFeedbackController = rememberTactileFeedback()
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Resolution cycle (4K UHD / 1080p FHD / 720p HD)
        FilterChip(
            selected = true,
            onClick = {
                tactile.triggerTick()
                val next = when (videoSettings.resolution) {
                    VideoResolution.UHD_4K -> VideoResolution.FHD_1080P
                    VideoResolution.FHD_1080P -> VideoResolution.HD_720P
                    VideoResolution.HD_720P -> VideoResolution.UHD_4K
                }
                onUpdateVideo(videoSettings.copy(resolution = next))
            },
            label = { Text(videoSettings.resolution.label) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        // FPS mode cycle (30fps / 60fps / 120fps)
        FilterChip(
            selected = true,
            onClick = {
                tactile.triggerTick()
                val next = when (videoSettings.frameRate) {
                    VideoFrameRate.FPS_30 -> VideoFrameRate.FPS_60
                    VideoFrameRate.FPS_60 -> VideoFrameRate.SLOW_MO_120
                    VideoFrameRate.SLOW_MO_120 -> VideoFrameRate.FPS_30
                }
                onUpdateVideo(videoSettings.copy(frameRate = next))
            },
            label = { Text(videoSettings.frameRate.label) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        // Stabilization mode cycle (Adaptive / Horizon Lock 360° / Off)
        FilterChip(
            selected = videoSettings.stabilization != MotoVideoStabilization.OFF,
            onClick = {
                tactile.triggerTick()
                val next = when (videoSettings.stabilization) {
                    MotoVideoStabilization.OFF -> MotoVideoStabilization.ADAPTIVE
                    MotoVideoStabilization.ADAPTIVE -> MotoVideoStabilization.HORIZON_LOCK
                    MotoVideoStabilization.HORIZON_LOCK -> MotoVideoStabilization.OFF
                }
                onUpdateVideo(videoSettings.copy(stabilization = next))
            },
            label = { Text(videoSettings.stabilization.label) }
        )

        // Video Night Vision
        FilterChip(
            selected = videoSettings.isNightVisionVideoEnabled,
            onClick = {
                tactile.triggerTick()
                onUpdateVideo(videoSettings.copy(isNightVisionVideoEnabled = !videoSettings.isNightVisionVideoEnabled))
            },
            label = { Text(if (videoSettings.isNightVisionVideoEnabled) "Night Video ON" else "Night Video") }
        )

        // HDR10+ / 10-Bit HLG
        FilterChip(
            selected = videoSettings.isHdr10BitEnabled,
            onClick = {
                tactile.triggerTick()
                onUpdateVideo(videoSettings.copy(isHdr10BitEnabled = !videoSettings.isHdr10BitEnabled))
            },
            label = { Text(if (videoSettings.isHdr10BitEnabled) "HDR10+ 10-Bit" else "SDR 8-Bit") }
        )

        // Audio Zoom (Microphone directionality tracking lens zoom)
        FilterChip(
            selected = videoSettings.isAudioZoomEnabled,
            onClick = {
                tactile.triggerTick()
                onUpdateVideo(videoSettings.copy(isAudioZoomEnabled = !videoSettings.isAudioZoomEnabled))
            },
            label = { Text(if (videoSettings.isAudioZoomEnabled) "Audio Zoom ON" else "Audio Zoom") }
        )

        // Dual Capture PIP
        FilterChip(
            selected = videoSettings.isDualCaptureEnabled,
            onClick = {
                tactile.triggerTick()
                onUpdateVideo(videoSettings.copy(isDualCaptureEnabled = !videoSettings.isDualCaptureEnabled))
            },
            label = { Text("Dual PIP") }
        )
    }
}
