package org.gxvcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.CaptureFormat
import org.gxvcam.ui.state.FlashMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh

@Composable
fun TopQuickSettingsBar(
    flashMode: FlashMode,
    aspectRatio: AspectRatio,
    gridEnabled: Boolean,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onCycleFlash: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleGrid: () -> Unit,
    onOpenSettings: () -> Unit = {},
    isProMode: Boolean = false,
    isRawSupported: Boolean = false,
    captureFormat: CaptureFormat = CaptureFormat.JPEG,
    onCycleCaptureFormat: () -> Unit = {},
    onToggleAgc: () -> Unit = {},
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    val buttonContainerColor = if (isFull) Color(0x55000000) else DarkSurfaceContainerHigh
    val borderStroke = if (isFull) BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)) else null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFull) Modifier.statusBarsPadding().padding(top = 10.dp)
                else Modifier.statusBarsPadding()
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash Mode Button
        FilledTonalIconButton(
            onClick = onCycleFlash,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = buttonContainerColor,
                contentColor = if (flashMode != FlashMode.OFF) MaterialTheme.colorScheme.primary else Color.White
            ),
            modifier = Modifier
                .size(42.dp)
                .then(if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape) else Modifier)
        ) {
            Icon(
                imageVector = when (flashMode) {
                    FlashMode.ON -> Icons.Default.FlashOn
                    FlashMode.AUTO -> Icons.Default.FlashAuto
                    FlashMode.TORCH -> Icons.Default.Highlight
                    FlashMode.OFF -> Icons.Default.FlashOff
                },
                contentDescription = "Flash Mode",
                modifier = Modifier.size(20.dp)
            )
        }

        // Recording Duration Badge or Center Toggles
        if (isRecording) {
            val totalSeconds = recordingDurationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xCCFF3B30),
                modifier = Modifier.height(34.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Text(
                        text = String.format("%02d:%02d", minutes, seconds),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Capture Format Toggle (JPEG / RAW / RAW+JPEG) in Pro Mode
                if (isProMode && isRawSupported) {
                    FilledTonalButton(
                        onClick = onCycleCaptureFormat,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (captureFormat != CaptureFormat.JPEG) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isFull) 0.85f else 1f) else buttonContainerColor,
                            contentColor = if (captureFormat != CaptureFormat.JPEG) MaterialTheme.colorScheme.onTertiaryContainer else Color.White
                        ),
                        border = if (captureFormat == CaptureFormat.JPEG) borderStroke else null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = captureFormat.shortLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Aspect Ratio Toggle
                FilledTonalButton(
                    onClick = onCycleAspectRatio,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = buttonContainerColor,
                        contentColor = Color.White
                    ),
                    border = borderStroke,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = aspectRatio.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // AGC LibPatcher Button
            FilledTonalIconButton(
                onClick = onToggleAgc,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = buttonContainerColor,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .size(42.dp)
                    .then(if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape) else Modifier),
            ) {
                Text(
                    text = "AGC",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Grid Toggle Button
            FilledTonalIconButton(
                onClick = onToggleGrid,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = buttonContainerColor,
                    contentColor = if (gridEnabled) MaterialTheme.colorScheme.primary else Color.White,
                ),
                modifier = Modifier
                    .size(42.dp)
                    .then(if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape) else Modifier),
            ) {
                Icon(
                    imageVector = Icons.Default.GridOn,
                    contentDescription = "Grid",
                    modifier = Modifier.size(20.dp),
                )
            }

            // Camera Settings Button
            FilledTonalIconButton(
                onClick = onOpenSettings,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = buttonContainerColor,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .size(42.dp)
                    .then(if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape) else Modifier),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Camera Settings",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
