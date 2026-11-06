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
import kotlin.math.roundToInt

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

        // Re-sync state that survives in the ViewModel when a new controller is attached
        // (e.g. after Activity recreation), so captures keep the user's watermark/bokeh choices.
        controller.portraitAperture = _uiState.value.portraitAperture
        controller.applyManualSettings(_uiState.value.manualSettings)

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

            is GXVcamEvent.SetZoomRatio -> {
                _uiState.update { it.copy(isMacroLocked = false) }
                smoothAnimateZoomTo(event.zoom)
            }
            is GXVcamEvent.OnZoomChanged -> {
                zoomAnimationJob?.cancel()
                _uiState.update { it.copy(isMacroLocked = false) }
                handleZoomChanged(event.zoomRatio)
            }
            is GXVcamEvent.SetZoomSliderExpanded -> {
                _uiState.update { it.copy(isZoomSliderExpanded = event.expanded) }
            }

            // Cinema Camera Hardware Volume Zoom Events
            is GXVcamEvent.OnVolumeZoomHold -> {
                startVolumeZoom(event.isZoomIn)
            }
            is GXVcamEvent.OnVolumeZoomStep -> {
                animateToNextZoomStop(event.isZoomIn)
            }
            is GXVcamEvent.OnVolumeZoomReleased -> {
                stopVolumeZoom()
            }

            is GXVcamEvent.ToggleCameraFacing -> handleFlipCamera()
            is GXVcamEvent.OnFlipCamera -> handleFlipCamera()

            is GXVcamEvent.CycleFlashMode -> handleCycleFlash()
            is GXVcamEvent.OnCycleFlash -> handleCycleFlash()

            is GXVcamEvent.CycleAspectRatio -> handleCycleAspectRatio()
            is GXVcamEvent.OnCycleAspectRatio -> handleCycleAspectRatio()

            is GXVcamEvent.SetGridType -> {
                _uiState.update {
                    it.copy(
                        manualSettings = it.manualSettings.copy(gridType = event.gridType),
                        gridEnabled = (event.gridType != GridType.NONE)
                    )
                }
            }
            is GXVcamEvent.ToggleGrid,
            is GXVcamEvent.OnToggleGrid -> {
                val next = when (_uiState.value.manualSettings.gridType) {
                    GridType.NONE -> GridType.RULE_OF_THIRDS
                    GridType.RULE_OF_THIRDS -> GridType.SQUARE
                    GridType.SQUARE -> GridType.DIAG_SQUARE
                    GridType.DIAG_SQUARE -> GridType.GOLDEN_RATIO
                    GridType.GOLDEN_RATIO -> GridType.CROSSHAIR
                    GridType.CROSSHAIR -> GridType.NONE
                }
                _uiState.update {
                    it.copy(
                        manualSettings = it.manualSettings.copy(gridType = next),
                        gridEnabled = (next != GridType.NONE)
                    )
                }
            }

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

            is GXVcamEvent.ToggleRawCapture,
            is GXVcamEvent.CycleCaptureFormat -> {
                val next = when (_uiState.value.manualSettings.captureFormat) {
                    CaptureFormat.JPEG -> CaptureFormat.RAW
                    CaptureFormat.RAW -> CaptureFormat.RAW_AND_JPEG
                    CaptureFormat.RAW_AND_JPEG -> CaptureFormat.JPEG
                }
                _uiState.update {
                    it.copy(
                        manualSettings = it.manualSettings.copy(captureFormat = next, isRawAndJpegEnabled = next.isRaw),
                        proSettings = it.proSettings.copy(captureFormat = next, isRawAndJpegEnabled = next.isRaw)
                    )
                }
                controller?.applyManualSettings(_uiState.value.manualSettings)
            }

            is GXVcamEvent.SetCaptureFormat -> {
                _uiState.update {
                    it.copy(
                        manualSettings = it.manualSettings.copy(captureFormat = event.format, isRawAndJpegEnabled = event.format.isRaw),
                        proSettings = it.proSettings.copy(captureFormat = event.format, isRawAndJpegEnabled = event.format.isRaw)
                    )
                }
                controller?.applyManualSettings(_uiState.value.manualSettings)
            }

            is GXVcamEvent.SetColorSpace -> {
                _uiState.update {
                    it.copy(manualSettings = it.manualSettings.copy(colorSpace = event.colorSpace))
                }
                controller?.applyManualSettings(_uiState.value.manualSettings)
            }

            is GXVcamEvent.SetJpegQuality -> {
                _uiState.update {
                    it.copy(manualSettings = it.manualSettings.copy(jpegQuality = event.quality.coerceIn(50, 100)))
                }
                controller?.applyManualSettings(_uiState.value.manualSettings)
            }

            is GXVcamEvent.OnTapToFocus -> {
                val isAeLocked = _uiState.value.isAeLocked
                val isAfLocked = _uiState.value.isAfLocked
                if (!isAeLocked) {
                    _uiState.update { it.copy(tapFocusEvOffset = 0f) }
                }
                controller?.triggerTapToFocus(event.x, event.y, isAeLocked = isAeLocked, isAfLocked = isAfLocked)
            }

            is GXVcamEvent.SetAeLock -> {
                val newLocked = event.locked
                _uiState.update {
                    it.copy(
                        isAeLocked = newLocked,
                        sonyAeMode = if (newLocked) SonyAeMode.AE_L else if (it.sonyAeMode == SonyAeMode.AE_L) SonyAeMode.AE_AUTO else it.sonyAeMode
                    )
                }
                controller?.setAeLock(newLocked)
                if (!newLocked && !_uiState.value.isAfLocked) {
                    controller?.unlockAe()
                }
            }

            is GXVcamEvent.SetAfLock -> {
                val newLocked = event.locked
                _uiState.update {
                    it.copy(
                        isAfLocked = newLocked,
                        sonyAfMode = if (newLocked) SonyAfMode.AF_L else if (it.sonyAfMode == SonyAfMode.AF_L) SonyAfMode.AF_C else it.sonyAfMode
                    )
                }
                if (newLocked) {
                    controller?.triggerTapToFocus(event.x, event.y, isAeLocked = _uiState.value.isAeLocked, isAfLocked = false)
                    controller?.setAfMode(SonyAfMode.AF_L, locked = true)
                } else {
                    controller?.setAfMode(_uiState.value.sonyAfMode, locked = false)
                    controller?.unlockAf()
                }
            }

            is GXVcamEvent.SetAeAfLock -> {
                val newLocked = event.locked
                _uiState.update {
                    it.copy(
                        isAeLocked = newLocked,
                        isAfLocked = newLocked,
                        sonyAeMode = if (newLocked) SonyAeMode.AE_L else SonyAeMode.AE_AUTO,
                        sonyAfMode = if (newLocked) SonyAfMode.AF_L else SonyAfMode.AF_C
                    )
                }
                if (newLocked) {
                    controller?.triggerTapToFocus(event.x, event.y, isAeLocked = false, isAfLocked = false)
                    controller?.setAeLock(true)
                    controller?.setAfMode(SonyAfMode.AF_L, locked = true)
                } else {
                    controller?.unlockAeAf()
                    controller?.setAfMode(_uiState.value.sonyAfMode, locked = false)
                    controller?.setAeMode(_uiState.value.sonyAeMode, locked = false)
                }
            }

            is GXVcamEvent.SetSonyAfMode -> {
                val isLockMode = event.mode == SonyAfMode.AF_L
                val isMfMode = event.mode == SonyAfMode.MF
                _uiState.update {
                    it.copy(
                        sonyAfMode = event.mode,
                        isAfLocked = isLockMode,
                        proSettings = if (isMfMode) it.proSettings.copy(isManualFocus = true) else it.proSettings
                    )
                }
                controller?.setAfMode(event.mode, locked = isLockMode)
            }

            is GXVcamEvent.SetSonyAeMode -> {
                val isLockMode = event.mode == SonyAeMode.AE_L
                val isManualMode = event.mode == SonyAeMode.AE_M
                _uiState.update {
                    it.copy(
                        sonyAeMode = event.mode,
                        isAeLocked = isLockMode,
                        proSettings = if (isManualMode) it.proSettings.copy(isManualIso = true, isManualShutter = true) else it.proSettings
                    )
                }
                controller?.setAeMode(event.mode, locked = isLockMode)
            }

            is GXVcamEvent.SetTapFocusEv -> {
                val evInt = event.ev.roundToInt()
                _uiState.update { it.copy(tapFocusEvOffset = event.ev, proSettings = it.proSettings.copy(evIndex = evInt)) }
                controller?.setExposureCompensation(evInt)
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

            is GXVcamEvent.OnDeviceOrientationChanged -> {
                controller?.updateDeviceOrientation(event.surfaceRotation, event.cardinalAngle)
            }

            is GXVcamEvent.UpdateMotoVideoSettings -> {
                val prevDual = _uiState.value.motoVideoSettings.isDualCaptureEnabled
                val prevSwap = _uiState.value.motoVideoSettings.isDualCaptureSwapped
                _uiState.update { it.copy(motoVideoSettings = event.settings) }
                controller?.applyMotoVideoSettings(
                    settings = event.settings,
                    zoomRatio = _uiState.value.currentZoomRatio,
                    rollDegrees = _uiState.value.levelHorizonDegrees
                )
                if (event.settings.isDualCaptureEnabled != prevDual || event.settings.isDualCaptureSwapped != prevSwap) {
                    rebindCamera()
                }
            }

            is GXVcamEvent.SetDualCaptureLayout -> {
                val newSettings = _uiState.value.motoVideoSettings.copy(dualCaptureLayout = event.layout)
                _uiState.update { it.copy(motoVideoSettings = newSettings) }
                controller?.applyMotoVideoSettings(
                    settings = newSettings,
                    zoomRatio = _uiState.value.currentZoomRatio,
                    rollDegrees = _uiState.value.levelHorizonDegrees
                )
            }

            is GXVcamEvent.ToggleDualCaptureSwap -> {
                val newSettings = _uiState.value.motoVideoSettings.copy(
                    isDualCaptureSwapped = !_uiState.value.motoVideoSettings.isDualCaptureSwapped
                )
                _uiState.update { it.copy(motoVideoSettings = newSettings) }
                controller?.applyMotoVideoSettings(
                    settings = newSettings,
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

            // Motorola Flagship Features
            is GXVcamEvent.SelectPortraitLens -> {
                val pLens = event.lens
                _uiState.update { it.copy(activePortraitLens = pLens) }
                handleZoomChanged(pLens.zoomRatio)
            }

            is GXVcamEvent.SetPortraitAperture -> {
                _uiState.update { it.copy(portraitAperture = event.aperture) }
                controller?.portraitAperture = event.aperture
            }

            is GXVcamEvent.SetPortraitApertureExpanded -> {
                _uiState.update { it.copy(isPortraitApertureExpanded = event.expanded) }
            }

            is GXVcamEvent.ToggleMacroLock -> {
                val newLock = !_uiState.value.isMacroLocked
                _uiState.update { it.copy(isMacroLocked = newLock) }
                if (newLock) {
                    handleZoomChanged(OpticalLens.UW.zoomRatio)
                } else {
                    handleZoomChanged(1.0f)
                }
            }

            is GXVcamEvent.ToggleAutoNightVision -> {
                _uiState.update { it.copy(isAutoNightVisionActive = !it.isAutoNightVisionActive) }
                // Rebind so PHOTO uses the OEM Night extension (same path as NIGHT mode)
                if (_uiState.value.currentMode == CameraMode.PHOTO) {
                    rebindCamera()
                }
            }
        }
    }

    private fun handleModeSelected(mode: CameraMode) {
        val previousMode = _uiState.value.currentMode
        if (mode == CameraMode.PORTRAIT) {
            val pLens = _uiState.value.activePortraitLens
            val optLens = OpticalLens.forZoom(pLens.zoomRatio)
            _uiState.update {
                it.copy(
                    currentMode = mode,
                    activeLens = optLens,
                    currentZoomRatio = pLens.zoomRatio,
                    isProPanelExpanded = false,
                    isMacroLocked = false,
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    currentMode = mode,
                    isProPanelExpanded = mode == CameraMode.PRO,
                    isMacroLocked = mode == previousMode && it.isMacroLocked,
                )
            }
        }

        if (previousMode != mode) {
            rebindCamera(mode = mode)
        }
    }

    // Cinema-Grade Logarithmic Zoom Engine
    private var volumeZoomJob: Job? = null
    private var volumeKeyDownTime: Long = 0L
    private var volumeZoomIsZoomIn: Boolean = true
    private var zoomAnimationJob: Job? = null

    private fun handleLensSelected(lens: OpticalLens) {
        _uiState.update {
            it.copy(
                isMacroLocked = false
            )
        }
        smoothAnimateZoomTo(lens.zoomRatio)
    }

    private fun handleZoomChanged(zoom: Float) {
        val isBack = _uiState.value.isBackCamera
        val minZoom = if (isBack) OpticalLens.MIN_ZOOM else 1.0f
        val maxZoom = if (isBack) OpticalLens.MAX_ZOOM else 4.0f
        val clamped = zoom.coerceIn(minZoom, maxZoom)
        val targetLens = OpticalLens.forZoom(clamped)
        _uiState.update {
            it.copy(
                currentZoomRatio = clamped,
                activeLens = targetLens
            )
        }
        controller?.setZoom(clamped)
    }

    fun startVolumeZoom(isZoomIn: Boolean) {
        volumeKeyDownTime = System.currentTimeMillis()
        volumeZoomIsZoomIn = isZoomIn
        zoomAnimationJob?.cancel()
        volumeZoomJob?.cancel()
        volumeZoomJob = viewModelScope.launch {
            val isBack = _uiState.value.isBackCamera
            val minZoom = if (isBack) OpticalLens.MIN_ZOOM else 1.0f
            val maxZoom = if (isBack) OpticalLens.MAX_ZOOM else 4.0f
            val startTime = System.currentTimeMillis()
            var currentVelocity = 0.0f
            // Logarithmic speed (d ln(zoom) / dt). 0.72 gives smooth cinema zoom ramping
            val targetVelocity = if (isZoomIn) 0.72f else -0.72f

            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsedHoldMs = now - startTime

                // Ease-in velocity ramp over 180ms
                val rampFactor = (elapsedHoldMs / 180f).coerceIn(0.12f, 1.0f)
                currentVelocity = targetVelocity * rampFactor

                val currentZ = _uiState.value.currentZoomRatio
                val dt = 0.016f // 16ms = ~60 FPS
                val nextZ = (currentZ * kotlin.math.exp(currentVelocity * dt)).coerceIn(
                    minZoom,
                    maxZoom
                )

                handleZoomChanged(nextZ)

                if ((isZoomIn && nextZ >= maxZoom) || (!isZoomIn && nextZ <= minZoom)) {
                    break
                }
                delay(16)
            }
        }
    }

    fun stopVolumeZoom() {
        val holdDuration = System.currentTimeMillis() - volumeKeyDownTime
        volumeZoomJob?.cancel()
        volumeZoomJob = null

        // If tap was quick (< 180ms), step smoothly to next natural cinema stop
        if (holdDuration < 180L) {
            animateToNextZoomStop(volumeZoomIsZoomIn)
        }
    }

    fun animateToNextZoomStop(isZoomIn: Boolean) {
        val isBack = _uiState.value.isBackCamera
        val stops = if (isBack) {
            listOf(0.6f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f, 5.0f, 10.0f, 20.0f)
        } else {
            listOf(1.0f, 1.4f)
        }
        val current = _uiState.value.currentZoomRatio
        val target = if (isZoomIn) {
            stops.firstOrNull { it > current + 0.05f } ?: (if (isBack) OpticalLens.MAX_ZOOM else 1.4f)
        } else {
            stops.lastOrNull { it < current - 0.05f } ?: 1.0f
        }
        smoothAnimateZoomTo(target)
    }

    fun smoothAnimateZoomTo(targetZoom: Float, durationMs: Long = 240L) {
        zoomAnimationJob?.cancel()
        volumeZoomJob?.cancel()
        zoomAnimationJob = viewModelScope.launch {
            val isBack = _uiState.value.isBackCamera
            val minZoom = if (isBack) OpticalLens.MIN_ZOOM else 1.0f
            val maxZoom = if (isBack) OpticalLens.MAX_ZOOM else 4.0f
            val startZoom = _uiState.value.currentZoomRatio
            val clampedTarget = targetZoom.coerceIn(minZoom, maxZoom)
            if (kotlin.math.abs(startZoom - clampedTarget) < 0.01f) {
                handleZoomChanged(clampedTarget)
                return@launch
            }
            val startTime = System.currentTimeMillis()

            // Perceptual logarithmic space interpolation for cinema-smooth optical feel
            val startLog = kotlin.math.ln(startZoom.toDouble())
            val targetLog = kotlin.math.ln(clampedTarget.toDouble())

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                // Smooth cubic ease-in-out curve
                val t = progress
                val easeProgress = if (t < 0.5f) {
                    4f * t * t * t
                } else {
                    1f - (-2f * t + 2f).let { it * it * it } / 2f
                }

                val currentLog = startLog + (targetLog - startLog) * easeProgress
                val z = kotlin.math.exp(currentLog).toFloat()

                handleZoomChanged(z)

                if (progress >= 1f) break
                delay(16)
            }
        }
    }

    private fun handleFlipCamera() {
        val nextFacing = !_uiState.value.isBackCamera
        zoomAnimationJob?.cancel()
        volumeZoomJob?.cancel()
        _uiState.update {
            it.copy(
                isBackCamera = nextFacing,
                currentZoomRatio = 1.0f,
                activeLens = OpticalLens.MAIN_1X,
                isZoomSliderExpanded = false,
                isMacroLocked = false
            )
        }
        rebindCamera(
            isBackCamera = nextFacing,
            activeLens = OpticalLens.MAIN_1X,
            displayZoom = 1.0f
        )
        controller?.setZoom(1.0f)
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
            AspectRatio.RATIO_21_9 -> AspectRatio.RATIO_4_3
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
        // PHOTO + Night Vision pill binds through the same OEM Night extension as NIGHT mode
        val bindMode = if (mode == CameraMode.PHOTO && _uiState.value.isAutoNightVisionActive) {
            CameraMode.NIGHT
        } else {
            mode
        }
        controller?.bindCamera(
            isBackCamera = isBackCamera,
            aspectRatio = aspectRatio,
            enableHistogram = mode == CameraMode.PRO,
            enableRawJpeg = mode == CameraMode.PRO && rawAndJpeg,
            isVideoMode = isVideo,
            activeLens = activeLens,
            cameraMode = bindMode,
            displayZoom = displayZoom,
            isDualCapture = (isVideo || mode == CameraMode.PHOTO) && _uiState.value.motoVideoSettings.isDualCaptureEnabled,
            isDualCaptureSwapped = _uiState.value.motoVideoSettings.isDualCaptureSwapped,
        )
    }
}
