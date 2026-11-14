package org.gxvcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.camera.PipelineMetrics
import org.gxvcam.ui.state.AccentTheme
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.CaptureFormat
import org.gxvcam.ui.state.ColorSpaceOption
import org.gxvcam.ui.state.GridType
import org.gxvcam.ui.state.ProLayoutPosition
import org.gxvcam.ui.state.VolumeKeyAction
import org.gxvcam.ui.state.WatermarkStyle
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh
import kotlin.math.roundToInt

private enum class SettingsCategory(val label: String) {
    PHOTO("Photo"),
    ISP("ISP & AI"),
    PRO_UI("Pro & UI"),
    DEVICE("Device")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsBottomSheet(
    settings: CameraManualSettings,
    onUpdateSettings: (CameraManualSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    pipelineMetrics: PipelineMetrics = PipelineMetrics(),
    onOpenAiSettings: () -> Unit = {},
) {
    val tactile = rememberTactileFeedback()
    var selectedTab by remember { mutableStateOf(SettingsCategory.PHOTO) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "Camera & ISP Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Edge 50 / 60 Neo • Imagiq 950 ISP",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = DarkSurfaceContainerHigh,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Category Pill Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsCategory.entries.forEach { category ->
                    val isSelected = selectedTab == category
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            tactile.triggerTick()
                            selectedTab = category
                        },
                        label = {
                            Text(
                                text = category.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Tab Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    SettingsCategory.PHOTO -> {
                        SettingsSectionHeader(
                            icon = Icons.Default.Camera,
                            title = "Photo Quality & Format",
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Photo Capture Format",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Select JPEG or 16-bit DNG RAW output (RAW requires Pro Camera2 RAW)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CaptureFormat.entries.forEach { format ->
                                    val isSelected = settings.captureFormat == format
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            onUpdateSettings(
                                                settings.copy(
                                                    captureFormat = format,
                                                    isRawAndJpegEnabled = format.isRaw
                                                )
                                            )
                                        },
                                        label = { Text(format.label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Color Space Gamut",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Select photographic color space: sRGB, DCI-P3 wide gamut, or Adobe RGB",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ColorSpaceOption.entries.forEach { cs ->
                                    val isSelected = settings.colorSpace == cs
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(colorSpace = cs)) },
                                        label = { Text(cs.label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "JPEG Compression Quality",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = if (settings.jpegQuality == 95) "95% (Default)" else "${settings.jpegQuality}%",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Text(
                                text = "Higher quality preserves finer details and textures with minimal artifacts",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            Slider(
                                value = settings.jpegQuality.toFloat(),
                                onValueChange = {
                                    val newQ = it.roundToInt()
                                    if (newQ != settings.jpegQuality) {
                                        tactile.triggerScrollTick()
                                        onUpdateSettings(settings.copy(jpegQuality = newQ))
                                    }
                                },
                                valueRange = 50f..100f,
                                steps = 49,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("50% (Small)", fontSize = 11.sp, color = Color.Gray)
                                Text("95% (Default)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Text("100% (Max)", fontSize = 11.sp, color = Color.Gray)
                            }
                        }

                        SettingsToggleCard(
                            title = "Macro Shortcut Pill",
                            description = "Show the Macro shortcut in Photo mode to lock the ultra-wide lens for close-ups.",
                            checked = settings.isAutoMacroEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isAutoMacroEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Night Vision Shortcut Pill",
                            description = "Show the Night shortcut in Photo mode to enable OEM Night extension.",
                            checked = settings.isAutoNightVisionEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isAutoNightVisionEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Persist Front 32MP High-Res (S5KJD1)",
                            description = "Persists 32MP front sensor state in Jetpack DataStore across restarts.",
                            checked = settings.isFrontHighRes32MpEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isFrontHighRes32MpEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Auto Group Selfie (1.0x Wide Framing)",
                            description = "Automatically widens to 1.0x on the front camera when 2+ faces enter the frame.",
                            checked = settings.isAutoGroupSelfieEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isAutoGroupSelfieEnabled = it)) },
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SettingsSectionHeader(
                            icon = Icons.Default.Verified,
                            title = "Motorola Watermark & Branding",
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WatermarkStyle.entries.forEach { style ->
                                    val isSelected = settings.watermarkStyle == style
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(watermarkStyle = style)) },
                                        label = { Text(style.label, fontSize = 11.sp, maxLines = 1) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            if (settings.watermarkStyle != WatermarkStyle.OFF) {
                                OutlinedTextField(
                                    value = settings.watermarkSignature,
                                    onValueChange = { onUpdateSettings(settings.copy(watermarkSignature = it)) },
                                    label = { Text("Custom Signature / Author Name") },
                                    placeholder = { Text("e.g. Antonio De Gaetano") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    SettingsCategory.ISP -> {
                        SettingsSectionHeader(
                            icon = Icons.Default.Psychology,
                            title = "Dual-Path AI & APU 655 Scheduler",
                        )

                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = settings.aiBackend.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    val npuLabel = pipelineMetrics.npuTelemetry?.activeAcceleratorName
                                        ?: settings.npuPowerProfile.label
                                    val strictLabel = if (settings.useInternalNpuOnly) "Strict NPU" else "CPU Allowed"
                                    Text(
                                        text = "$npuLabel • $strictLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                                FilledTonalButton(
                                    onClick = {
                                        tactile.triggerTick()
                                        onOpenAiSettings()
                                    }
                                ) {
                                    Text("AI & NPU", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SettingsSectionHeader(
                            icon = Icons.Default.Memory,
                            title = "MediaTek Imagiq 950 ISP Hardware",
                        )

                        SettingsToggleCard(
                            title = "Ultra Res 50MP (Quad-Bayer Remosaic)",
                            description = "Captures full 8192×6144 resolution using Dimensity 7300 HW remosaic on Sony IMX896.",
                            checked = settings.isUltraRes50M,
                            onCheckedChange = { onUpdateSettings(settings.copy(isUltraRes50M = it)) },
                        )
                        SettingsToggleCard(
                            title = "Motorola ADRC (Dynamic Range)",
                            description = "Adaptive Dynamic Range Control prevents highlight clipping in bright scenes.",
                            checked = settings.isAdrcEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isAdrcEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Motorola Tintless (Shading Correction)",
                            description = "Compensates for optical lens vignetting and corner color casts.",
                            checked = settings.isTintlessEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isTintlessEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Zero Shutter Lag (ZSL)",
                            description = "MediaTek hardware ISP ring buffer for instant shutter response.",
                            checked = settings.isZslEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isZslEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Super Night / Long Exposure (32s)",
                            description = "Unlocks vendor exposure range up to 32 seconds on tripod.",
                            checked = settings.isSuperNightEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isSuperNightEnabled = it)) },
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Anti-Banding (Flicker Reduction)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val antiBandingOptions = listOf("Auto" to 3, "50 Hz" to 1, "60 Hz" to 2, "Off" to 0)
                                antiBandingOptions.forEach { (label, mode) ->
                                    val isSelected = settings.antiBandingMode == mode
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(antiBandingMode = mode)) },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "HDR+ Multi-Frame Burst Stack",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val burstOptions = listOf("6 Fast" to 6, "8 Normal" to 8, "12 High" to 12, "14 Max" to 14)
                                burstOptions.forEach { (label, count) ->
                                    val isSelected = settings.hdrFrameCount == count
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(hdrFrameCount = count)) },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SettingsSectionHeader(
                            icon = Icons.Default.AutoAwesome,
                            title = "Edge 50 / 60 Neo Computational Engine",
                        )

                        SettingsToggleCard(
                            title = "Sub-Pixel Drizzle MFSR Super-Res",
                            description = "Enables hand-tremor sub-pixel drizzle upsampling on 2x & 3x-10x telephoto.",
                            checked = settings.isDrizzleMfsrEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isDrizzleMfsrEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Multi-Face Portrait Depth Slab",
                            description = "Protects every detected face in Portrait mode instead of only center ellipse.",
                            checked = settings.isPortraitDepthSlabEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isPortraitDepthSlabEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Ultra-HDR 10-bit Gainmap",
                            description = "Synthesizes an ISO 21496-1 HDR luminance gainmap for HDR displays.",
                            checked = settings.isUltraHdrGainmapEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isUltraHdrGainmapEnabled = it)) },
                        )
                    }

                    SettingsCategory.PRO_UI -> {
                        SettingsSectionHeader(
                            icon = Icons.Default.Tune,
                            title = "Pro Mode Layout & Overlays",
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Pro Controls Position",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ProLayoutPosition.entries.forEach { pos ->
                                    val isSelected = settings.proLayoutPosition == pos
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(proLayoutPosition = pos)) },
                                        label = { Text(pos.label, fontSize = 11.sp, maxLines = 1) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        SettingsToggleCard(
                            title = "Live Waveform Histogram (Pro Mode)",
                            description = "Shows real-time floating RGB & Luminance graph in Pro Mode.",
                            checked = settings.isHistogramOverlayVisible,
                            onCheckedChange = { onUpdateSettings(settings.copy(isHistogramOverlayVisible = it)) },
                        )
                        SettingsToggleCard(
                            title = "Virtual Horizon Level (Pro Mode)",
                            description = "Gyroscopic degree guide with haptic snap in Pro Mode.",
                            checked = settings.isHorizonLevelVisible,
                            onCheckedChange = { onUpdateSettings(settings.copy(isHorizonLevelVisible = it)) },
                        )
                        SettingsToggleCard(
                            title = "Rotate-to-Zoom Cinema Dial",
                            description = "Replaces horizontal zoom ruler with a tactile rotary wheel.",
                            checked = settings.isRotaryZoomDialEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isRotaryZoomDialEnabled = it)) },
                        )
                        SettingsToggleCard(
                            title = "Zoom & Scroll Bar Haptics",
                            description = "Tactile ticks and snaps when dragging zoom wheels, rockers, and parameter sliders.",
                            checked = settings.isZoomScrollHapticsEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(isZoomScrollHapticsEnabled = it)) },
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Composition Grid",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GridType.entries.forEach { grid ->
                                    val isSelected = settings.gridType == grid
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onUpdateSettings(settings.copy(gridType = grid)) },
                                        label = { Text(grid.label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SettingsSectionHeader(
                            icon = Icons.Default.Palette,
                            title = "Material 3 Expressive Accent",
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AccentTheme.entries.chunked(2).forEach { rowAccents ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    rowAccents.forEach { theme ->
                                        val isSelected = settings.accentTheme == theme
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { onUpdateSettings(settings.copy(accentTheme = theme)) },
                                            leadingIcon = {
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(theme.colorHex))
                                                )
                                            },
                                            label = {
                                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                    Text(theme.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    Text(
                                                        text = theme.subtitle,
                                                        fontSize = 10.sp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else Color.Gray,
                                                        maxLines = 1,
                                                    )
                                                }
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (rowAccents.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        SettingsSectionHeader(
                            icon = Icons.Default.VolumeUp,
                            title = "Hardware Volume Rocker",
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            VolumeKeyAction.entries.forEach { action ->
                                val isSelected = settings.volumeKeyAction == action
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onUpdateSettings(settings.copy(volumeKeyAction = action)) },
                                    label = { Text(action.shortLabel, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    SettingsCategory.DEVICE -> {
                        SettingsSectionHeader(
                            icon = Icons.Default.Info,
                            title = "Hardware Specifications & Telemetry",
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SensorSpecRow(label = "Active Pipeline", value = pipelineMetrics.activePipelineName)
                                SensorSpecRow(
                                    label = "NPU / AI Accelerator",
                                    value = pipelineMetrics.npuTelemetry?.activeAcceleratorName ?: settings.npuPowerProfile.label
                                )
                                SensorSpecRow(
                                    label = "Last Capture Latency",
                                    value = if (pipelineMetrics.lastCaptureTotalMs > 0L) {
                                        "${pipelineMetrics.lastCaptureTotalMs}ms (Tone ${pipelineMetrics.lastToneMapMs}ms, Deblur ${pipelineMetrics.lastDeblurMs}ms)"
                                    } else {
                                        "Idle (ready)"
                                    }
                                )
                                SensorSpecRow(
                                    label = "ZSL RAW16 Ring",
                                    value = "${pipelineMetrics.zslSlotsOccupied}/14 slots • ${pipelineMetrics.zslDroppedFrames} dropped"
                                )
                                SensorSpecRow(
                                    label = "Thermal Burst Cap",
                                    value = "${pipelineMetrics.recommendedBurstFrames} frames (Status ${pipelineMetrics.thermalStatus})"
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                SensorSpecRow(label = "Main Sensor (Cam 0)", value = "Sony IMX896 50MP f/1.8 OIS (LYT-700C)")
                                SensorSpecRow(label = "Ultra-Wide (Cam 2)", value = "GalaxyCore GC13A2 13MP f/2.2 120° FOV")
                                SensorSpecRow(label = "Telephoto (Cam 3)", value = "Samsung S5K3K1 10MP 3x Optical OIS")
                                SensorSpecRow(label = "Front Camera (Cam 1)", value = "Samsung S5KJD1 32MP Quad-Bayer (FF)")
                                SensorSpecRow(label = "Image Processor", value = "MediaTek Dimensity 7300/7400 Imagiq 950")
                            }
                        }

                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        SettingsSectionHeader(
                            icon = Icons.Default.Camera,
                            title = "About GXVcam",
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "GXVcam",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Version 0.4.0 • Created by Antonio",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            try {
                                                uriHandler.openUri("https://github.com/Antux912012/GXVcam")
                                            } catch (_: Exception) {}
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Code,
                                            contentDescription = "GitHub",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("GitHub", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SensorSpecRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
        )
    }
}
