package org.gxvcam.ui.viewfinder

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import org.gxvcam.camera.*
import org.gxvcam.ui.theme.*

@Composable
fun CameraScreen(
    controller: Camera2Controller,
    modifier: Modifier = Modifier
) {
    val tactile = org.gxvcam.ui.components.rememberTactileFeedback()
    var lastEvStep by remember { mutableIntStateOf(0) }
    var lastShadowStep by remember { mutableIntStateOf(0) }
    val uiState by controller.uiState.collectAsState()
    val currentSensor by controller.currentSensor.collectAsState()
    val activeAuxButton by controller.activeAuxButton.collectAsState()

    // Mode state (Moto Hello UI modes)
    val modes = listOf("Slow Mo", "Video", "Photo", "Portrait", "Pro", "Night Vision", "Ultra-Res 50M")
    var selectedMode by remember { mutableStateOf("Photo") }

    // Quick Settings State
    var flashState by remember { mutableStateOf("OFF") } // OFF, AUTO, ON
    var hdrState by remember { mutableStateOf("HDR+") } // OFF, HDR+, AUTO
    var activeLook by remember { mutableStateOf("Zeiss Natural") } // Zeiss Natural, Vivo Vivid, Moto Natural, Leica Mono
    var ratioState by remember { mutableStateOf("3:4") } // 3:4, 9:16, 1:1, Full
    var isAwbActive by remember { mutableStateOf(true) }
    var isTopDrawerOpen by remember { mutableStateOf(false) }
    var isSettingsDialogOpen by remember { mutableStateOf(false) }

    // Pro mode manual parameters
    var activeProControl by remember { mutableStateOf<String?>(null) } // ISO, SHUTTER, FOCUS, WB, EV
    var proIso by remember { mutableIntStateOf(0) } // 0 = Auto, 100, 200, 400, 800, 1600, 3200, 6400
    var proShutter by remember { mutableStateOf("AUTO") } // AUTO, 1/1000s, 1/250s, 1/30s, 1s, 4s, 32s (Moto tag)
    var proFocus by remember { mutableFloatStateOf(-1f) } // -1 = Auto, 0f = Infinity, 10f = Macro

    // Tap-to-focus & GCam Dual Exposure Sliders
    var showExposureSliders by remember { mutableStateOf(false) }
    var exposureSliderValue by remember { mutableFloatStateOf(0f) }
    var shadowSliderValue by remember { mutableFloatStateOf(0f) }
    var focusRingOffset by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // Shutter & Camera flip animations
    var isShutterPressed by remember { mutableStateOf(false) }
    val shutterScale by animateFloatAsState(targetValue = if (isShutterPressed) 0.88f else 1.0f, label = "shutterAnim")
    var flipRotation by remember { mutableFloatStateOf(0f) }
    val animatedFlipRotation by animateFloatAsState(targetValue = flipRotation, label = "flipAnim")

    // Sync mode changes to controller
    LaunchedEffect(selectedMode) {
        controller.isProMode = (selectedMode == "Pro")
        controller.isUltraRes50M = (selectedMode == "Ultra-Res 50M")
        if (selectedMode != "Pro") {
            activeProControl = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    focusRingOffset = Pair(offset.x, offset.y)
                    showExposureSliders = true
                }
            }
    ) {
        // 1. Edge-to-Edge Viewfinder
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            controller.openCamera(currentSensor, holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            controller.closeCamera()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. GCam Artificial Horizon Leveler Overlay
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(160.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.3f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFD54F).copy(alpha = 0.8f))
        )

        // 3. Focus Indicator Ring
        focusRingOffset?.let { (fx, fy) ->
            Box(
                modifier = Modifier
                    .offset(x = (fx - 36).dp, y = (fy - 36).dp)
                    .size(72.dp)
                    .border(1.5.dp, Color(0xFFFFD54F), CircleShape)
            )
        }

        // 4. GCam Dual Exposure Sliders (Highlight EV + Shadow Tone on right edge)
        if (showExposureSliders) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(PillBackground)
                    .padding(vertical = 12.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.WbSunny, contentDescription = "Highlight EV", tint = Color.White, modifier = Modifier.size(18.dp))
                Slider(
                    value = exposureSliderValue,
                    onValueChange = {
                        val step = (it * 3f).roundToInt()
                        if (step != lastEvStep) {
                            tactile.triggerTick()
                            lastEvStep = step
                        }
                        exposureSliderValue = it
                        controller.setExposureCompensation(it)
                    },
                    valueRange = -2f..2f,
                    modifier = Modifier
                        .height(110.dp)
                        .width(30.dp)
                )
                Icon(Icons.Default.BrightnessMedium, contentDescription = "Shadows", tint = Color.White, modifier = Modifier.size(18.dp))
                Slider(
                    value = shadowSliderValue,
                    onValueChange = {
                        val step = (it * 10f).roundToInt()
                        if (step != lastShadowStep) {
                            tactile.triggerTick()
                            lastShadowStep = step
                        }
                        shadowSliderValue = it
                    },
                    valueRange = -1f..1f,
                    modifier = Modifier
                        .height(110.dp)
                        .width(30.dp)
                )
            }
        }

        // 5. Motorola Hello UI Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flash Toggle
            IconButton(
                onClick = {
                    flashState = when (flashState) {
                        "OFF" -> "AUTO"
                        "AUTO" -> "ON"
                        else -> "OFF"
                    }
                    controller.flashMode = flashState
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = when (flashState) {
                        "ON" -> Icons.Default.FlashOn
                        "AUTO" -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    },
                    contentDescription = "Flash",
                    tint = if (flashState == "ON") Color(0xFFFFD54F) else Color.White
                )
            }

            // HDR Toggle
            IconButton(
                onClick = {
                    hdrState = when (hdrState) {
                        "OFF" -> "HDR+"
                        "HDR+" -> "AUTO"
                        else -> "OFF"
                    }
                    controller.hdrMode = hdrState
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (hdrState != "OFF") Icons.Default.HdrOn else Icons.Default.HdrOff,
                    contentDescription = "HDR",
                    tint = if (hdrState != "OFF") AccentTeal else Color.White.copy(alpha = 0.6f)
                )
            }

            // Aspect Ratio
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(PillBackground)
                    .clickable {
                        ratioState = when (ratioState) {
                            "3:4" -> "9:16"
                            "9:16" -> "1:1"
                            "1:1" -> "Full"
                            else -> "3:4"
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(ratioState, color = Color.White, fontSize = 12.sp, style = MaterialTheme.typography.labelSmall)
            }

            // Vivo / Zeiss Color Look Badge (Motorola Color Tone style)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentTealContainer)
                    .clickable {
                        activeLook = when (activeLook) {
                            "Zeiss Natural" -> "Vivo Vivid"
                            "Vivo Vivid" -> "Moto Natural"
                            "Moto Natural" -> "Leica Mono"
                            else -> "Zeiss Natural"
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Palette, contentDescription = "Look", tint = AccentTeal, modifier = Modifier.size(14.dp))
                    Text(activeLook, color = AccentTeal, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Quick Settings Drawer Toggle + Gear
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { isTopDrawerOpen = !isTopDrawerOpen }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isTopDrawerOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { isSettingsDialogOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }

        // 6. GCam AWB Badge (Floating left)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isAwbActive) AccentTealContainer else PillBackground)
                .clickable {
                    isAwbActive = !isAwbActive
                    controller.isAwbEnabled = isAwbActive
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                "AWB",
                color = if (isAwbActive) AccentTeal else Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // 7. Motorola Expandable Quick Settings Tray
        AnimatedVisibility(
            visible = isTopDrawerOpen,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 16.dp, end = 16.dp)
        ) {
            MotoQuickSettingsCard(
                activeLook = activeLook,
                onLookChange = { activeLook = it },
                onDismiss = { isTopDrawerOpen = false }
            )
        }

        // 8. Bottom Controls Cluster (Moto Hello UI Layout)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pro Mode Manual Control Strip (shown only when in "Pro" mode)
            if (selectedMode == "Pro") {
                MotoProControlBar(
                    activeControl = activeProControl,
                    onControlSelected = { activeProControl = if (activeProControl == it) null else it },
                    iso = proIso,
                    onIsoChange = {
                        proIso = it
                        controller.setManualIso(if (it == 0) null else it)
                    },
                    shutter = proShutter,
                    onShutterChange = {
                        proShutter = it
                        controller.setManualExposureNs(
                            when (it) {
                                "1/1000s" -> 1_000_000L
                                "1/250s" -> 4_000_000L
                                "1/30s" -> 33_333_333L
                                "1s" -> 1_000_000_000L
                                "4s" -> 4_000_000_000L
                                "32s" -> 32_000_000_000L // Motorola 32s Vendor Exposure Tag!
                                else -> null
                            }
                        )
                    },
                    focus = proFocus,
                    onFocusChange = {
                        proFocus = it
                        controller.setManualFocusDist(if (it < 0f) null else it)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Motorola Hello UI Zoom Switcher (.5, 1x, 2x, 3x, 6x)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(PillBackground)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceCameraProfiles.SGCAM_AUX_BUTTONS.forEach { btn ->
                    val isSelected = activeAuxButton.label == btn.label
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) PillSelectedBackground else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) AccentTeal else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { controller.switchAuxLens(btn) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = btn.label,
                            color = if (isSelected) AccentTeal else Color.White,
                            fontSize = 12.sp,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Motorola Mode Carousel
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(modes) { mode ->
                    val isSelected = mode == selectedMode
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { selectedMode = mode }
                    ) {
                        Text(
                            text = mode,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            style = if (isSelected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .width(16.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(AccentTeal)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Motorola Signature Shutter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Shortcut
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                        .clickable {},
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(24.dp))
                }

                // Motorola Shutter Button (Concentric dual ring)
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .scale(shutterScale)
                        .border(3.5.dp, ShutterOuterRing, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (selectedMode) {
                                "Video", "Slow Mo" -> Color(0xFFE53935)
                                "Ultra-Res 50M" -> Color(0xFFFFB300)
                                else -> ShutterInnerCore
                            }
                        )
                        .clickable {
                            isShutterPressed = true
                            controller.takePicture()
                            isShutterPressed = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when (selectedMode) {
                        "Night Vision" -> {
                            Icon(Icons.Default.Bedtime, contentDescription = "Night", tint = DarkBackground, modifier = Modifier.size(26.dp))
                        }
                        "Ultra-Res 50M" -> {
                            Text("50M", color = DarkBackground, fontSize = 14.sp, style = MaterialTheme.typography.labelLarge)
                        }
                        "Pro" -> {
                            Text("PRO", color = DarkBackground, fontSize = 12.sp, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Motorola Camera Switch Toggle
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                        .clickable {
                            flipRotation += 180f
                            controller.toggleCameraFacing()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(animatedFlipRotation)
                    )
                }
            }
        }

        // Settings Dialog
        if (isSettingsDialogOpen) {
            MotoSettingsDialog(onDismiss = { isSettingsDialogOpen = false })
        }
    }
}

@Composable
fun MotoProControlBar(
    activeControl: String?,
    onControlSelected: (String) -> Unit,
    iso: Int,
    onIsoChange: (Int) -> Unit,
    shutter: String,
    onShutterChange: (String) -> Unit,
    focus: Float,
    onFocusChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Active Sub-Slider
        when (activeControl) {
            "ISO" -> {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(PillBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 100, 200, 400, 800, 1600, 3200, 6400).forEach { iVal ->
                        val isSel = iVal == iso
                        Text(
                            text = if (iVal == 0) "AUTO" else "$iVal",
                            color = if (isSel) AccentTeal else Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { onIsoChange(iVal) }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            "SHUTTER" -> {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(PillBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("AUTO", "1/1000s", "1/250s", "1/30s", "1s", "4s", "32s").forEach { sVal ->
                        val isSel = sVal == shutter
                        Text(
                            text = sVal,
                            color = if (isSel) AccentTeal else Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { onShutterChange(sVal) }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            "FOCUS" -> {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(PillBackground)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "AUTO",
                        color = if (focus < 0f) AccentTeal else Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onFocusChange(-1f) }
                    )
                    Text(
                        "MACRO",
                        color = if (focus == 10f) AccentTeal else Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onFocusChange(10f) }
                    )
                    Text(
                        "INFINITY",
                        color = if (focus == 0f) AccentTeal else Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onFocusChange(0f) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Top Pro Control Toggles
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PillBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProButton("ISO", isSelected = activeControl == "ISO", onClick = { onControlSelected("ISO") })
            ProButton("SHUTTER", isSelected = activeControl == "SHUTTER", onClick = { onControlSelected("SHUTTER") })
            ProButton("FOCUS", isSelected = activeControl == "FOCUS", onClick = { onControlSelected("FOCUS") })
            ProButton("RAW", isSelected = false, onClick = {})
        }
    }
}

@Composable
fun ProButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (isSelected) AccentTeal else Color.White.copy(alpha = 0.8f),
        fontSize = 11.sp,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun MotoQuickSettingsCard(
    activeLook: String,
    onLookChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF21C1C20)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("AI Color & Computational Engine", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Zeiss Natural", "Vivo Vivid", "Moto Natural", "Leica Mono").forEach { look ->
                    val isSel = look == activeLook
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) AccentTealContainer else PillBackground)
                            .clickable { onLookChange(look) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = look.split(" ").last(),
                            color = if (isSel) AccentTeal else Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MotoSettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Motorola Edge 50 Neo Camera Hardware") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SoC: MediaTek Dimensity 7300 (MT6878)", color = Color.White)
                Text("ISP: MediaTek Imagiq 950", color = Color.White)
                Text("Main: 50MP Sony IMX896 / LYT-700C (Quad-Bayer)", color = Color.White)
                Text("Ultrawide: 13MP GalaxyCore GC13A2", color = Color.White)
                Text("Telephoto: 10MP Samsung S5K3K1 (3x Optical)", color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))
                Text("• MediaTek Hardware ZSL: ACTIVE", color = AccentTeal)
                Text("• Motorola Quad-Bayer Remosaic: SUPPORTED (50MP)", color = AccentTeal)
                Text("• Motorola 32s Long Exposure: UNLOCKED", color = AccentTeal)
                Text("• Vivo Zeiss Natural Color Science: ACTIVE", color = AccentTeal)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK", color = AccentTeal) }
        },
        containerColor = Color(0xFF222226)
    )
}
