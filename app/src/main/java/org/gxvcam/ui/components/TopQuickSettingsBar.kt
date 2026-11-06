package org.gxvcam.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import org.gxvcam.ui.theme.GXVMotion

/**
 * Google Camera / Pixel Camera Material 3 Expressive Quick Settings Bar
 * - Cohesive floating glassmorphic islands
 * - Balanced horizontal layout that prevents clipping on all aspect ratios
 * - Integrated haptic feedback and fluid M3 spring menus
 */
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
    isDualCaptureEnabled: Boolean = false,
    onToggleDualCapture: () -> Unit = {},
    isFull: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()
    val buttonContainerColor = if (isFull) Color(0x66000000) else DarkSurfaceContainerHigh
    val borderStroke = BorderStroke(1.dp, Color.White.copy(alpha = if (isFull) 0.22f else 0.12f))

    var showProfileMenu by remember { mutableStateOf(false) }
    var showGridMenu by remember { mutableStateOf(false) }
    var showAeMenu by remember { mutableStateOf(false) }
    var showAfMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFull) Modifier.statusBarsPadding().padding(top = 8.dp)
                else Modifier.statusBarsPadding()
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Island: Flash Mode & Color Profile LUT pill
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = buttonContainerColor,
            border = borderStroke,
            tonalElevation = if (isFull) 0.dp else 4.dp,
            modifier = Modifier.height(40.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash Mode Button
                IconButton(
                    onClick = {
                        tactile.triggerTick()
                        onCycleFlash()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = when (flashMode) {
                            FlashMode.ON -> Icons.Default.FlashOn
                            FlashMode.AUTO -> Icons.Default.FlashAuto
                            FlashMode.TORCH -> Icons.Default.Highlight
                            FlashMode.OFF -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash Mode",
                        tint = if (flashMode != FlashMode.OFF) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Color Profile LUT Button
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                tactile.triggerSnap()
                                showProfileMenu = true
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Color Profile",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = activeProfile.label.split(" ").first(),
                            fontSize = 11.sp,
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
                                    tactile.triggerSnap()
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
                                tactile.triggerSnap()
                                showProfileMenu = false
                                onOpenLibPatcher()
                            }
                        )
                    }
                }

                // In Pro Mode: AE & AF Sony Alpha locks in the top island
                if (isProMode) {
                    VerticalDivider(
                        color = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.height(18.dp).padding(horizontal = 2.dp)
                    )

                    // Sony AE Mode / Lock Button
                    Box {
                        Text(
                            text = if (isAeLocked) "AE-L" else sonyAeMode.shortLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAeLocked) Color(0xFFFFB74D) else Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isAeLocked) Color(0x33FFB74D) else Color.Transparent)
                                .clickable {
                                    tactile.triggerSnap()
                                    showAeMenu = true
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )

                        DropdownMenu(
                            expanded = showAeMenu,
                            onDismissRequest = { showAeMenu = false },
                            modifier = Modifier.background(Color(0xFF1E1E1E)).widthIn(min = 230.dp)
                        ) {
                            Text(
                                text = "EXPOSURE MODE & LOCK (SONY)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isAeLocked) "Unlock Exposure (AE)" else "Lock Exposure (AE-L)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAeLocked) Color(0xFFFFB74D) else Color.White
                                    )
                                },
                                onClick = {
                                    tactile.triggerSnap()
                                    onToggleAeLock()
                                    showAeMenu = false
                                }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                            SonyAeMode.entries.forEach { mode ->
                                val isSelected = (sonyAeMode == mode && !isAeLocked) || (mode == SonyAeMode.AE_L && isAeLocked)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = mode.label,
                                            fontSize = 13.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                        )
                                    },
                                    onClick = {
                                        tactile.triggerSnap()
                                        if (mode == SonyAeMode.AE_L) onToggleAeLock() else onSelectAeMode(mode)
                                        showAeMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Sony AF Mode / Lock Button
                    Box {
                        Text(
                            text = if (isAfLocked) "AF-L" else sonyAfMode.shortLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAfLocked) Color(0xFFFFB74D) else Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isAfLocked) Color(0x33FFB74D) else Color.Transparent)
                                .clickable {
                                    tactile.triggerSnap()
                                    showAfMenu = true
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )

                        DropdownMenu(
                            expanded = showAfMenu,
                            onDismissRequest = { showAfMenu = false },
                            modifier = Modifier.background(Color(0xFF1E1E1E)).widthIn(min = 230.dp)
                        ) {
                            Text(
                                text = "FOCUS MODE & LOCK (SONY)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isAfLocked) "Unlock Focus (AF)" else "Lock Focus (AF-L)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAfLocked) Color(0xFFFFB74D) else Color.White
                                    )
                                },
                                onClick = {
                                    tactile.triggerSnap()
                                    onToggleAfLock()
                                    showAfMenu = false
                                }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                            SonyAfMode.entries.forEach { mode ->
                                val isSelected = (sonyAfMode == mode && !isAfLocked) || (mode == SonyAfMode.AF_L && isAfLocked)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = mode.label,
                                            fontSize = 13.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                        )
                                    },
                                    onClick = {
                                        tactile.triggerSnap()
                                        if (mode == SonyAfMode.AF_L) onToggleAfLock() else onSelectAfMode(mode)
                                        showAfMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Center Island: Recording Duration Pill or Aspect Ratio / RAW Format Chip
        if (isRecording) {
            val totalSeconds = recordingDurationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xDDFF3B30),
                modifier = Modifier.height(36.dp)
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
                        text = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = buttonContainerColor,
                border = borderStroke,
                tonalElevation = if (isFull) 0.dp else 4.dp,
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Capture Format Toggle (JPEG / RAW / RAW+JPEG) in Pro Mode
                    if (isProMode && isRawSupported) {
                        Text(
                            text = captureFormat.shortLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (captureFormat != CaptureFormat.JPEG) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    tactile.triggerTick()
                                    onCycleCaptureFormat()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        VerticalDivider(
                            color = Color.White.copy(alpha = 0.18f),
                            modifier = Modifier.height(14.dp)
                        )
                    }

                    // Aspect Ratio Toggle
                    Text(
                        text = aspectRatio.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                tactile.triggerTick()
                                onCycleAspectRatio()
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Right Island: Dual Capture PIP, Composition Grid & Settings Gear
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = buttonContainerColor,
            border = borderStroke,
            tonalElevation = if (isFull) 0.dp else 4.dp,
            modifier = Modifier.height(40.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dual Cam PIP Toggle
                IconButton(
                    onClick = {
                        tactile.triggerSnap()
                        onToggleDualCapture()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureInPicture,
                        contentDescription = "Dual Capture PIP",
                        tint = if (isDualCaptureEnabled) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Sony Alpha Grid Menu
                Box {
                    IconButton(
                        onClick = {
                            tactile.triggerSnap()
                            showGridMenu = true
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (gridEnabled && gridType != GridType.NONE) Icons.Default.GridOn else Icons.Default.GridOff,
                            contentDescription = "Composition Grid",
                            tint = if (gridEnabled && gridType != GridType.NONE) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showGridMenu,
                        onDismissRequest = { showGridMenu = false },
                        modifier = Modifier.background(Color(0xFF1E1E1E)).widthIn(min = 230.dp)
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
                                    tactile.triggerSnap()
                                    onSelectGrid(type)
                                    showGridMenu = false
                                }
                            )
                        }
                    }
                }

                // Camera Settings Gear
                IconButton(
                    onClick = {
                        tactile.triggerSnap()
                        onOpenSettings()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
