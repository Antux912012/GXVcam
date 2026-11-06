package org.gxvcam.ui.state

import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.gxvcam.camera.GXVcamController

class GXVcamViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GXVcamUiState())
    val uiState: StateFlow<GXVcamUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null

    private var controller: GXVcamController? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    fun attachController(
        controller: GXVcamController,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
    ) {
        this.controller = controller
        this.lifecycleOwner = lifecycleOwner
        this.surfaceProvider = surfaceProvider

        controller.setLifecycleOwner(lifecycleOwner)
        controller.setOnEventListener { onEvent(it) }

        controller.onCapabilitiesReady = { capabilities ->
            onEvent(GXVcamEvent.UpdateHardwareCapabilities(capabilities))
        }
        controller.onHistogramReady = { histogram ->
            onEvent(GXVcamEvent.UpdateHistogram(histogram))
        }

        rebindCamera()
    }

    override fun onCleared() {
        super.onCleared()
        stopRecordingTimer()
        controller = null
        lifecycleOwner = null
        surfaceProvider = null
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                _uiState.update { it.copy(recordingDurationMs = it.recordingDurationMs + 1000L) }
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    fun onEvent(event: GXVcamEvent) {
        when (event) {
            is GXVcamEvent.SelectMode -> handleModeSelected(event.mode)
            is GXVcamEvent.OnModeSelected -> handleModeSelected(event.mode)

            is GXVcamEvent.SelectLens -> handleLensSelected(event.lens)
            is GXVcamEvent.OnLensSelected -> handleLensSelected(event.lens)

            is GXVcamEvent.SetZoomRatio -> handleZoomChanged(event.zoom)
            is GXVcamEvent.OnZoomChanged -> handleZoomChanged(event.zoomRatio)

            is GXVcamEvent.ToggleCameraFacing -> handleFlipCamera()
            is GXVcamEvent.OnFlipCamera -> handleFlipCamera()

            is GXVcamEvent.CycleFlashMode -> handleCycleFlash()
            is GXVcamEvent.OnCycleFlash -> handleCycleFlash()

            is GXVcamEvent.CycleAspectRatio -> handleCycleAspectRatio()
            is GXVcamEvent.OnCycleAspectRatio -> handleCycleAspectRatio()

            is GXVcamEvent.ToggleGrid -> _uiState.update { it.copy(gridEnabled = !it.gridEnabled) }
            is GXVcamEvent.OnToggleGrid -> _uiState.update { it.copy(gridEnabled = !it.gridEnabled) }

            is GXVcamEvent.UpdateProSettings -> {
                _uiState.update { it.copy(proSettings = event.settings) }
                val isVideo = _uiState.value.currentMode == CameraMode.VIDEO
                controller?.applyProSettings(event.settings, isVideoMode = isVideo)
            }
            is GXVcamEvent.OnProSettingChanged -> {
                val newSettings = event.transform(_uiState.value.proSettings)
                _uiState.update { it.copy(proSettings = newSettings) }
                val isVideo = _uiState.value.currentMode == CameraMode.VIDEO
                controller?.applyProSettings(newSettings, isVideoMode = isVideo)
            }

            is GXVcamEvent.UpdateHardwareCapabilities -> {
                _uiState.update { it.copy(hardwareCapabilities = event.capabilities) }
            }
            is GXVcamEvent.OnHardwareCapabilitiesUpdated -> {
                _uiState.update { it.copy(hardwareCapabilities = event.capabilities) }
            }

            is GXVcamEvent.UpdateHistogram -> {
                _uiState.update { it.copy(histogram = event.data) }
            }
            is GXVcamEvent.OnHistogramUpdated -> {
                _uiState.update { it.copy(histogram = event.histogram) }
            }

            is GXVcamEvent.UpdateHorizonLevel -> {
                _uiState.update { it.copy(levelHorizonDegrees = event.degrees) }
            }
            is GXVcamEvent.OnLevelHorizonUpdated -> {
                _uiState.update { it.copy(levelHorizonDegrees = event.degrees) }
            }

            is GXVcamEvent.OnPhotoCaptured -> {
                _uiState.update { it.copy(lastCapturedUri = event.uri, isCapturingPhoto = false) }
            }

            is GXVcamEvent.SetRecordingState -> {
                _uiState.update {
                    it.copy(
                        isRecording = event.isRecording,
                        recordingDurationMs = if (event.isRecording) 0L else it.recordingDurationMs
                    )
                }
                if (event.isRecording) {
                    startRecordingTimer()
                } else {
                    stopRecordingTimer()
                }
            }

            is GXVcamEvent.ToggleRawCapture -> {
                if (_uiState.value.hardwareCapabilities.isRawSupported) {
                    val newRawState = !_uiState.value.proSettings.isRawAndJpegEnabled
                    _uiState.update {
                        it.copy(proSettings = it.proSettings.copy(isRawAndJpegEnabled = newRawState))
                    }
                    if (lifecycleOwner != null) {
                        rebindCamera(rawAndJpeg = newRawState)
                    }
                }
            }

            is GXVcamEvent.OnTapToFocus -> {
                controller?.triggerTapToFocus(event.x, event.y)
            }

            is GXVcamEvent.OnShutterClicked -> {
                val isRaw = (_uiState.value.currentMode == CameraMode.PRO) && _uiState.value.proSettings.isRawAndJpegEnabled
                controller?.capturePhoto(saveRawAndJpeg = isRaw)
            }

            is GXVcamEvent.OnStartRecording -> {
                controller?.toggleVideoRecording()
            }

            is GXVcamEvent.OnStopRecording -> {
                controller?.toggleVideoRecording()
            }

            is GXVcamEvent.OnRecordingDurationUpdated -> {
                _uiState.update { it.copy(recordingDurationMs = event.durationMs) }
            }

            is GXVcamEvent.UpdateMotoVideoSettings -> {
                _uiState.update { it.copy(motoVideoSettings = event.settings) }
                controller?.applyMotoVideoSettings(
                    settings = event.settings,
                    zoomRatio = _uiState.value.currentZoomRatio,
                    rollDegrees = _uiState.value.levelHorizonDegrees
                )
            }

            is GXVcamEvent.UpdateAgcSettings -> {
                _uiState.update { it.copy(agcSettings = event.settings) }
                controller?.applyAgcSettings(event.settings, _uiState.value.activeLens)
            }

            is GXVcamEvent.SelectAgcProfile -> {
                val p = event.profile
                val newSettings = _uiState.value.agcSettings.copy(
                    activeProfile = p,
                    saturation = p.saturationBoost,
                    contrast = p.contrastCurve,
                    highlightKnee = p.highlightKnee,
                    shadowLift = p.shadowLift,
                    sharpnessLevel = p.acutance,
                    vignetteStrength = p.vignetteStrength,
                    rGainShift = 1.0f,
                    gGainShift = 1.0f,
                    bGainShift = 1.0f
                )
                _uiState.update { it.copy(agcSettings = newSettings) }
                controller?.applyAgcSettings(newSettings, _uiState.value.activeLens)
            }

            is GXVcamEvent.ToggleLiveViewfinderLut -> {
                val newSettings = _uiState.value.agcSettings.copy(isLiveViewfinderLutEnabled = event.enabled)
                _uiState.update { it.copy(agcSettings = newSettings) }
                controller?.updateLiveViewfinderLut(event.enabled, newSettings.activeProfile, _uiState.value.activeLens, newSettings)
            }

            is GXVcamEvent.ResetAgcToDefaults -> {
                val p = _uiState.value.agcSettings.activeProfile
                val defaultSettings = AgcLibPatcherSettings(
                    activeProfile = p,
                    isLiveViewfinderLutEnabled = _uiState.value.agcSettings.isLiveViewfinderLutEnabled,
                    sharpnessLevel = p.acutance,
                    saturation = p.saturationBoost,
                    contrast = p.contrastCurve,
                    highlightKnee = p.highlightKnee,
                    shadowLift = p.shadowLift,
                    vignetteStrength = p.vignetteStrength
                )
                _uiState.update { it.copy(agcSettings = defaultSettings) }
                controller?.applyAgcSettings(defaultSettings, _uiState.value.activeLens)
            }

            is GXVcamEvent.ToggleAgcSheet -> {
                _uiState.update { it.copy(isAgcSheetOpen = !it.isAgcSheetOpen) }
            }

            is GXVcamEvent.OnActiveProControlSelected -> {
                _uiState.update { it.copy(activeProControl = event.control) }
            }

            is GXVcamEvent.OnToggleProPanel -> {
                _uiState.update { it.copy(isProPanelExpanded = !it.isProPanelExpanded) }
            }

            is GXVcamEvent.ToggleSettingsSheet -> {
                _uiState.update { it.copy(isSettingsSheetOpen = !it.isSettingsSheetOpen) }
            }

            is GXVcamEvent.UpdateManualSettings -> {
                _uiState.update { it.copy(manualSettings = event.settings) }
                controller?.applyManualSettings(event.settings)
            }

            is GXVcamEvent.ResetProToAuto -> {
                val auto = ProSettings()
                _uiState.update { it.copy(proSettings = auto) }
                val isVideo = _uiState.value.currentMode == CameraMode.VIDEO
                controller?.applyProSettings(auto, isVideoMode = isVideo)
            }
        }
    }

    private fun handleModeSelected(mode: CameraMode) {
        val previousMode = _uiState.value.currentMode
        if (mode == CameraMode.PORTRAIT) {
            _uiState.update {
                it.copy(
                    currentMode = mode,
                    activeLens = OpticalLens.TELE_3X,
                    currentZoomRatio = OpticalLens.TELE_3X.zoomRatio,
                    isProPanelExpanded = false
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    currentMode = mode,
                    isProPanelExpanded = mode == CameraMode.PRO
                )
            }
        }

        if (previousMode != mode) {
            rebindCamera(mode = mode)
        }
    }

    private fun handleLensSelected(lens: OpticalLens) {
        _uiState.update {
            it.copy(
                activeLens = lens,
                currentZoomRatio = lens.zoomRatio
            )
        }
        controller?.setLens(lens)
    }

    private fun handleZoomChanged(zoom: Float) {
        val clamped = zoom.coerceIn(OpticalLens.MIN_ZOOM, OpticalLens.MAX_ZOOM)
        val targetLens = OpticalLens.forZoom(clamped)
        val previousLens = _uiState.value.activeLens
        _uiState.update {
            it.copy(
                currentZoomRatio = clamped,
                activeLens = targetLens
            )
        }
        if (targetLens != previousLens) {
            controller?.setLens(targetLens, clamped)
        } else {
            controller?.setZoom(clamped)
        }
    }

    private fun handleFlipCamera() {
        val nextFacing = !_uiState.value.isBackCamera
        _uiState.update { it.copy(isBackCamera = nextFacing) }
        rebindCamera(isBackCamera = nextFacing)
    }

    private fun handleCycleFlash() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        _uiState.update { it.copy(flashMode = next) }
        controller?.setFlashMode(next)
    }

    private fun handleCycleAspectRatio() {
        val next = when (_uiState.value.aspectRatio) {
            AspectRatio.RATIO_4_3 -> AspectRatio.RATIO_16_9
            AspectRatio.RATIO_16_9 -> AspectRatio.RATIO_1_1
            AspectRatio.RATIO_1_1 -> AspectRatio.RATIO_3_2
            AspectRatio.RATIO_3_2 -> AspectRatio.RATIO_21_9
            AspectRatio.RATIO_21_9 -> AspectRatio.RATIO_FULL
            AspectRatio.RATIO_FULL -> AspectRatio.RATIO_4_3
        }
        _uiState.update { it.copy(aspectRatio = next) }
        rebindCamera(aspectRatio = next)
    }

    private fun rebindCamera(
        isBackCamera: Boolean = _uiState.value.isBackCamera,
        aspectRatio: AspectRatio = _uiState.value.aspectRatio,
        mode: CameraMode = _uiState.value.currentMode,
        rawAndJpeg: Boolean = _uiState.value.proSettings.isRawAndJpegEnabled,
        activeLens: OpticalLens = _uiState.value.activeLens,
        displayZoom: Float = _uiState.value.currentZoomRatio,
    ) {
        val isVideo = mode == CameraMode.VIDEO
        controller?.bindCamera(
            isBackCamera = isBackCamera,
            aspectRatio = aspectRatio,
            enableHistogram = mode == CameraMode.PRO,
            enableRawJpeg = mode == CameraMode.PRO && rawAndJpeg,
            isVideoMode = isVideo,
            activeLens = activeLens,
            cameraMode = mode,
            displayZoom = displayZoom,
        )
    }
}
