package org.gxvcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.AccentTheme
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.GridType
import org.gxvcam.ui.state.ProLayoutPosition
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsBottomSheet(
    settings: CameraManualSettings,
    onUpdateSettings: (CameraManualSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                            .size(38.dp)
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
                            text = "Motorola Camera 5 & GCam Hardware Tuning",
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
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // SECTION 1: Motorola Camera 5 & MediaTek Dimensity ISP
            SettingsSectionHeader(
                icon = Icons.Default.Memory,
                title = "Motorola & MediaTek ISP Hardware",
            )

            // Ultra Res 50MP Mode (Quad-Bayer Hardware Remosaic)
            SettingsToggleCard(
                title = "Ultra Res 50MP (Quad-Bayer Remosaic)",
                description = "Captures full 8192×6144 resolution using Dimensity 7300 HW remosaic on Sony IMX896.",
                checked = settings.isUltraRes50M,
                onCheckedChange = { onUpdateSettings(settings.copy(isUltraRes50M = it)) },
            )

            // Motorola ADRC
            SettingsToggleCard(
                title = "Motorola ADRC (Dynamic Range)",
                description = "Adaptive Dynamic Range Control from MotCamera5 prevents highlight clipping.",
                checked = settings.isAdrcEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(isAdrcEnabled = it)) },
            )

            // Motorola Tintless
            SettingsToggleCard(
                title = "Motorola Tintless (Shading Correction)",
                description = "Compensates for optical lens vignetting and sensor corner color casts.",
                checked = settings.isTintlessEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(isTintlessEnabled = it)) },
            )

            // Zero Shutter Lag (ZSL)
            SettingsToggleCard(
                title = "Zero Shutter Lag (ZSL)",
                description = "MediaTek hardware ISP ring buffer for instant shutter response with no lag.",
                checked = settings.isZslEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(isZslEnabled = it)) },
            )

            // Super Night & Tripod Astro Mode
            SettingsToggleCard(
                title = "Super Night / Long Exposure (32s)",
                description = "Unlocks vendor exposure range up to 32 seconds on tripod instead of standard 0.4s.",
                checked = settings.isSuperNightEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(isSuperNightEnabled = it)) },
            )

            // Anti-Banding Filter
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Anti-Banding (Flicker Reduction)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Compensates for 50Hz/60Hz AC lighting frequency",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val antiBandingOptions = listOf(
                        "Auto" to 3,
                        "50 Hz" to 1,
                        "60 Hz" to 2,
                        "Off" to 0,
                    )
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // SECTION 2: GCam Computational Photography
            SettingsSectionHeader(
                icon = Icons.Default.Camera,
                title = "GCam Computational Photography",
            )

            // RAW+JPEG Capture
            SettingsToggleCard(
                title = "RAW + JPEG Capture",
                description = "Saves 16-bit DNG RAW files alongside processed JPEG photos.",
                checked = settings.isRawAndJpegEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(isRawAndJpegEnabled = it)) },
            )

            // HDR+ Multi-Frame Count
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "HDR+ Multi-Frame Burst Stack",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Controls ISP burst count for multi-frame noise reduction & dynamic range",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val burstOptions = listOf(
                        "6 Fast" to 6,
                        "8 Normal" to 8,
                        "12 High" to 12,
                        "15 Max" to 15,
                    )
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

            // SECTION 3: Pro Controls Layout & Positioning
            SettingsSectionHeader(
                icon = Icons.Default.Tune,
                title = "Pro Mode Layout & Placement",
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Controls Position",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Choose where manual dials appear: vertical sidebar (left/right) or horizontal strip (top/bottom)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // SECTION 4: Material 3 Expressive & Pantone Accent Theming
            SettingsSectionHeader(
                icon = Icons.Default.Palette,
                title = "Material 3 Expressive & Pantone Accent",
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Color Accent & Theming",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Select from official Motorola Edge 50 Neo Pantone editions or Google Material You dynamic colors",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
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
                                            Text(
                                                text = theme.label,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
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
            }

            // Grid Overlay Style
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Composition Grid",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
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

            // Live RGB Histogram Toggle
            SettingsToggleCard(
                title = "Live Waveform Histogram",
                description = "Shows real-time floating RGB & Luminance graph in the viewfinder.",
                checked = settings.isHistogramOverlayVisible,
                onCheckedChange = { onUpdateSettings(settings.copy(isHistogramOverlayVisible = it)) },
            )

            // Horizon Level Toggle
            SettingsToggleCard(
                title = "Virtual Horizon Level",
                description = "Gyroscopic degree guide with haptic snap when device is perfectly level.",
                checked = settings.isHorizonLevelVisible,
                onCheckedChange = { onUpdateSettings(settings.copy(isHorizonLevelVisible = it)) },
            )

            // Device Watermark
            SettingsToggleCard(
                title = "Photo Watermark",
                description = "Embed 'GXVcam • Motorola Edge 50 Neo | LYT-700C' stamp.",
                checked = settings.isWatermarkEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(isWatermarkEnabled = it)) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // SECTION 3: Sensor & Hardware Diagnostics
            SettingsSectionHeader(
                icon = Icons.Default.Info,
                title = "Hardware Specifications (Edge 50 Neo)",
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
                    SensorSpecRow(label = "Main Sensor (Cam 0)", value = "Sony IMX896 50MP f/1.8 OIS (LYT-700C)")
                    SensorSpecRow(label = "Ultra-Wide (Cam 2)", value = "GalaxyCore GC13A2 13MP f/2.2 120° FOV")
                    SensorSpecRow(label = "Telephoto (Cam 3)", value = "Samsung S5K3K1 10MP 3x Optical OIS")
                    SensorSpecRow(label = "Front Camera (Cam 1)", value = "Samsung S5KJD1 32MP Quad-Bayer")
                    SensorSpecRow(label = "Image Processor", value = "MediaTek Dimensity 7300 Imagiq 950")
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
