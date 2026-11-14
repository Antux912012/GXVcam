package org.gxvcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.gxvcam.audio.AudioDeviceOption
import org.gxvcam.ui.components.rememberTactileFeedback
import org.gxvcam.ui.state.AudioInputSource
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.ProVideoLutStyle
import org.gxvcam.ui.state.VideoCodecProfile
import org.gxvcam.ui.state.VideoColorProfile
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import org.gxvcam.ui.theme.GXVMotion
import org.gxvcam.video.CrossLensColorState
import org.gxvcam.video.SeamlessLensZoomController
import org.gxvcam.video.VideoCodecCapabilities
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val VIDEO_OPTICAL_DETENTS = listOf(0.6f, 1.0f, 2.0f, 3.0f, 6.0f)

/**
 * Material 3 Expressive Pro Video Cinema HUD (Sprint 8).
 *
 * Provides:
 * - Top Cinema Telemetry Capsule: Codec/FPS/Bit-depth, 180° shutter lock, and live stereo L/R dBFS VU meter.
 * - Expandable Cinema Audio Matrix (Dual Mic / Audio Zoom / USB-C & BT device selector + 80Hz Wind Cut).
 * - Expandable Cinema Codec & Look Strip (H.264 / HEVC 10-bit / AV1 locked during recording, Rec.709 / HLG / GXV-Log, 4x 3D-LUT, Vibrance).
 * - Bottom Motorized Constant-Velocity Zoom Rocker (resets `rockerVelocity = 0f` on drag end/cancel) + optical detents.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProVideoCinemaTopTelemetry(
    videoSettings: MotoVideoSettings,
    codecCapabilities: VideoCodecCapabilities,
    audioMeterDbFs: Pair<Float, Float>,
    availableAudioDevices: List<AudioDeviceOption>,
    seamlessColorState: CrossLensColorState,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onUpdateVideoSettings: (MotoVideoSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()
    var showAudioDrawer by remember { mutableStateOf(false) }
    var showLookDrawer by remember { mutableStateOf(false) }

    val fps = if (videoSettings.isDualCaptureEnabled) 30 else videoSettings.frameRate.fps.coerceAtMost(60)
    val shutterLabel = SeamlessLensZoomController.format180ShutterLabel(fps)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xAA0D0E12),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                // Cinema Engine Active Badge (Recording timer is displayed once in TopQuickSettingsBar)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isRecording) Color(0x44FF3B30) else Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    val dotColor by animateColorAsState(
                        targetValue = if (isRecording) Color(0xFFFF453A) else MaterialTheme.colorScheme.primary,
                        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "recDotColor"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = if (isRecording) "REC • PRO" else "CINEMA",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Active Codec & Color Profile Pill (opens Codec/LUT drawer)
                val codecTag = when (videoSettings.codecProfile) {
                    VideoCodecProfile.AV1 -> if (codecCapabilities.hasHardwareAv1Encoder) "AV1 HW" else "AV1 SW"
                    VideoCodecProfile.H265_HEVC -> if (videoSettings.colorProfile.is10Bit) "HEVC 10" else "HEVC"
                    VideoCodecProfile.H264_AVC -> "H.264"
                }
                Surface(
                    onClick = {
                        tactile.triggerTick()
                        showLookDrawer = !showLookDrawer
                        if (showLookDrawer) showAudioDrawer = false
                    },
                    shape = CircleShape,
                    color = if (showLookDrawer) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.09f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MovieFilter,
                            contentDescription = "Codec and Color LUT",
                            tint = if (showLookDrawer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "$codecTag • ${videoSettings.colorProfile.shortLabel}",
                            color = if (showLookDrawer) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 180-Degree Shutter Rule Lock Pill
                Surface(
                    onClick = {
                        tactile.triggerSnap()
                        onUpdateVideoSettings(
                            videoSettings.copy(isShutter180Locked = !videoSettings.isShutter180Locked)
                        )
                    },
                    shape = CircleShape,
                    color = if (videoSettings.isShutter180Locked) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.White.copy(alpha = 0.08f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (videoSettings.isShutter180Locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "180 degree shutter lock",
                            tint = if (videoSettings.isShutter180Locked) MaterialTheme.colorScheme.onSecondaryContainer else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (videoSettings.isShutter180Locked) shutterLabel else "Auto S",
                            color = if (videoSettings.isShutter180Locked) MaterialTheme.colorScheme.onSecondaryContainer else Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Live Stereo L/R dBFS VU Meter + Audio Matrix Drawer trigger
                Surface(
                    onClick = {
                        tactile.triggerTick()
                        showAudioDrawer = !showAudioDrawer
                        if (showAudioDrawer) showLookDrawer = false
                    },
                    shape = CircleShape,
                    color = if (showAudioDrawer) MaterialTheme.colorScheme.tertiaryContainer else Color.White.copy(alpha = 0.09f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (videoSettings.audioInputSource == AudioInputSource.REAR_SUBJECT_FOCUS) {
                                Icons.Default.GraphicEq
                            } else {
                                Icons.Default.Mic
                            },
                            contentDescription = "Pro Audio Matrix",
                            tint = if (showAudioDrawer) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        StereoVuMeterBar(
                            leftDbFs = audioMeterDbFs.first,
                            rightDbFs = audioMeterDbFs.second,
                            modifier = Modifier.width(46.dp).height(12.dp)
                        )
                    }
                }

                // Cross-Lens WB Lock / Dissolve Indicator
                if (seamlessColorState.isTransitioning) {
                    Text(
                        text = "AWB Lock",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Expandable Codec, Color Space & NPU 3D-LUT Panel
        AnimatedVisibility(
            visible = showLookDrawer,
            enter = fadeIn(GXVMotion.fastEffects()) + expandVertically(GXVMotion.spatialBouncy()),
            exit = fadeOut(GXVMotion.fastEffects()) + shrinkVertically(GXVMotion.fastEffects())
        ) {
            ProVideoLookAndCodecPanel(
                videoSettings = videoSettings,
                codecCapabilities = codecCapabilities,
                isRecording = isRecording,
                onUpdateVideoSettings = onUpdateVideoSettings
            )
        }

        // Expandable Pro Audio Matrix Panel
        AnimatedVisibility(
            visible = showAudioDrawer,
            enter = fadeIn(GXVMotion.fastEffects()) + expandVertically(GXVMotion.spatialBouncy()),
            exit = fadeOut(GXVMotion.fastEffects()) + shrinkVertically(GXVMotion.fastEffects())
        ) {
            ProAudioMatrixPanel(
                videoSettings = videoSettings,
                audioMeterDbFs = audioMeterDbFs,
                availableDevices = availableAudioDevices,
                onUpdateVideoSettings = onUpdateVideoSettings
            )
        }
    }
}

@Composable
private fun StereoVuMeterBar(
    leftDbFs: Float,
    rightDbFs: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barH = (size.height - 2.dp.toPx()) / 2f
        val leftFrac = ((leftDbFs + 60f) / 60f).coerceIn(0.02f, 1f)
        val rightFrac = ((rightDbFs + 60f) / 60f).coerceIn(0.02f, 1f)

        fun barColor(db: Float): Color = when {
            db >= -3f -> Color(0xFFFF453A)
            db >= -12f -> Color(0xFFFFD60A)
            else -> Color(0xFF30D158)
        }

        // Background tracks
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset.Zero,
            size = Size(size.width, barH),
            cornerRadius = CornerRadius(2f, 2f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset(0f, barH + 2.dp.toPx()),
            size = Size(size.width, barH),
            cornerRadius = CornerRadius(2f, 2f)
        )

        // Active levels
        drawRoundRect(
            color = barColor(leftDbFs),
            topLeft = Offset.Zero,
            size = Size(size.width * leftFrac, barH),
            cornerRadius = CornerRadius(2f, 2f)
        )
        drawRoundRect(
            color = barColor(rightDbFs),
            topLeft = Offset(0f, barH + 2.dp.toPx()),
            size = Size(size.width * rightFrac, barH),
            cornerRadius = CornerRadius(2f, 2f)
        )
    }
}

@Composable
private fun ProVideoLookAndCodecPanel(
    videoSettings: MotoVideoSettings,
    codecCapabilities: VideoCodecCapabilities,
    isRecording: Boolean,
    onUpdateVideoSettings: (MotoVideoSettings) -> Unit
) {
    val tactile = rememberTactileFeedback()
    var lastLutStep by remember { mutableIntStateOf((videoSettings.lutBlend * 20f).roundToInt()) }
    var lastVibStep by remember { mutableIntStateOf((videoSettings.vibrance * 20f).roundToInt()) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkSurfaceContainerHigh,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pro Video Engine toggle + Codec Selector (disabled while recording)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRecording) "CODEC LOCKED WHILE RECORDING" else "VIDEO CODEC & COLOR",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                FilterChip(
                    selected = videoSettings.isProVideoEngineEnabled,
                    enabled = !isRecording,
                    onClick = {
                        tactile.triggerTick()
                        onUpdateVideoSettings(
                            videoSettings.copy(isProVideoEngineEnabled = !videoSettings.isProVideoEngineEnabled)
                        )
                    },
                    label = {
                        Text(
                            text = if (videoSettings.isProVideoEngineEnabled) "Shader Engine ON" else "Stock Engine",
                            fontSize = 10.sp
                        )
                    }
                )
            }

            // Codec Chips (explicitly disabled while recording)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VideoCodecProfile.entries.forEach { codec ->
                    val label = if (codec == VideoCodecProfile.AV1 && codecCapabilities.hasHardwareAv1Encoder) {
                        "AV1 HW"
                    } else {
                        codec.label
                    }
                    FilterChip(
                        selected = videoSettings.codecProfile == codec,
                        enabled = !isRecording,
                        onClick = {
                            tactile.triggerTick()
                            onUpdateVideoSettings(videoSettings.copy(codecProfile = codec))
                        },
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Color Space / Log Profile Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VideoColorProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = videoSettings.colorProfile == profile,
                        enabled = !isRecording,
                        onClick = {
                            tactile.triggerTick()
                            onUpdateVideoSettings(
                                videoSettings.copy(
                                    colorProfile = profile,
                                    isHdr10BitEnabled = profile == VideoColorProfile.HLG_HDR10
                                )
                            )
                        },
                        label = {
                            Text(
                                text = profile.shortLabel,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4-Way NPU 3D-LUT Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProVideoLutStyle.entries.forEach { style ->
                    FilterChip(
                        selected = videoSettings.activeLutStyle == style,
                        onClick = {
                            tactile.triggerTick()
                            onUpdateVideoSettings(videoSettings.copy(activeLutStyle = style))
                        },
                        label = { Text(style.label, fontSize = 11.sp) }
                    )
                }
            }

            // LUT Mix & Vibrance Sliders (respect zoom/scroll haptics setting)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("LUT ${(videoSettings.lutBlend * 100).roundToInt()}%", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(56.dp))
                Slider(
                    value = videoSettings.lutBlend,
                    onValueChange = { v ->
                        val step = (v * 20f).roundToInt()
                        if (step != lastLutStep) {
                            tactile.triggerScrollTick()
                            lastLutStep = step
                        }
                        onUpdateVideoSettings(videoSettings.copy(lutBlend = v))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Vib ${String.format(Locale.US, "%+.2f", videoSettings.vibrance)}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(56.dp))
                Slider(
                    value = videoSettings.vibrance,
                    onValueChange = { v ->
                        val step = (v * 20f).roundToInt()
                        if (step != lastVibStep) {
                            tactile.triggerScrollTick()
                            lastVibStep = step
                        }
                        onUpdateVideoSettings(videoSettings.copy(vibrance = v))
                    },
                    valueRange = -0.35f..0.45f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ProAudioMatrixPanel(
    videoSettings: MotoVideoSettings,
    audioMeterDbFs: Pair<Float, Float>,
    availableDevices: List<AudioDeviceOption>,
    onUpdateVideoSettings: (MotoVideoSettings) -> Unit
) {
    val tactile = rememberTactileFeedback()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DarkSurfaceContainerHigh,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRO AUDIO MATRIX & VU",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(Locale.US, "L %.0f dB  •  R %.0f dB", audioMeterDbFs.first, audioMeterDbFs.second),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AudioInputSource.entries.forEach { source ->
                    FilterChip(
                        selected = videoSettings.audioInputSource == source,
                        onClick = {
                            tactile.triggerTick()
                            onUpdateVideoSettings(
                                videoSettings.copy(
                                    audioInputSource = source,
                                    isAudioZoomEnabled = source == AudioInputSource.REAR_SUBJECT_FOCUS
                                )
                            )
                        },
                        label = { Text(source.shortLabel, fontSize = 11.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = videoSettings.isWindNoiseCutEnabled,
                    onClick = {
                        tactile.triggerTick()
                        onUpdateVideoSettings(
                            videoSettings.copy(isWindNoiseCutEnabled = !videoSettings.isWindNoiseCutEnabled)
                        )
                    },
                    label = { Text("80 Hz Wind Cut", fontSize = 11.sp) }
                )

                availableDevices.forEach { dev ->
                    val isSelected = videoSettings.selectedAudioDeviceId == dev.id
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            tactile.triggerTick()
                            onUpdateVideoSettings(
                                videoSettings.copy(
                                    selectedAudioDeviceId = if (isSelected) null else dev.id,
                                    audioInputSource = if (dev.isExternal) AudioInputSource.EXTERNAL_USBC_BLE else videoSettings.audioInputSource
                                )
                            )
                        },
                        label = { Text(dev.name, fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}

/**
 * Bottom Cinema Zoom Rocker & Optical Detent Strip for VIDEO mode.
 *
 * Strictly resets [rockerVelocity] to `0f` on both `onDragEnd` and `onDragCancel`
 * so the camera zoom never gets stuck drifting.
 */
@Composable
fun ProVideoZoomRockerDeck(
    currentZoomRatio: Float,
    isBackCamera: Boolean,
    onZoomChanged: (Float) -> Unit,
    onZoomDetentSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()
    val minZoom = if (isBackCamera) 0.6f else 1.0f
    val maxZoom = if (isBackCamera) 20.0f else 4.0f

    var rockerVelocity by remember { mutableFloatStateOf(0f) }
    val latestZoom by rememberUpdatedState(currentZoomRatio)
    var lastHapticTickZoom by remember { mutableIntStateOf((currentZoomRatio * 10f).roundToInt()) }

    // Constant-velocity logarithmic motorized zoom loop while rocker is held/dragged
    LaunchedEffect(rockerVelocity, isBackCamera) {
        if (abs(rockerVelocity) > 0.04f) {
            while (isActive && abs(rockerVelocity) > 0.04f) {
                val speed = rockerVelocity * 0.85f // log-units/sec
                val next = (latestZoom * exp((speed * 0.016f).toDouble()).toFloat())
                    .coerceIn(minZoom, maxZoom)
                val step = (next * 10f).roundToInt()
                if (step != lastHapticTickZoom) {
                    if (VIDEO_OPTICAL_DETENTS.any { abs(it - next) < 0.04f }) {
                        tactile.triggerScrollSnap()
                    } else {
                        tactile.triggerScrollTick()
                    }
                    lastHapticTickZoom = step
                }
                onZoomChanged(next)
                delay(16L)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x990D0E12),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Optical Detent Strip (.6x, 1x, 2x, 3x, 6x)
            val detents = if (isBackCamera) VIDEO_OPTICAL_DETENTS else listOf(1.0f, 1.4f, 2.0f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    detents.forEach { stop ->
                        val isActive = abs(currentZoomRatio - stop) < 0.06f
                        val label = when {
                            stop == 0.6f -> ".6"
                            stop == stop.toInt().toFloat() -> "${stop.toInt()}x"
                            else -> "${stop}x"
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.08f)
                                )
                                .clickable {
                                    tactile.triggerScrollSnap()
                                    onZoomDetentSelected(stop)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Text(
                    text = String.format(Locale.US, "%.1fx", currentZoomRatio),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }

            // Motorized Smooth Zoom Rocker Track (W <-> Center <-> T)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pointerInput(isBackCamera) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                rockerVelocity = 0f
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val halfW = (size.width / 2f).coerceAtLeast(1f)
                                val normalized = ((change.position.x - halfW) / halfW).coerceIn(-1f, 1f)
                                rockerVelocity = if (abs(normalized) < 0.08f) 0f else normalized
                            },
                            onDragEnd = {
                                rockerVelocity = 0f
                            },
                            onDragCancel = {
                                rockerVelocity = 0f
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("W", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (abs(rockerVelocity) > 0.04f) {
                            if (rockerVelocity > 0f) "ZOOM IN ▶" else "◀ ZOOM OUT"
                        } else {
                            "MOTORIZED ROCKER"
                        },
                        color = if (abs(rockerVelocity) > 0.04f) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        textAlign = TextAlign.Center
                    )
                    Text("T", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Spring-centered visual thumb
                Box(
                    modifier = Modifier
                        .offset(x = (rockerVelocity * 110f).dp)
                        .size(width = 36.dp, height = 24.dp)
                        .clip(CircleShape)
                        .background(
                            if (abs(rockerVelocity) > 0.04f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            }
                        )
                )
            }
        }
    }
}
