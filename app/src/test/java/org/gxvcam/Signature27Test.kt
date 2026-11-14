package org.gxvcam

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.os.PowerManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.gxvcam.ai.Apu655HardwareAccelerator
import org.gxvcam.ai.NpuExecutionTelemetry
import org.gxvcam.ai.VirtualEvsTelemetrySimulator
import org.gxvcam.camera.CieUvAwbGuard
import org.gxvcam.camera.MotoAiFallbackController
import org.gxvcam.camera.MotoAiSceneMode
import org.gxvcam.camera.PantoneLutGenerator
import org.gxvcam.camera.PipelineTelemetry
import org.gxvcam.camera.ViennaLensRouter
import org.gxvcam.camera.ZslRawRingBuffer
import org.gxvcam.data.GXVcamPreferencesRepository
import org.gxvcam.sensors.GyroMotionTracker
import org.gxvcam.sensors.HorizonSensorManager
import org.gxvcam.ui.components.ROTARY_ZOOM_DETENTS
import org.gxvcam.ui.components.computeRotaryZoomStep
import org.gxvcam.ui.state.AccentTheme
import org.gxvcam.ui.state.AgcColorProfile
import org.gxvcam.ui.state.AiProcessingBackend
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.state.MotoPortraitLens
import org.gxvcam.ui.state.NpuPowerProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI

@OptIn(ExperimentalCoroutinesApi::class)
class Signature27Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testZslRawRingBuffer_evictsOldestAndLocksBurstPair() {
        val ring = ZslRawRingBuffer(maxSlots = 14)

        // Offer 14 EV0 frames followed by 2 EV-2.5 frames (total 16 -> 2 oldest evicted)
        repeat(14) { i ->
            val buf = ByteBuffer.allocateDirect(64)
            ring.offerDirectSlot(
                buf,
                ZslRawRingBuffer.RawFrameMetadata(
                    timestampNs = 1_000_000L * (i + 1),
                    width = 4096,
                    height = 3072,
                    rowStrideBytes = 8192,
                    exposureNs = 8_000_000L,
                    iso = 100,
                    whiteLevel = 1023f,
                    blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
                    cfaPattern = 3,
                    evOffset = 0f
                )
            )
        }
        repeat(2) { i ->
            val buf = ByteBuffer.allocateDirect(64)
            ring.offerDirectSlot(
                buf,
                ZslRawRingBuffer.RawFrameMetadata(
                    timestampNs = 20_000_000L + i,
                    width = 4096,
                    height = 3072,
                    rowStrideBytes = 8192,
                    exposureNs = 1_500_000L,
                    iso = 100,
                    whiteLevel = 1023f,
                    blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
                    cfaPattern = 3,
                    evOffset = -2.5f
                )
            )
        }

        assertEquals(14, ring.slotCount)
        assertEquals(2, ring.totalDropped)

        val locked = ring.lockBurstPair(ev0Count = 8, evMinusCount = 2)
        assertEquals(8, locked.ev0Frames.size)
        assertEquals(2, locked.evMinusFrames.size)
        assertEquals(10, locked.totalFrames)

        val bracket = ring.interleaveActionFrames(shortShutterNs = 50_000L)
        assertEquals(100_000L, bracket.shortShutterNs)
        assertEquals(-2.5f, bracket.evMinusTarget, 0.001f)

        ring.close()
        assertFalse(
            ring.offerDirectSlot(
                ByteBuffer.allocateDirect(8),
                locked.ev0Frames.first().meta
            )
        )
    }

    @Test
    fun testViennaLensRouter_selectRawResolution_enforcesMemoryBudget() {
        val main1x = ViennaLensRouter.selectRawResolution(
            role = ViennaLensRouter.LensRole.MAIN,
            zoomRatio = 1.0f,
            isUltraRes50M = false
        )
        assertEquals(4096, main1x.width)
        assertEquals(3072, main1x.height)
        assertEquals(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT, main1x.pixelMode)

        val main2x = ViennaLensRouter.selectRawResolution(
            role = ViennaLensRouter.LensRole.MAIN,
            zoomRatio = 2.0f,
            isUltraRes50M = false
        )
        assertEquals(8192, main2x.width)
        assertEquals(6144, main2x.height)
        assertEquals(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, main2x.pixelMode)

        val frontStd = ViennaLensRouter.selectRawResolution(
            role = ViennaLensRouter.LensRole.FRONT,
            zoomRatio = 1.0f,
            isUltraRes50M = false,
            isFrontHighRes32Mp = false
        )
        assertEquals(3280, frontStd.width)
        assertEquals(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT, frontStd.pixelMode)

        val front32Mp = ViennaLensRouter.selectRawResolution(
            role = ViennaLensRouter.LensRole.FRONT,
            zoomRatio = 1.0f,
            isUltraRes50M = false,
            isFrontHighRes32Mp = true
        )
        assertEquals(6560, front32Mp.width)
        assertEquals(4920, front32Mp.height)
        assertEquals(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, front32Mp.pixelMode)
    }

    @Test
    fun testRotateToZoom_computeRotaryZoomStep_increasesDecreasesAndClamps() {
        // First touch initializes angle without changing zoom
        val (z0, angle0) = computeRotaryZoomStep(
            touchX = 100f,
            touchY = 0f,
            centerX = 0f,
            centerY = 0f,
            previousAngleRad = null,
            currentZoom = 1.0f
        )
        assertEquals(1.0f, z0, 0.001f)
        assertEquals(0f, angle0, 0.001f)

        // Clockwise step (+PI/2 rad) should increase zoom cleanly
        val (zUp, angle1) = computeRotaryZoomStep(
            touchX = 0f,
            touchY = 100f,
            centerX = 0f,
            centerY = 0f,
            previousAngleRad = angle0,
            currentZoom = z0
        )
        assertTrue("Expected zoom > 1.0 after positive rotation, got $zUp", zUp > 1.5f)
        assertEquals((PI / 2.0).toFloat(), angle1, 0.01f)

        // Counter-clockwise step back toward MIN_ZOOM clamps at 0.6f
        val (zMin, _) = computeRotaryZoomStep(
            touchX = 0f,
            touchY = -100f,
            centerX = 0f,
            centerY = 0f,
            previousAngleRad = 0f,
            currentZoom = 0.65f
        )
        assertEquals(0.6f, zMin, 0.001f)
        assertTrue(ROTARY_ZOOM_DETENTS.contains(1.5f))
        assertTrue(ROTARY_ZOOM_DETENTS.contains(6.0f))
    }

    @Test
    fun testViewModel_signature27SettingsAndAutoGroupSelfie() = runTest(testDispatcher) {
        val vm = GXVcamViewModel()

        val updated = vm.uiState.value.manualSettings.copy(
            isRotaryZoomDialEnabled = true,
            isAutoGroupSelfieEnabled = true,
            isFrontHighRes32MpEnabled = true,
            isSignature27PipelineEnabled = false
        )
        vm.onEvent(GXVcamEvent.UpdateManualSettings(updated))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.manualSettings.isRotaryZoomDialEnabled)
        assertTrue(vm.uiState.value.manualSettings.isAutoGroupSelfieEnabled)
        assertFalse(vm.uiState.value.manualSettings.isSignature27PipelineEnabled)

        // Flip to front camera and set 1.4x portrait crop
        vm.onEvent(GXVcamEvent.OnFlipCamera)
        vm.onEvent(GXVcamEvent.SetZoomRatio(1.4f))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isBackCamera)
        assertEquals(1.4f, vm.uiState.value.currentZoomRatio, 0.02f)

        // Group selfie trigger (2 faces detected) must smoothly widen back to 1.0x
        vm.onSelfieFacesDetected(faceCount = 2)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1.0f, vm.uiState.value.currentZoomRatio, 0.02f)
    }

    @Test
    fun testPreferencesRepository_persistsFrontHighResAndAutoGroupSelfie() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "gxv_test.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { file }
        )
        val repo = GXVcamPreferencesRepository(dataStore)

        val custom = org.gxvcam.ui.state.CameraManualSettings(
            isFrontHighRes32MpEnabled = true,
            isAutoGroupSelfieEnabled = true,
            isRotaryZoomDialEnabled = true,
            jpegQuality = 95
        )
        repo.saveManualSettings(custom)
        repo.saveColorProfile(AgcColorProfile.LEICA_VIBRANT)

        val snapshot = repo.preferencesFlow.first()
        assertTrue(snapshot.manualSettings.isFrontHighRes32MpEnabled)
        assertTrue(snapshot.manualSettings.isAutoGroupSelfieEnabled)
        assertTrue(snapshot.manualSettings.isRotaryZoomDialEnabled)
        assertEquals(95, snapshot.manualSettings.jpegQuality)
        assertEquals(AgcColorProfile.LEICA_VIBRANT, snapshot.colorProfile)
    }

    @Test
    fun testGyroMotionTracker_syntheticRingBufferWindow() {
        // Smoke-check GyroMotionTracker synthetic recording on a headless test would need Context;
        // verify Sample data class fields remain immutable and consistent.
        val sample = GyroMotionTracker.Sample(timestampNs = 10_000_000L, wx = 0.05f, wy = -0.02f, wz = 0.01f)
        assertEquals(10_000_000L, sample.timestampNs)
        assertEquals(0.05f, sample.wx, 1e-6f)
    }

    @Test
    fun testCieUvAwbGuard_clampsExtremeMagentaDriftAndPassesD65() {
        val neutral = CieUvAwbGuard.guardGains(1.60f, 1.0f, 1.0f, 1.50f, hasFace = false)
        assertFalse("D65-like gains should not clamp, deltaUv=${neutral.deltaUv}", neutral.wasClamped)

        // Heavy magenta/purple stage LED push (very high R+B vs G gains -> low illuminant G)
        val magenta = CieUvAwbGuard.guardGains(3.2f, 1.0f, 1.0f, 3.1f, hasFace = true)
        assertTrue("Magenta drift should exceed 0.038 and clamp, deltaUv=${magenta.deltaUv}", magenta.wasClamped)
        assertTrue(magenta.deltaUv > CieUvAwbGuard.DELTA_UV_THRESHOLD)
        assertTrue(magenta.rGain < 3.2f && magenta.bGain < 3.1f)
    }

    @Test
    fun testFixedFocusRoleAndNordThemeAndUltraHdrPrefs() = runTest(testDispatcher) {
        assertTrue(ViennaLensRouter.isFixedFocusRole(ViennaLensRouter.LensRole.FRONT, 5.0f))
        assertTrue(ViennaLensRouter.isFixedFocusRole(ViennaLensRouter.LensRole.ULTRA_WIDE, 0.0f))
        assertFalse(ViennaLensRouter.isFixedFocusRole(ViennaLensRouter.LensRole.MAIN, 10.0f))

        assertEquals(0xFF88C0D0, AccentTheme.NORD_FROST.colorHex)
        assertEquals(0xFF3B4252, AccentTheme.NORD_FROST.containerHex)

        val file = File(tempFolder.root, "gxv_hdr_test.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = this, produceFile = { file })
        val repo = GXVcamPreferencesRepository(dataStore)
        repo.saveManualSettings(org.gxvcam.ui.state.CameraManualSettings(isUltraHdrGainmapEnabled = false))
        assertFalse(repo.preferencesFlow.first().manualSettings.isUltraHdrGainmapEnabled)
    }

    @Test
    fun testHorizonSensorManager_portraitLandscapeHysteresisAndPlanar() {
        val portrait = HorizonSensorManager.computeHorizonSample(x = 0f, y = 9.81f, z = 0f)
        assertFalse(portrait.isPlanar)
        assertEquals(0, portrait.cardinalAngle)
        assertEquals(0f, portrait.relativeRollDegrees, 0.05f)

        val landscape90 = HorizonSensorManager.computeHorizonSample(x = -9.81f, y = 0f, z = 0f)
        assertFalse(landscape90.isPlanar)
        assertEquals(90, landscape90.cardinalAngle)
        assertEquals(0f, landscape90.relativeRollDegrees, 0.05f)

        val landscape270 = HorizonSensorManager.computeHorizonSample(x = 9.81f, y = 0f, z = 0f)
        assertFalse(landscape270.isPlanar)
        assertEquals(270, landscape270.cardinalAngle)
        assertEquals(0f, landscape270.relativeRollDegrees, 0.05f)

        // Tilted 10° off horizontal while in 270° landscape
        val rad10 = Math.toRadians(10.0)
        val tilted270 = HorizonSensorManager.computeHorizonSample(
            x = (9.81 * kotlin.math.cos(rad10)).toFloat(),
            y = (9.81 * kotlin.math.sin(rad10)).toFloat(),
            z = 0f,
            previousCardinal = 270
        )
        assertEquals(270, tilted270.cardinalAngle)
        assertEquals(10f, tilted270.relativeRollDegrees, 0.2f)

        val upsideDown = HorizonSensorManager.computeHorizonSample(x = 0f, y = -9.81f, z = 0f)
        assertEquals(180, upsideDown.cardinalAngle)
        assertEquals(0f, upsideDown.relativeRollDegrees, 0.05f)

        // ±5° hysteresis prevents rapid flicker at 47° when previously in 0° portrait
        val rad47 = Math.toRadians(47.0)
        val hystKeep0 = HorizonSensorManager.computeHorizonSample(
            x = (-9.81 * kotlin.math.sin(rad47)).toFloat(),
            y = (9.81 * kotlin.math.cos(rad47)).toFloat(),
            z = 0f,
            previousCardinal = 0
        )
        assertEquals(0, hystKeep0.cardinalAngle)

        // Phone facing flat/planar (4 * xy^2 < z^2)
        val planar = HorizonSensorManager.computeHorizonSample(x = 0.2f, y = 0.3f, z = 9.81f, previousCardinal = 90)
        assertTrue(planar.isPlanar)
        assertEquals(90, planar.cardinalAngle)
        assertEquals(0f, planar.relativeRollDegrees, 0.01f)
    }

    @Test
    fun testMotoPortraitLens73mm_pantoneLut_andViewModelHorizonEvents() = runTest(testDispatcher) {
        assertEquals(5, MotoPortraitLens.entries.size)
        assertEquals(73, MotoPortraitLens.TELE_73MM.focalMm)
        assertEquals(3.0f, MotoPortraitLens.TELE_73MM.zoomRatio, 0.001f)

        val lutFile = File(tempFolder.root, PantoneLutGenerator.LUT_FILENAME)
        PantoneLutGenerator.generateToFile(lutFile)
        assertTrue(lutFile.exists())
        assertEquals(PantoneLutGenerator.EXPECTED_BYTE_SIZE.toLong(), lutFile.length())

        // Neutral gray diagonal at index (16, 16, 16) -> 0.5f stays exact identity
        val lut = PantoneLutGenerator.build33x33x33Lut()
        val midIdx = ((16 * 33 + 16) * 33 + 16) * 3
        assertEquals(0.5f, lut[midIdx], 1e-5f)
        assertEquals(0.5f, lut[midIdx + 1], 1e-5f)
        assertEquals(0.5f, lut[midIdx + 2], 1e-5f)

        val vm = GXVcamViewModel()
        vm.onEvent(GXVcamEvent.OnDeviceOrientationChanged(android.view.Surface.ROTATION_90, 270))
        vm.onEvent(GXVcamEvent.UpdateHorizonPlanar(true))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(270, vm.uiState.value.deviceCardinalAngle)
        assertTrue(vm.uiState.value.isHorizonPlanar)

        // Verify OnSelfieFaceCountUpdated routes cleanly to auto group selfie widen
        vm.onEvent(GXVcamEvent.UpdateManualSettings(vm.uiState.value.manualSettings.copy(isAutoGroupSelfieEnabled = true)))
        vm.onEvent(GXVcamEvent.OnFlipCamera)
        vm.onEvent(GXVcamEvent.SetZoomRatio(1.4f))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1.4f, vm.uiState.value.currentZoomRatio, 0.02f)

        vm.onEvent(GXVcamEvent.OnSelfieFaceCountUpdated(2))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1.0f, vm.uiState.value.currentZoomRatio, 0.02f)
    }

    @Test
    fun testSprint7_apu655FallbackThresholds_andMotoAiSceneMapping() {
        // GXV_HYBRID_AI never falls back regardless of battery/thermals
        assertFalse(
            Apu655HardwareAccelerator.evaluateFallbackDecision(
                backend = AiProcessingBackend.GXV_HYBRID_AI,
                batteryPercent = 5,
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE
            )
        )

        // MOTO_AI_FALLBACK always routes to OEM Moto AI ISP
        assertTrue(
            Apu655HardwareAccelerator.evaluateFallbackDecision(
                backend = AiProcessingBackend.MOTO_AI_FALLBACK,
                batteryPercent = 100,
                thermalStatus = PowerManager.THERMAL_STATUS_NONE
            )
        )

        // AUTO_BATTERY_FALLBACK: active when battery <= 20% or thermal >= MODERATE
        assertFalse(
            Apu655HardwareAccelerator.evaluateFallbackDecision(
                backend = AiProcessingBackend.AUTO_BATTERY_FALLBACK,
                batteryPercent = 21,
                thermalStatus = PowerManager.THERMAL_STATUS_LIGHT
            )
        )
        assertTrue(
            Apu655HardwareAccelerator.evaluateFallbackDecision(
                backend = AiProcessingBackend.AUTO_BATTERY_FALLBACK,
                batteryPercent = 20,
                thermalStatus = PowerManager.THERMAL_STATUS_NONE
            )
        )
        assertTrue(
            Apu655HardwareAccelerator.evaluateFallbackDecision(
                backend = AiProcessingBackend.AUTO_BATTERY_FALLBACK,
                batteryPercent = 85,
                thermalStatus = PowerManager.THERMAL_STATUS_MODERATE
            )
        )

        // MotoAiFallbackController camera mode -> extension scene mode mapping
        assertEquals(MotoAiSceneMode.NIGHT, MotoAiFallbackController.sceneModeFor(CameraMode.NIGHT))
        assertEquals(MotoAiSceneMode.BOKEH, MotoAiFallbackController.sceneModeFor(CameraMode.PORTRAIT))
        assertEquals(MotoAiSceneMode.HDR, MotoAiFallbackController.sceneModeFor(CameraMode.PHOTO))
        assertEquals(MotoAiSceneMode.AUTO, MotoAiFallbackController.sceneModeFor(CameraMode.PRO))
        assertEquals(MotoAiSceneMode.AUTO, MotoAiFallbackController.sceneModeFor(CameraMode.VIDEO))
    }

    @Test
    fun testSprint7_aiPreferencesPersistence_andViewModelSheetTelemetry() = runTest(testDispatcher) {
        val dataStoreFile = File(tempFolder.root, "sprint7_prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { dataStoreFile }
        val repo = GXVcamPreferencesRepository(dataStore)

        val custom = CameraManualSettings(
            aiBackend = AiProcessingBackend.AUTO_BATTERY_FALLBACK,
            npuPowerProfile = NpuPowerProfile.APU655_SUSTAINED_SPEED,
            useInternalNpuOnly = false
        )
        repo.saveManualSettings(custom)

        val loaded = repo.preferencesFlow.first()
        assertEquals(AiProcessingBackend.AUTO_BATTERY_FALLBACK, loaded.manualSettings.aiBackend)
        assertEquals(NpuPowerProfile.APU655_SUSTAINED_SPEED, loaded.manualSettings.npuPowerProfile)
        assertFalse(loaded.manualSettings.useInternalNpuOnly)

        val vm = GXVcamViewModel()
        assertFalse(vm.uiState.value.isAiSheetOpen)
        vm.onEvent(GXVcamEvent.ToggleAiSettingsSheet)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isAiSheetOpen)

        val sampleTelemetry = NpuExecutionTelemetry(
            activeAcceleratorName = "MediaTek APU 655 NPU (Sustained Turbo)",
            inferenceLatencyMs = 14L,
            isMotoAiFallbackActive = false,
            shadowLift = 0.16f,
            detailGain = 1.12f,
            dehazeVeil = 0.05f,
            lutWeights = floatArrayOf(0.70f, 0.10f, 0.20f, 0.0f),
            batteryPercent = 82,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE
        )
        PipelineTelemetry.recordNpuTelemetry(sampleTelemetry)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(sampleTelemetry, vm.uiState.value.npuTelemetry)
        assertTrue(vm.uiState.value.pipelineMetrics.activePipelineName.contains("APU 655"))
    }

    @Test
    fun testSprint71_virtualEvsAdaptiveThreshold_flickerRejection_andEventPolarity() {
        // Low light raises contrast threshold to suppress sensor shot noise
        val darkThr = VirtualEvsTelemetrySimulator.computeAdaptiveContrastThreshold(
            lux = 5f,
            flickerMode = CaptureResult.STATISTICS_SCENE_FLICKER_NONE
        )
        val daylightThr = VirtualEvsTelemetrySimulator.computeAdaptiveContrastThreshold(
            lux = 500f,
            flickerMode = CaptureResult.STATISTICS_SCENE_FLICKER_NONE
        )
        val flicker50Thr = VirtualEvsTelemetrySimulator.computeAdaptiveContrastThreshold(
            lux = 500f,
            flickerMode = CaptureResult.STATISTICS_SCENE_FLICKER_50HZ
        )
        assertTrue(darkThr > daylightThr)
        assertTrue(flicker50Thr > daylightThr)

        // Analytical vEVS event synthesis: moving bright block triggers +127 ON, dark block triggers -127 OFF,
        // static background stays 0 even under minor global flicker perturbation.
        val w = VirtualEvsTelemetrySimulator.MASK_WIDTH
        val h = VirtualEvsTelemetrySimulator.MASK_HEIGHT
        val prev = ByteArray(w * h) { 100.toByte() }
        val curr = ByteArray(w * h) { 100.toByte() }

        // Inject +ON motion patch and -OFF motion patch
        for (y in 40 until 60) {
            for (x in 40 until 60) {
                curr[y * w + x] = 220.toByte()
            }
            for (x in 120 until 140) {
                curr[y * w + x] = 30.toByte()
            }
        }

        val outMask = ByteBuffer.allocateDirect(w * h)
        val activeCount = VirtualEvsTelemetrySimulator.synthesizeAnalyticalEvents(
            prevLuma256x192 = prev,
            currLuma256x192 = curr,
            shiftX = 0,
            shiftY = 0,
            rollingShutterSkewNs = 14_200_000L,
            flickerMode = CaptureResult.STATISTICS_SCENE_FLICKER_NONE,
            threshold = daylightThr,
            outMask = outMask
        )
        assertEquals(800, activeCount)
        assertEquals(127.toByte(), outMask.get(50 * w + 50))
        assertEquals((-127).toByte(), outMask.get(50 * w + 130))
        assertEquals(0.toByte(), outMask.get(10 * w + 10))
    }

    @Test
    fun testSprint71_virtualEvsDisabled_andAspectRatioAndSignatureLutDefaults() = runTest(testDispatcher) {
        val dataStoreFile = File(tempFolder.root, "sprint71_vevs_prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { dataStoreFile }
        val repo = GXVcamPreferencesRepository(dataStore)

        // Even if legacy preferences had vEVS/Signature27 enabled, repository forces them off
        repo.saveManualSettings(
            CameraManualSettings(
                isSignature27PipelineEnabled = true,
                isSyntheticEvsDeblurEnabled = true,
                isVirtualEvsOverlayVisible = true
            )
        )
        val loaded = repo.preferencesFlow.first()
        assertFalse(loaded.manualSettings.isSignature27PipelineEnabled)
        assertFalse(loaded.manualSettings.isSyntheticEvsDeblurEnabled)
        assertFalse(loaded.manualSettings.isVirtualEvsOverlayVisible)

        val vm = GXVcamViewModel()
        assertFalse(vm.uiState.value.hasPhysicalEvsHardware)
        assertFalse(vm.uiState.value.manualSettings.isSyntheticEvsDeblurEnabled)
        assertFalse(vm.uiState.value.manualSettings.isSignature27PipelineEnabled)

        // Default aspect ratios: Photo = 21:9, Video = 16:9, preserving per-mode choice
        assertEquals(org.gxvcam.ui.state.AspectRatio.RATIO_21_9, vm.uiState.value.aspectRatio)
        vm.onEvent(GXVcamEvent.SelectMode(org.gxvcam.ui.state.CameraMode.VIDEO))
        assertEquals(org.gxvcam.ui.state.AspectRatio.RATIO_16_9, vm.uiState.value.aspectRatio)
        vm.onEvent(GXVcamEvent.OnCycleAspectRatio) // Video 16:9 -> 4:3
        assertEquals(org.gxvcam.ui.state.AspectRatio.RATIO_4_3, vm.uiState.value.aspectRatio)
        vm.onEvent(GXVcamEvent.SelectMode(org.gxvcam.ui.state.CameraMode.PHOTO))
        assertEquals(org.gxvcam.ui.state.AspectRatio.RATIO_21_9, vm.uiState.value.aspectRatio)
        vm.onEvent(GXVcamEvent.SelectMode(org.gxvcam.ui.state.CameraMode.VIDEO))
        assertEquals(org.gxvcam.ui.state.AspectRatio.RATIO_4_3, vm.uiState.value.aspectRatio)

        // Signature LUT exists in both Photo and Pro Video LUT enums
        assertEquals("Signature", AgcColorProfile.SIGNATURE_LUT.label)
        assertEquals("Signature", org.gxvcam.ui.state.ProVideoLutStyle.SIGNATURE_LUT.label)
    }
}
