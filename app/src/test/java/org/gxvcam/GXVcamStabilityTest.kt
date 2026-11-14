package org.gxvcam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.gxvcam.camera.MotoVideoEngine
import org.gxvcam.camera.MtkMotoVendorTags
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.OpticalLens
import org.gxvcam.ui.state.VideoFrameRate
import org.gxvcam.ui.state.VideoResolution
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic Stability and Stress Test Suite for GXVcam.
 * Validates rapid lens switching, burst shutter triggers, concurrency safety,
 * HAL vendor tag keys, video bitrate/FPS scaling, and default quality settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GXVcamStabilityTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: GXVcamViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = GXVcamViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRapidLensSwitches_maintainsStateConsistency() = runTest(testDispatcher) {
        val lenses = listOf(
            OpticalLens.UW,
            OpticalLens.MAIN_1X,
            OpticalLens.CROP_2X,
            OpticalLens.TELE_3X
        )

        // Simulate 50 rapid lens switches without controller attachment
        repeat(50) { index ->
            val targetLens = lenses[index % lenses.size]
            viewModel.onEvent(GXVcamEvent.OnLensSelected(targetLens))
            testDispatcher.scheduler.advanceTimeBy(16)

            val currentState = viewModel.uiState.value
            assertNotNull("UI state must never be null", currentState)
            assertTrue(
                "Zoom ratio must remain within valid hardware limits [0.6x..20.0x]",
                currentState.currentZoomRatio in 0.6f..20.0f
            )
        }

        testDispatcher.scheduler.advanceUntilIdle()
        val finalState = viewModel.uiState.value
        assertNotNull(finalState.activeLens)
        assertTrue(finalState.currentZoomRatio in 0.6f..20.0f)
    }

    @Test
    fun testRapidShutterTriggers_doesNotDeadlockOrThrow() = runTest(testDispatcher) {
        // Switch to Photo Mode
        viewModel.onEvent(GXVcamEvent.OnModeSelected(CameraMode.PHOTO))
        testDispatcher.scheduler.advanceTimeBy(50)

        // Simulate 20 rapid burst shutter presses
        repeat(20) {
            viewModel.onEvent(GXVcamEvent.OnShutterClicked)
            testDispatcher.scheduler.advanceTimeBy(10)
        }

        testDispatcher.scheduler.advanceUntilIdle()
        val currentState = viewModel.uiState.value
        assertEquals("Mode must remain PHOTO", CameraMode.PHOTO, currentState.currentMode)
    }

    @Test
    fun testRapidCameraFlipsAndZoomPills_maintainsCorrectOptics() = runTest(testDispatcher) {
        // Initial state is back camera
        assertTrue("Initial camera must be back camera", viewModel.uiState.value.isBackCamera)
        assertEquals("Initial zoom must be 1.0x", 1.0f, viewModel.uiState.value.currentZoomRatio)

        // 1. Flip to front camera
        viewModel.onEvent(GXVcamEvent.OnFlipCamera)
        testDispatcher.scheduler.advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue("Camera must be front camera", !state.isBackCamera)
        assertEquals("Front camera must default to 1.0x", 1.0f, state.currentZoomRatio)

        // 2. Select 1.4x portrait mode on front camera
        viewModel.onEvent(GXVcamEvent.SetZoomRatio(1.4f))
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals("Front camera zoom must be 1.4x", 1.4f, state.currentZoomRatio)

        // 3. Flip back to rear camera
        viewModel.onEvent(GXVcamEvent.OnFlipCamera)
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.uiState.value
        assertTrue("Camera must be back camera", state.isBackCamera)
        assertEquals("Rear camera must reset to 1.0x", 1.0f, state.currentZoomRatio)
        assertEquals("Rear camera active lens must be MAIN_1X", OpticalLens.MAIN_1X, state.activeLens)
    }

    @Test
    fun testStressLifecycle_50LensSwitchesAnd20ShutterEvents_interleaved() = runTest(testDispatcher) {
        val lenses = listOf(
            OpticalLens.UW,
            OpticalLens.MAIN_1X,
            OpticalLens.CROP_2X,
            OpticalLens.TELE_3X
        )

        repeat(50) { i ->
            viewModel.onEvent(GXVcamEvent.OnLensSelected(lenses[i % lenses.size]))
            if (i % 2 == 0 && i < 40) {
                viewModel.onEvent(GXVcamEvent.OnShutterClicked)
            }
            if (i % 5 == 0) {
                viewModel.onEvent(GXVcamEvent.OnVolumeZoomStep(isZoomIn = (i % 10 == 0)))
            }
            testDispatcher.scheduler.advanceTimeBy(16)
        }

        testDispatcher.scheduler.advanceUntilIdle()
        val finalState = viewModel.uiState.value
        assertNotNull(finalState)
        assertTrue(finalState.currentZoomRatio in 0.6f..20.0f)
    }

    @Test
    fun testMotoVideoEngine_computesTargetFpsAndBitRate() {
        val uhdSettings = MotoVideoSettings(
            resolution = VideoResolution.UHD_4K,
            frameRate = VideoFrameRate.FPS_60
        )
        assertEquals(30, MotoVideoEngine.computeTargetFps(uhdSettings))
        assertEquals(65_000_000, MotoVideoEngine.computeTargetBitRate(uhdSettings))

        val fhd30 = MotoVideoSettings(
            resolution = VideoResolution.FHD_1080P,
            frameRate = VideoFrameRate.FPS_30
        )
        assertEquals(30, MotoVideoEngine.computeTargetFps(fhd30))
        assertEquals(24_000_000, MotoVideoEngine.computeTargetBitRate(fhd30))

        val fhd60 = MotoVideoSettings(
            resolution = VideoResolution.FHD_1080P,
            frameRate = VideoFrameRate.FPS_60
        )
        assertEquals(60, MotoVideoEngine.computeTargetFps(fhd60))
        assertEquals(35_000_000, MotoVideoEngine.computeTargetBitRate(fhd60))

        val hd30 = MotoVideoSettings(
            resolution = VideoResolution.HD_720P,
            frameRate = VideoFrameRate.FPS_30
        )
        assertEquals(12_000_000, MotoVideoEngine.computeTargetBitRate(hd30))

        val hd60 = MotoVideoSettings(
            resolution = VideoResolution.HD_720P,
            frameRate = VideoFrameRate.FPS_60
        )
        assertEquals(18_000_000, MotoVideoEngine.computeTargetBitRate(hd60))

        val dual4k = MotoVideoSettings(
            resolution = VideoResolution.UHD_4K,
            frameRate = VideoFrameRate.FPS_60,
            isDualCaptureEnabled = true
        )
        assertEquals(30, MotoVideoEngine.computeTargetFps(dual4k))
        assertEquals(24_000_000, MotoVideoEngine.computeTargetBitRate(dual4k))
    }

    @Test
    fun testMtkMotoVendorTags_allKeysInstantiateNonNull() {
        assertNotNull(MtkMotoVendorTags.MTK_ZSL_MODE)
        assertNotNull(MtkMotoVendorTags.MOTO_IN_SENSOR_CROP_ENABLE)
        assertNotNull(MtkMotoVendorTags.MOTO_DO_REMOSAIC)
        assertNotNull(MtkMotoVendorTags.MOTO_ZSL_CROP)
        assertNotNull(MtkMotoVendorTags.MOTO_IS_MOTCAMERA2)
        assertNotNull(MtkMotoVendorTags.MOTO_IS_PRO_MODE)
        assertNotNull(MtkMotoVendorTags.MOTO_CURRENT_MODE)
        assertNotNull(MtkMotoVendorTags.MOTO_NIGHT_REQUEST)
        assertNotNull(MtkMotoVendorTags.MTK_3DNR_MODE)
        assertNotNull(MtkMotoVendorTags.MTK_MFNR_MFB_MODE)
        assertNotNull(MtkMotoVendorTags.MTK_MFNR_AIS_MODE)
        assertNotNull(MtkMotoVendorTags.MTK_VIDEO_AINR_MODES)
        assertNotNull(MtkMotoVendorTags.MOTO_VHDR_STATE)
        assertNotNull(MtkMotoVendorTags.MOTO_VSTAB_SMARTEIS)
        assertNotNull(MtkMotoVendorTags.MOTO_VSTAB_IHC)
        assertNotNull(MtkMotoVendorTags.MOTO_VSTAB_PZS)
        assertNotNull(MtkMotoVendorTags.MOTO_ADRC_GAIN)
    }

    @Test
    fun testCameraManualSettings_defaultJpegQualityIs95() {
        val defaults = CameraManualSettings()
        assertEquals(95, defaults.jpegQuality)
    }

    @Test
    fun testViennaLensRouter_mapsAllOpticalLensesToCorrectPhysicalSensorIds() {
        val resolved = mapOf(
            org.gxvcam.camera.ViennaLensRouter.LensRole.MAIN to "0",
            org.gxvcam.camera.ViennaLensRouter.LensRole.FRONT to "1",
            org.gxvcam.camera.ViennaLensRouter.LensRole.ULTRA_WIDE to "2",
            org.gxvcam.camera.ViennaLensRouter.LensRole.TELE_3X to "3",
            org.gxvcam.camera.ViennaLensRouter.LensRole.LOGICAL_BACK to "5"
        )
        assertEquals("2", org.gxvcam.camera.ViennaLensRouter.cameraIdForOpticalLens(OpticalLens.UW, isBackCamera = true, resolvedIds = resolved))
        assertEquals("0", org.gxvcam.camera.ViennaLensRouter.cameraIdForOpticalLens(OpticalLens.MAIN_1X, isBackCamera = true, resolvedIds = resolved))
        assertEquals("0", org.gxvcam.camera.ViennaLensRouter.cameraIdForOpticalLens(OpticalLens.CROP_2X, isBackCamera = true, resolvedIds = resolved))
        assertEquals("3", org.gxvcam.camera.ViennaLensRouter.cameraIdForOpticalLens(OpticalLens.TELE_3X, isBackCamera = true, resolvedIds = resolved))
        assertEquals("1", org.gxvcam.camera.ViennaLensRouter.cameraIdForOpticalLens(OpticalLens.MAIN_1X, isBackCamera = false, resolvedIds = resolved))
    }

    @Test
    fun testCrossSensorLensSelection_switchesImmediatelyToTargetLens() = runTest(testDispatcher) {
        // Default is 1.0x MAIN_1X (camera "0")
        assertEquals(OpticalLens.MAIN_1X, viewModel.uiState.value.activeLens)
        assertEquals(1.0f, viewModel.uiState.value.currentZoomRatio, 0.01f)

        // Switch to 0.6x UW (camera "2") -> immediate cross-sensor jump without intermediate frames
        viewModel.onEvent(GXVcamEvent.SelectLens(OpticalLens.UW))
        assertEquals(OpticalLens.UW, viewModel.uiState.value.activeLens)
        assertEquals(0.6f, viewModel.uiState.value.currentZoomRatio, 0.01f)

        // Switch to 3.0x TELE_3X (camera "3") -> immediate cross-sensor jump
        viewModel.onEvent(GXVcamEvent.SelectLens(OpticalLens.TELE_3X))
        assertEquals(OpticalLens.TELE_3X, viewModel.uiState.value.activeLens)
        assertEquals(3.0f, viewModel.uiState.value.currentZoomRatio, 0.01f)

        // Switch back to 1.0x MAIN_1X (camera "0") -> immediate cross-sensor jump
        viewModel.onEvent(GXVcamEvent.SelectLens(OpticalLens.MAIN_1X))
        assertEquals(OpticalLens.MAIN_1X, viewModel.uiState.value.activeLens)
        assertEquals(1.0f, viewModel.uiState.value.currentZoomRatio, 0.01f)

        // Switch to 2.0x CROP_2X (same physical camera "0") -> smooth animation completes to 2.0x
        viewModel.onEvent(GXVcamEvent.SelectLens(OpticalLens.CROP_2X))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OpticalLens.CROP_2X, viewModel.uiState.value.activeLens)
        assertEquals(2.0f, viewModel.uiState.value.currentZoomRatio, 0.01f)
    }
}
