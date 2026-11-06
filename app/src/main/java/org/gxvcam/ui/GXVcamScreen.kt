package org.gxvcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.gxvcam.ui.components.*
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.GXVcamEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.gxvcam.ui.state.DualCaptureLayout
import org.gxvcam.ui.state.GXVcamUiState
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.theme.GXVcamTheme
import org.gxvcam.ui.theme.GXVMotion
import org.gxvcam.ui.theme.OledBlack

@Composable
fun GXVcamScreen(
    viewModel: GXVcamViewModel = viewModel(),
    modifier: Modifier = Modifier,
    onOpenGallery: () -> Unit = {},
    viewfinderContent: @Composable () -> Unit,
    secondaryViewfinderContent: (@Composable () -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GXVcamScreenContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onOpenGallery = onOpenGallery,
        modifier = modifier,
        viewfinderContent = viewfinderContent,
        secondaryViewfinderContent = secondaryViewfinderContent
    )
}

@Composable
fun GXVcamScreenContent(
    uiState: GXVcamUiState,
    onEvent: (GXVcamEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGallery: () -> Unit = {},
    viewfinderContent: @Composable () -> Unit,
    secondaryViewfinderContent: (@Composable () -> Unit)? = null
) {
    val isFull = false
    val isDualCaptureActive = (uiState.currentMode == CameraMode.VIDEO || uiState.currentMode == CameraMode.PHOTO) &&
            uiState.motoVideoSettings.isDualCaptureEnabled &&
            secondaryViewfinderContent != null

    val effectiveViewfinderContent: @Composable () -> Unit = {
        if (isDualCaptureActive && uiState.motoVideoSettings.dualCaptureLayout == DualCaptureLayout.SPLIT) {
            DualCaptureSplitView(
                onSwap = { onEvent(GXVcamEvent.ToggleDualCaptureSwap) },
                primaryContent = viewfinderContent,
                secondaryContent = secondaryViewfinderContent
            )
        } else {
            viewfinderContent()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Center: Adaptive Viewfinder Container (spans 100% of the screen in FULL mode)
        GXVViewfinderContainer(
            aspectRatio = uiState.aspectRatio,
            gridEnabled = uiState.gridEnabled,
            gridType = uiState.manualSettings.gridType,
            showHorizon = uiState.manualSettings.isHorizonLevelVisible,
            levelHorizonDegrees = uiState.levelHorizonDegrees,
            currentZoom = uiState.currentZoomRatio,
            isBackCamera = uiState.isBackCamera,
            isAeLocked = uiState.isAeLocked,
            isAfLocked = uiState.isAfLocked,
            isAeAfLocked = uiState.isAeAfLocked,
            onEvent = onEvent,
            modifier = Modifier.fillMaxSize(),
            content = effectiveViewfinderContent
        )

        // Motorola Dual Capture Floating PIP Window (Draggable, Rounded Corner, Instant Swap)
        if (isDualCaptureActive && uiState.motoVideoSettings.dualCaptureLayout == DualCaptureLayout.PIP) {
            DualCapturePipWindow(
                onSwap = { onEvent(GXVcamEvent.ToggleDualCaptureSwap) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 110.dp, end = 16.dp),
                content = secondaryViewfinderContent
            )
        }

        // Top Overlay: Live RGB Histogram in Pro Mode or when enabled in settings
        AnimatedVisibility(
            visible = (uiState.currentMode == CameraMode.PRO && uiState.manualSettings.isHistogramOverlayVisible),
            enter = fadeIn(GXVMotion.fastEffects()),
            exit = fadeOut(GXVMotion.fastEffects()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 16.dp)
        ) {
            LiveRgbHistogram(
                histogram = uiState.histogram,
                isFull = isFull,
            )
        }

        // Top Column: Quick Settings Bar and Moto Video Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            TopQuickSettingsBar(
                flashMode = uiState.flashMode,
                aspectRatio = uiState.aspectRatio,
                gridEnabled = uiState.gridEnabled,
                gridType = uiState.manualSettings.gridType,
                activeProfile = uiState.agcSettings.activeProfile,
                isAeLocked = uiState.isAeLocked,
                isAfLocked = uiState.isAfLocked,
                sonyAeMode = uiState.sonyAeMode,
                sonyAfMode = uiState.sonyAfMode,
                isRecording = uiState.isRecording,
                recordingDurationMs = uiState.recordingDurationMs,
                onCycleFlash = { onEvent(GXVcamEvent.OnCycleFlash) },
                onCycleAspectRatio = { onEvent(GXVcamEvent.OnCycleAspectRatio) },
                onSelectGrid = { onEvent(GXVcamEvent.SetGridType(it)) },
                onSelectProfile = { onEvent(GXVcamEvent.SelectAgcProfile(it)) },
                onToggleAeLock = { onEvent(GXVcamEvent.SetAeLock(!uiState.isAeLocked)) },
                onToggleAfLock = { onEvent(GXVcamEvent.SetAfLock(!uiState.isAfLocked)) },
                onSelectAeMode = { onEvent(GXVcamEvent.SetSonyAeMode(it)) },
                onSelectAfMode = { onEvent(GXVcamEvent.SetSonyAfMode(it)) },
                onOpenSettings = { onEvent(GXVcamEvent.ToggleSettingsSheet) },
                onOpenLibPatcher = { onEvent(GXVcamEvent.ToggleAgcSheet) },
                isProMode = uiState.currentMode == CameraMode.PRO,
                isRawSupported = uiState.hardwareCapabilities.isRawSupported,
                captureFormat = uiState.manualSettings.captureFormat,
                onCycleCaptureFormat = { onEvent(GXVcamEvent.CycleCaptureFormat) },
                isDualCaptureEnabled = uiState.motoVideoSettings.isDualCaptureEnabled,
                onToggleDualCapture = {
                    onEvent(
                        GXVcamEvent.UpdateMotoVideoSettings(
                            uiState.motoVideoSettings.copy(isDualCaptureEnabled = !uiState.motoVideoSettings.isDualCaptureEnabled)
                        )
                    )
                },
                isFull = isFull
            )

            // Photo Mode Dual Capture Quick Bar (shown when Dual Cam is active in Photo mode)
            AnimatedVisibility(
                visible = uiState.currentMode == CameraMode.PHOTO && uiState.motoVideoSettings.isDualCaptureEnabled,
                enter = fadeIn(GXVMotion.fastEffects()),
                exit = fadeOut(GXVMotion.fastEffects())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = true,
                        onClick = {
                            val nextLayout = if (uiState.motoVideoSettings.dualCaptureLayout == DualCaptureLayout.PIP) {
                                DualCaptureLayout.SPLIT
                            } else {
                                DualCaptureLayout.PIP
                            }
                            onEvent(GXVcamEvent.SetDualCaptureLayout(nextLayout))
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.motoVideoSettings.dualCaptureLayout == DualCaptureLayout.PIP) {
                                    Icons.Default.PictureInPictureAlt
                                } else {
                                    Icons.Default.VerticalSplit
                                },
                                contentDescription = "Dual Capture Layout",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (uiState.motoVideoSettings.dualCaptureLayout == DualCaptureLayout.PIP) "PIP Window" else "50:50 Split",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    FilterChip(
                        selected = uiState.motoVideoSettings.isDualCaptureSwapped,
                        onClick = { onEvent(GXVcamEvent.ToggleDualCaptureSwap) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Swap Cameras",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = { Text("Swap (⇄)", fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                }
            }

            // Motorola Contextual Badges: Macro lock & Night Vision in PHOTO Mode
            val showMacroPill = uiState.manualSettings.isAutoMacroEnabled
            val showNightPill = uiState.manualSettings.isAutoNightVisionEnabled
            AnimatedVisibility(
                visible = uiState.currentMode == CameraMode.PHOTO && (showMacroPill || showNightPill),
                enter = fadeIn(GXVMotion.fastEffects()),
                exit = fadeOut(GXVMotion.fastEffects())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Macro Pill
                    if (showMacroPill) Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (uiState.isMacroLocked) MaterialTheme.colorScheme.primaryContainer else Color(0x66000000),
                        border = BorderStroke(1.dp, if (uiState.isMacroLocked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.20f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onEvent(GXVcamEvent.ToggleMacroLock) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "✿",
                                fontSize = 12.sp,
                                color = if (uiState.isMacroLocked) MaterialTheme.colorScheme.primary else Color.White
                            )
                            Text(
                                text = if (uiState.isMacroLocked) "Macro ON" else "Macro",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.isMacroLocked) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                            )
                        }
                    }

                    if (showMacroPill && showNightPill) Spacer(modifier = Modifier.width(8.dp))

                    // Night Vision Pill (rebinds PHOTO with the OEM Night extension)
                    if (showNightPill) Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (uiState.isAutoNightVisionActive) Color(0xCCFFB74D) else Color(0x66000000),
                        border = BorderStroke(1.dp, if (uiState.isAutoNightVisionActive) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.20f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onEvent(GXVcamEvent.ToggleAutoNightVision) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "🌙", fontSize = 11.sp)
                            Text(
                                text = if (uiState.isAutoNightVisionActive) "Night ON" else "Night",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.isAutoNightVisionActive) Color.Black else Color.White
                            )
                        }
                    }
                }
            }

            // Moto Cam 5 Video Quick Bar (shown when in Video mode)
            AnimatedVisibility(
                visible = uiState.currentMode == CameraMode.VIDEO,
                enter = fadeIn(GXVMotion.fastEffects()),
                exit = fadeOut(GXVMotion.fastEffects())
            ) {
                MotoVideoQuickBar(
                    videoSettings = uiState.motoVideoSettings,
                    onUpdateVideo = { onEvent(GXVcamEvent.UpdateMotoVideoSettings(it)) }
                )
            }

            // Pro Manual Top Bar (when TOP_BAR layout is selected)
            if (uiState.manualSettings.proLayoutPosition == org.gxvcam.ui.state.ProLayoutPosition.TOP_BAR) {
                ProManualDeck(
                    visible = uiState.currentMode == CameraMode.PRO,
                    proSettings = uiState.proSettings,
                    activeControl = uiState.activeProControl,
                    hardwareCapabilities = uiState.hardwareCapabilities,
                    onControlSelected = { onEvent(GXVcamEvent.OnActiveProControlSelected(it)) },
                    onProSettingChanged = { onEvent(GXVcamEvent.OnProSettingChanged(it)) },
                    layoutPosition = org.gxvcam.ui.state.ProLayoutPosition.TOP_BAR,
                    isFull = isFull,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }

        // Pro Manual Vertical Sidebar (Right edge)
        if (uiState.manualSettings.proLayoutPosition == org.gxvcam.ui.state.ProLayoutPosition.SIDEBAR_RIGHT) {
            ProManualDeck(
                visible = uiState.currentMode == CameraMode.PRO,
                proSettings = uiState.proSettings,
                activeControl = uiState.activeProControl,
                hardwareCapabilities = uiState.hardwareCapabilities,
                onControlSelected = { onEvent(GXVcamEvent.OnActiveProControlSelected(it)) },
                onProSettingChanged = { onEvent(GXVcamEvent.OnProSettingChanged(it)) },
                layoutPosition = org.gxvcam.ui.state.ProLayoutPosition.SIDEBAR_RIGHT,
                isFull = isFull,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
            )
        }

        // Pro Manual Vertical Sidebar (Left edge)
        if (uiState.manualSettings.proLayoutPosition == org.gxvcam.ui.state.ProLayoutPosition.SIDEBAR_LEFT) {
            ProManualDeck(
                visible = uiState.currentMode == CameraMode.PRO,
                proSettings = uiState.proSettings,
                activeControl = uiState.activeProControl,
                hardwareCapabilities = uiState.hardwareCapabilities,
                onControlSelected = { onEvent(GXVcamEvent.OnActiveProControlSelected(it)) },
                onProSettingChanged = { onEvent(GXVcamEvent.OnProSettingChanged(it)) },
                layoutPosition = org.gxvcam.ui.state.ProLayoutPosition.SIDEBAR_LEFT,
                isFull = isFull,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp),
            )
        }

        // Bottom Overlays: Pro Controls, AGC LUT Carousel, Lens Selector, and Control Deck
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pro Manual Parameter Deck (visible in PRO mode only if BOTTOM_DOCK is selected)
            if (uiState.manualSettings.proLayoutPosition == org.gxvcam.ui.state.ProLayoutPosition.BOTTOM_DOCK) {
                ProManualDeck(
                    visible = uiState.currentMode == CameraMode.PRO,
                    proSettings = uiState.proSettings,
                    activeControl = uiState.activeProControl,
                    hardwareCapabilities = uiState.hardwareCapabilities,
                    onControlSelected = { onEvent(GXVcamEvent.OnActiveProControlSelected(it)) },
                    onProSettingChanged = { onEvent(GXVcamEvent.OnProSettingChanged(it)) },
                    layoutPosition = org.gxvcam.ui.state.ProLayoutPosition.BOTTOM_DOCK,
                    isFull = isFull,
                    modifier = Modifier.fillMaxWidth(),
                )
            }



            // M3 Expressive Lens Pill Selector or Motorola 4-Prime Portrait Deck
            if (uiState.currentMode == CameraMode.PORTRAIT && uiState.isBackCamera) {
                MotoPortraitDeck(
                    activeLens = uiState.activePortraitLens,
                    aperture = uiState.portraitAperture,
                    isApertureExpanded = uiState.isPortraitApertureExpanded,
                    onLensSelected = { onEvent(GXVcamEvent.SelectPortraitLens(it)) },
                    onApertureChanged = { onEvent(GXVcamEvent.SetPortraitAperture(it)) },
                    onToggleApertureExpanded = { onEvent(GXVcamEvent.SetPortraitApertureExpanded(it)) },
                    isFull = isFull
                )
            } else {
                ExpressiveLensSelector(
                    activeLens = uiState.activeLens,
                    currentZoomRatio = uiState.currentZoomRatio,
                    onLensSelected = { onEvent(GXVcamEvent.OnLensSelected(it)) },
                    onZoomChanged = { onEvent(GXVcamEvent.OnZoomChanged(it)) },
                    onZoomStep = { onEvent(GXVcamEvent.SetZoomRatio(it)) },
                    isExpanded = uiState.isZoomSliderExpanded,
                    onExpandedChange = { onEvent(GXVcamEvent.SetZoomSliderExpanded(it)) },
                    isFull = isFull,
                    isBackCamera = uiState.isBackCamera
                )
            }

            // Primary Morphing Shutter & Mode Deck
            BottomControlDeck(
                currentMode = uiState.currentMode,
                isRecording = uiState.isRecording,
                lastCapturedUri = uiState.lastCapturedUri,
                onModeSelected = { onEvent(GXVcamEvent.OnModeSelected(it)) },
                onShutterClick = {
                    if (uiState.currentMode == CameraMode.VIDEO) {
                        if (uiState.isRecording) {
                            onEvent(GXVcamEvent.OnStopRecording)
                        } else {
                            onEvent(GXVcamEvent.OnStartRecording)
                        }
                    } else {
                        onEvent(GXVcamEvent.OnShutterClicked)
                    }
                },
                onFlipCamera = { onEvent(GXVcamEvent.OnFlipCamera) },
                onOpenGallery = onOpenGallery,
                isFull = isFull
            )
        }

        // BigKaka AGC LibPatcher Bottom Sheet
        if (uiState.isAgcSheetOpen) {
            AgcLibPatcherBottomSheet(
                agcSettings = uiState.agcSettings,
                onUpdateAgc = { onEvent(GXVcamEvent.UpdateAgcSettings(it)) },
                onResetToDefaults = { onEvent(GXVcamEvent.ResetAgcToDefaults) },
                onDismiss = { onEvent(GXVcamEvent.ToggleAgcSheet) }
            )
        }

        // Motorola Camera 5 + GCam Hardware & Manual Settings Bottom Sheet
        if (uiState.isSettingsSheetOpen) {
            CameraSettingsBottomSheet(
                settings = uiState.manualSettings,
                onUpdateSettings = { onEvent(GXVcamEvent.UpdateManualSettings(it)) },
                onDismiss = { onEvent(GXVcamEvent.ToggleSettingsSheet) }
            )
        }
    }
}

@Composable
fun DualCapturePipWindow(
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val tactile = rememberTactileFeedback()

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(width = 120.dp, height = 160.dp)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
    ) {
        content()

        // Swap button on top-right of PIP window
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xAA000000))
                .clickable {
                    tactile.triggerTick()
                    onSwap()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⇄",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DualCaptureSplitView(
    onSwap: () -> Unit,
    primaryContent: @Composable () -> Unit,
    secondaryContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val tactile = rememberTactileFeedback()

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Top half (Primary camera)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            primaryContent()
        }

        // Center sleek divider with Swap button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color(0xDD121212)),
            contentAlignment = Alignment.Center
        ) {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Surface(
                onClick = {
                    tactile.triggerTick()
                    onSwap()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "⇄",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Swap",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Bottom half (Secondary camera)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            secondaryContent()
        }
    }
}

@Preview(
    name = "Motorola Edge 50 Neo / 60 Neo (pOLED 6.4\")",
    device = "spec:width=1080px,height=2400px,dpi=440",
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun GXVcamScreenPreview() {
    GXVcamTheme {
        GXVcamScreenContent(
            uiState = GXVcamUiState(
                currentMode = CameraMode.PRO,
                levelHorizonDegrees = 0.5f,
                gridEnabled = true
            ),
            onEvent = {},
            viewfinderContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OledBlack)
                )
            }
        )
    }
}
