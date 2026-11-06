package org.gxvcam.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ui.state.AgcColorProfile
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.CaptureFormat
import org.gxvcam.ui.state.FlashMode
import org.gxvcam.ui.state.GridType
import org.gxvcam.ui.state.SonyAeMode
import org.gxvcam.ui.state.SonyAfMode
import org.gxvcam.ui.theme.DarkSurfaceContainerHigh

@Composable
fun TopQuickSettingsBar(
    flashMode: FlashMode,
    aspectRatio: AspectRatio,
    gridEnabled: Boolean,
    gridType: GridType = GridType.RULE_OF_THIRDS,
    activeProfile: AgcColorProfile = AgcColorProfile.ZEISS_NATURAL,
    isAeLocked: Boolean = false,
    isAfLocked: Boolean = false,
    sonyAeMode: SonyAeMode = SonyAeMode.AE_AUTO,
    sonyAfMode: SonyAfMode = SonyAfMode.AF_C,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onCycleFlash: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleGrid: () -> Unit,
    onSelectGrid: (GridType) -> Unit = {},
    onSelectProfile: (AgcColorProfile) -> Unit = {},
    onToggleAeLock: () -> Unit = {},
    onToggleAfLock: () -> Unit = {},
    onSelectAeMode: (SonyAeMode) -> Unit = {},
    onSelectAfMode: (SonyAfMode) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLibPatcher: () -> Unit = {},
    isProMode: Boolean = false,
    isRawSupported: Boolean = false,
    captureFormat: CaptureFormat = CaptureFormat.JPEG,
    onCycleCaptureFormat: () -> Unit = {},
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    val buttonContainerColor = if (isFull) Color(0x55000000) else DarkSurfaceContainerHigh
    val borderStroke = if (isFull) BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)) else null

    var showProfileMenu by remember { mutableStateOf(false) }
    var showGridMenu by remember { mutableStateOf(false) }
    var showAeMenu by remember { mutableStateOf(false) }
    var showAfMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFull) Modifier.statusBarsPadding().padding(top = 10.dp)
                else Modifier.statusBarsPadding()
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Group: Flash Mode & Color Profile Button (LUT Engine)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    .size(40.dp)
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
                    modifier = Modifier.size(19.dp)
                )
            }

            // Color Profile Dropdown Button (Near Flash on Top Left)
            Box {
                FilledTonalButton(
                    onClick = { showProfileMenu = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = buttonContainerColor,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = borderStroke,
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Color Profile",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = activeProfile.label.split(" ").first(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                DropdownMenu(
                    expanded = showProfileMenu,
                    onDismissRequest = { showProfileMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E))
                        .widthIn(min = 220.dp)
                ) {
                    Text(
                        text = "COLOR PROFILES (LUT)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )

                    AgcColorProfile.entries.forEach { profile ->
                        val isSelected = profile == activeProfile
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = profile.label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                    Text(
                                        text = profile.description,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                onSelectProfile(profile)
                                showProfileMenu = false
                            }
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "LibPatcher",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Open LibPatcher...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        onClick = {
                            showProfileMenu = false
                            onOpenLibPatcher()
                        }
                    )
                }
            }

            // AE Button (Sony Alpha Auto Exposure Mode & Lock)
            Box {
                FilledTonalButton(
                    onClick = { showAeMenu = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAeLocked) Color(0xFFFFB74D) else buttonContainerColor,
                        contentColor = if (isAeLocked) Color.Black else MaterialTheme.colorScheme.primary
                    ),
                    border = if (isAeLocked) null else borderStroke,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    if (isAeLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "AE Locked",
                            modifier = Modifier.size(12.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = if (isAeLocked) "AE-L" else sonyAeMode.shortLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAeLocked) Color.Black else Color.White
                    )
                }

                DropdownMenu(
                    expanded = showAeMenu,
                    onDismissRequest = { showAeMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E))
                        .widthIn(min = 230.dp)
                ) {
                    Text(
                        text = "EXPOSURE MODE & LOCK (SONY)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )

                    // Quick Toggle for AE Lock
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAeLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = "Toggle AE Lock",
                                    tint = if (isAeLocked) Color(0xFFFFB74D) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAeLocked) "Unlock Exposure (AE)" else "Lock Exposure (AE-L)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAeLocked) Color(0xFFFFB74D) else Color.White
                                )
                            }
                        },
                        onClick = {
                            onToggleAeLock()
                            showAeMenu = false
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    SonyAeMode.entries.forEach { mode ->
                        val isSelected = (sonyAeMode == mode && !isAeLocked) || (mode == SonyAeMode.AE_L && isAeLocked)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = mode.label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                    Text(
                                        text = mode.description,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                if (mode == SonyAeMode.AE_L) {
                                    onToggleAeLock()
                                } else {
                                    onSelectAeMode(mode)
                                }
                                showAeMenu = false
                            }
                        )
                    }
                }
            }

            // AF Button (Sony Alpha Auto Focus Mode & Lock)
            Box {
                FilledTonalButton(
                    onClick = { showAfMenu = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAfLocked) Color(0xFFFFB74D) else buttonContainerColor,
                        contentColor = if (isAfLocked) Color.Black else MaterialTheme.colorScheme.primary
                    ),
                    border = if (isAfLocked) null else borderStroke,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    if (isAfLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "AF Locked",
                            modifier = Modifier.size(12.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = if (isAfLocked) "AF-L" else sonyAfMode.shortLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAfLocked) Color.Black else Color.White
                    )
                }

                DropdownMenu(
                    expanded = showAfMenu,
                    onDismissRequest = { showAfMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E))
                        .widthIn(min = 230.dp)
                ) {
                    Text(
                        text = "FOCUS MODE & LOCK (SONY)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )

                    // Quick Toggle for AF Lock
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAfLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = "Toggle AF Lock",
                                    tint = if (isAfLocked) Color(0xFFFFB74D) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAfLocked) "Unlock Focus (AF)" else "Lock Focus (AF-L)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAfLocked) Color(0xFFFFB74D) else Color.White
                                )
                            }
                        },
                        onClick = {
                            onToggleAfLock()
                            showAfMenu = false
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    SonyAfMode.entries.forEach { mode ->
                        val isSelected = (sonyAfMode == mode && !isAfLocked) || (mode == SonyAfMode.AF_L && isAfLocked)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = mode.label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                    Text(
                                        text = mode.description,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                if (mode == SonyAfMode.AF_L) {
                                    onToggleAfLock()
                                } else {
                                    onSelectAfMode(mode)
                                }
                                showAfMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Center Group: Recording Duration or Format & Aspect Ratio
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = aspectRatio.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Right Group: Sony Alpha Grid Menu & Camera Settings
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sony Alpha Composition Grid Dropdown Button
            Box {
                FilledTonalIconButton(
                    onClick = { showGridMenu = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = buttonContainerColor,
                        contentColor = if (gridEnabled && gridType != GridType.NONE) MaterialTheme.colorScheme.primary else Color.White,
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .then(if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape) else Modifier),
                ) {
                    Icon(
                        imageVector = if (gridEnabled && gridType != GridType.NONE) Icons.Default.GridOn else Icons.Default.GridOff,
                        contentDescription = "Sony Alpha Grid Guides",
                        modifier = Modifier.size(19.dp),
                    )
                }

                DropdownMenu(
                    expanded = showGridMenu,
                    onDismissRequest = { showGridMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E))
                        .widthIn(min = 230.dp)
                ) {
                    Text(
                        text = "GRID GUIDES (SONY ALPHA)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )

                    GridType.entries.forEach { type ->
                        val isSelected = (gridEnabled && gridType == type) || (!gridEnabled && type == GridType.NONE)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = type.label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                    Text(
                                        text = type.subtitle,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                onSelectGrid(type)
                                showGridMenu = false
                            }
                        )
                    }
                }
            }

            // Camera Settings Button
            FilledTonalIconButton(
                onClick = onOpenSettings,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = buttonContainerColor,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .size(40.dp)
                    .then(if (isFull) Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape) else Modifier),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}
