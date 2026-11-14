package org.gxvcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.gxvcam.ai.NpuExecutionTelemetry
import org.gxvcam.ui.state.AiProcessingBackend
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.NpuPowerProfile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GxvAiSettingsSheet(
    settings: CameraManualSettings,
    telemetry: NpuExecutionTelemetry?,
    onBackendSelected: (AiProcessingBackend) -> Unit,
    onNpuProfileSelected: (NpuPowerProfile) -> Unit,
    onToggleInternalNpuOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "AI Engine & NPU Power Architecture",
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            // 1. Live NPU & Fallback Status Banner
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isFallback = telemetry?.isMotoAiFallbackActive
                        ?: (settings.aiBackend == AiProcessingBackend.MOTO_AI_FALLBACK)
                    val statusTitle = if (isFallback) {
                        "Active Pipeline: Built-in Moto AI (Imagiq 950 ISP)"
                    } else {
                        "Active Pipeline: GXV Hybrid RAW + ${telemetry?.activeAcceleratorName ?: settings.npuPowerProfile.label}"
                    }
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    val latencyLabel = telemetry?.inferenceLatencyMs?.takeIf { it > 0L }?.let { "$it ms" } ?: "Ready"
                    val isolationLabel = if (settings.useInternalNpuOnly) "Zero CPU Wake-Lock" else "CPU Fallback Allowed"
                    Text(
                        text = "Last Neural Pass: $latencyLabel • $isolationLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            // 2. AI Processing Backend Selector (GXV Hybrid vs. Built-in Moto AI Fallback)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Photo Processing Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Switch between GXVcam's Vulkan RAW16 + APU 655 engine and Motorola's built-in Moto AI camera extensions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )

                val backends = listOf(
                    AiProcessingBackend.GXV_HYBRID_AI to AiProcessingBackend.GXV_HYBRID_AI.shortLabel,
                    AiProcessingBackend.AUTO_BATTERY_FALLBACK to AiProcessingBackend.AUTO_BATTERY_FALLBACK.shortLabel,
                    AiProcessingBackend.MOTO_AI_FALLBACK to AiProcessingBackend.MOTO_AI_FALLBACK.shortLabel
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    backends.forEachIndexed { index, (backend, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = backends.size),
                            selected = settings.aiBackend == backend,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBackendSelected(backend)
                            }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 3. Internal NPU (MediaTek APU 655) Power & Performance Profile
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Internal NPU Accelerator (Dimensity 7300 APU 655)",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Offloads 3D-LUT prediction, semantic masks, and raw denoising to dedicated NPU silicon to save battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )

                val profiles = listOf(
                    NpuPowerProfile.APU655_BATTERY_SAVER to NpuPowerProfile.APU655_BATTERY_SAVER.shortLabel,
                    NpuPowerProfile.APU655_SUSTAINED_SPEED to NpuPowerProfile.APU655_SUSTAINED_SPEED.shortLabel,
                    NpuPowerProfile.GPU_MALI_FP16 to NpuPowerProfile.GPU_MALI_FP16.shortLabel
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    profiles.forEachIndexed { index, (profile, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = profiles.size),
                            selected = settings.npuPowerProfile == profile,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNpuProfileSelected(profile)
                            }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 4. Strict NPU Isolation Toggle (Forbids CPU thread fallback to prevent heat/battery drain)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Strict Internal NPU Execution",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Lock INT8 neural graphs to the MediaTek APU 655 and block CPU thread fallback for cooler operation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.useInternalNpuOnly,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            onToggleInternalNpuOnly(it)
                        }
                    )
                }
            }
        }
    }
}
