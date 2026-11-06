package org.gxvcam.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.gxvcam.ui.components.*
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.GXVcamEvent
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
    viewfinderContent: @Composable () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GXVcamScreenContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onOpenGallery = onOpenGallery,
        modifier = modifier,
        viewfinderContent = viewfinderContent
    )
}

@Composable
fun GXVcamScreenContent(
    uiState: GXVcamUiState,
    onEvent: (GXVcamEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGallery: () -> Unit = {},
    viewfinderContent: @Composable () -> Unit
) {
    val isFull = uiState.aspectRatio == AspectRatio.RATIO_FULL
    val view = LocalView.current

    // Immersive Mode: When FULL aspect ratio is selected, hide notification & time status bar
    DisposableEffect(isFull) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFull) {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        onDispose {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
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
            onEvent = onEvent,
            modifier = Modifier.fillMaxSize(),
            content = viewfinderContent
        )

        // Top Overlay: Live RGB Histogram in Pro Mode or when enabled in settings
        AnimatedVisibility(
            visible = (uiState.currentMode == CameraMode.PRO && uiState.manualSettings.isHistogramOverlayVisible),
            enter = fadeIn(GXVMotion.fastEffects()),
            exit = fadeOut(GXVMotion.fastEffects()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (isFull) 60.dp else 72.dp, end = 16.dp)
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
                isRecording = uiState.isRecording,
                recordingDurationMs = uiState.recordingDurationMs,
                onCycleFlash = { onEvent(GXVcamEvent.OnCycleFlash) },
                onCycleAspectRatio = { onEvent(GXVcamEvent.OnCycleAspectRatio) },
                onToggleGrid = { onEvent(GXVcamEvent.OnToggleGrid) },
                onOpenSettings = { onEvent(GXVcamEvent.ToggleSettingsSheet) },
                isProMode = uiState.currentMode == CameraMode.PRO,
                isRawSupported = uiState.hardwareCapabilities.isRawSupported,
                isRawAndJpegEnabled = uiState.proSettings.isRawAndJpegEnabled,
                onToggleRaw = { onEvent(GXVcamEvent.ToggleRawCapture) },
                onToggleAgc = { onEvent(GXVcamEvent.ToggleAgcSheet) },
                isFull = isFull
            )

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

            // AGC LUT Color Profile Carousel (hidden in PRO mode to prevent visual clutter)
            AnimatedVisibility(
                visible = uiState.currentMode != CameraMode.PRO,
                enter = fadeIn(GXVMotion.fastEffects()),
                exit = fadeOut(GXVMotion.fastEffects())
            ) {
                AgcLutCarousel(
                    selectedProfile = uiState.agcSettings.activeProfile,
                    onSelectProfile = { onEvent(GXVcamEvent.SelectAgcProfile(it)) },
                    isFull = isFull
                )
            }

            // M3 Expressive Lens Pill Selector
            ExpressiveLensSelector(
                activeLens = uiState.activeLens,
                currentZoomRatio = uiState.currentZoomRatio,
                onLensSelected = { onEvent(GXVcamEvent.OnLensSelected(it)) },
                onZoomChanged = { onEvent(GXVcamEvent.OnZoomChanged(it)) },
                isFull = isFull
            )

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
