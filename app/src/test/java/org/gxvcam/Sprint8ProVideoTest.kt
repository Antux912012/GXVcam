package org.gxvcam

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.gxvcam.audio.ProAudioInputRouter
import org.gxvcam.audio.WindCutBiquadFilter
import org.gxvcam.camera.MotoVideoEngine
import org.gxvcam.camera.ViennaLensRouter.LensRole
import org.gxvcam.data.GXVcamPreferencesRepository
import org.gxvcam.ui.state.AudioInputSource
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.ProVideoLutStyle
import org.gxvcam.ui.state.VideoCodecProfile
import org.gxvcam.ui.state.VideoColorProfile
import org.gxvcam.ui.state.VideoFrameRate
import org.gxvcam.ui.state.VideoResolution
import org.gxvcam.video.GxvVideoCodecManager
import org.gxvcam.video.SeamlessLensZoomController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class Sprint8ProVideoTest {

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
    fun testSeamlessLensZoomController_hysteresisPrewarmAnd12FrameDissolve() {
        val controller = SeamlessLensZoomController(emaAlpha = 0.25f, dissolveTotalFrames = 12)
        val prewarmed = mutableListOf<LensRole>()

        // Starts on MAIN (1.0x)
        val s1 = controller.updateZoom(1.0f) { prewarmed.add(it) }
        assertEquals(LensRole.MAIN, s1.activeRole)
        assertFalse(s1.isTransitioning)

        // Slight jitter at 0.96x must stay on MAIN due to hysteresis (threshold is <= 0.94f)
        val sJitter = controller.updateZoom(0.96f) { prewarmed.add(it) }
        assertEquals(LensRole.MAIN, sJitter.activeRole)

        // Crossing 0.92f switches to ULTRA_WIDE and starts 12-frame dissolve
        val sUw = controller.updateZoom(0.92f) { prewarmed.add(it) }
        assertEquals(LensRole.ULTRA_WIDE, sUw.activeRole)
        assertTrue(sUw.isTransitioning)
        assertEquals(0.0f, sUw.transitionBlendFactor, 0.001f)

        // Advancing 12 frames completes dissolve
        repeat(11) {
            assertTrue(controller.advanceFrame().isTransitioning)
        }
        val sDone = controller.advanceFrame()
        assertFalse(sDone.isTransitioning)
        assertEquals(1.0f, sDone.transitionBlendFactor, 0.001f)

        // Approaching 3.0x triggers standby pre-warm for TELE_3X
        controller.updateZoom(1.5f)
        prewarmed.clear()
        controller.updateZoom(2.75f) { prewarmed.add(it) }
        assertTrue(prewarmed.contains(LensRole.TELE_3X))

        // Crossing 3.05x switches to TELE_3X
        val sTele = controller.updateZoom(3.05f)
        assertEquals(LensRole.TELE_3X, sTele.activeRole)
    }

    @Test
    fun testSeamlessLensZoomController_emaRggbAnd180ShutterRule() {
        val controller = SeamlessLensZoomController(emaAlpha = 0.20f)
        controller.onColorCorrectionSample(LensRole.MAIN, r = 1.90f, gEven = 1.0f, gOdd = 1.0f, b = 1.55f)
        val firstUw = controller.onColorCorrectionSample(LensRole.ULTRA_WIDE, r = 1.40f, gEven = 1.0f, gOdd = 1.0f, b = 1.95f)
        val secondUw = controller.onColorCorrectionSample(LensRole.ULTRA_WIDE, r = 1.50f, gEven = 1.0f, gOdd = 1.0f, b = 1.85f)

        val rgbFirst = firstUw.toRgbShaderMultipliers()
        val rgbSecond = secondUw.toRgbShaderMultipliers()
        assertTrue(rgbFirst[0] in 0.4f..2.5f)
        assertTrue(rgbSecond[2] in 0.4f..2.5f)
        assertTrue("EMA smoothing should dampen step change", abs(rgbSecond[0] - rgbFirst[0]) < 0.15f)

        assertEquals(8_333_333L, SeamlessLensZoomController.compute180ShutterExposureNs(60))
        assertEquals(16_666_666L, SeamlessLensZoomController.compute180ShutterExposureNs(30))
        assertEquals("1/120s (180°)", SeamlessLensZoomController.format180ShutterLabel(60))
    }

    @Test
    fun testWindCutBiquadFilter_attenuates45HzAndPasses1kHzSpeech() {
        val sampleRate = 48_000.0
        val filterLow = WindCutBiquadFilter(sampleRateHz = sampleRate.toFloat(), cutoffHz = 80f)
        val filterHigh = WindCutBiquadFilter(sampleRateHz = sampleRate.toFloat(), cutoffHz = 80f)

        fun sineRmsAfterSettling(freqHz: Double, filter: WindCutBiquadFilter): Double {
            val n = 4800
            var sumSq = 0.0
            var count = 0
            for (i in 0 until n) {
                val x = (sin(2.0 * PI * freqHz * i / sampleRate) * 10_000.0).toFloat()
                val y = filter.processSample(0, x).toDouble()
                if (i >= 2400) {
                    sumSq += y * y
                    count++
                }
            }
            return sqrt(sumSq / count)
        }

        val wind45HzRms = sineRmsAfterSettling(45.0, filterLow)
        val voice1kHzRms = sineRmsAfterSettling(1000.0, filterHigh)

        assertTrue("Expected 1kHz speech ($voice1kHzRms) to be much stronger than 45Hz wind ($wind45HzRms)", voice1kHzRms > wind45HzRms * 2.5)
        assertTrue("Expected 1kHz speech to pass near unity", voice1kHzRms > 6500.0)
    }

    @Test
    fun testProAudioInputRouter_computeStereoDbfsBounds() {
        val silent = ShortArray(200) { 0 }
        val (lSil, rSil) = ProAudioInputRouter.computeStereoDbfs(silent, silent.size, isStereo = true)
        assertEquals(-60f, lSil, 0.01f)
        assertEquals(-60f, rSil, 0.01f)

        val fullScale = ShortArray(200) { idx -> if (idx % 2 == 0) 32767 else (-32767).toShort() }
        val (lMax, rMax) = ProAudioInputRouter.computeStereoDbfs(fullScale, fullScale.size, isStereo = true)
        assertTrue("Expected full scale in [-60, 0], got $lMax / $rMax", lMax in -60f..0f && rMax in -60f..0f)
        assertTrue(lMax > -1f && rMax > -1f)
    }

    @Test
    fun testVideoCodecBitratesAndMotoVideoEngineHelpers() {
        assertEquals(14_000_000, GxvVideoCodecManager.computeTargetBitrate(VideoCodecProfile.AV1, 1920, 1080, 60, hasHwAv1 = false))
        assertEquals(32_000_000, GxvVideoCodecManager.computeTargetBitrate(VideoCodecProfile.H265_HEVC, 1920, 1080, 60, hasHwAv1 = false))
        assertEquals(65_000_000, GxvVideoCodecManager.computeTargetBitrate(VideoCodecProfile.H264_AVC, 3840, 2160, 30, hasHwAv1 = false))

        val settings = MotoVideoSettings(
            resolution = VideoResolution.FHD_1080P,
            frameRate = VideoFrameRate.FPS_60,
            isShutter180Locked = true
        )
        assertEquals(8_333_333L, MotoVideoEngine.compute180ShutterTargetNs(settings))
        assertEquals(400, MotoVideoEngine.computeEquivalentIso(16_666_666L, 200, 8_333_333L, 50..6400))
    }

    @Test
    fun testPreferencesAndViewModel_sprint8VideoAndZoomScrollHaptics() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "sprint8_prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = this, produceFile = { file })
        val repo = GXVcamPreferencesRepository(dataStore)

        val vm = GXVcamViewModel()
        vm.attachPreferencesRepository(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        // Default haptics enabled
        assertTrue(vm.uiState.value.manualSettings.isZoomScrollHapticsEnabled)

        // Disable Zoom & Scroll Bar Haptics via event
        vm.onEvent(GXVcamEvent.SetZoomScrollHapticsEnabled(false))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.manualSettings.isZoomScrollHapticsEnabled)

        // Update Pro Video settings via event
        val customVideo = MotoVideoSettings(
            isProVideoEngineEnabled = true,
            codecProfile = VideoCodecProfile.AV1,
            colorProfile = VideoColorProfile.GXV_LOG_ACES,
            isShutter180Locked = false,
            audioInputSource = AudioInputSource.CAMCORDER_STEREO,
            isWindNoiseCutEnabled = false,
            activeLutStyle = ProVideoLutStyle.OPPO_LUMO_FILM,
            lutBlend = 0.55f,
            vibrance = 0.25f
        )
        vm.onEvent(GXVcamEvent.UpdateMotoVideoSettings(customVideo))
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = repo.preferencesFlow.first()
        assertFalse(persisted.manualSettings.isZoomScrollHapticsEnabled)
        assertEquals(VideoCodecProfile.AV1, persisted.videoSettings.codecProfile)
        assertEquals(VideoColorProfile.GXV_LOG_ACES, persisted.videoSettings.colorProfile)
        assertFalse(persisted.videoSettings.isShutter180Locked)
        assertEquals(AudioInputSource.CAMCORDER_STEREO, persisted.videoSettings.audioInputSource)
        assertFalse(persisted.videoSettings.isWindNoiseCutEnabled)
        assertEquals(ProVideoLutStyle.OPPO_LUMO_FILM, persisted.videoSettings.activeLutStyle)
        assertEquals(0.55f, persisted.videoSettings.lutBlend, 0.001f)
        assertEquals(0.25f, persisted.videoSettings.vibrance, 0.001f)
    }
}
