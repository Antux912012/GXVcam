package org.gxvcam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.GXVcamViewModel
import org.gxvcam.ui.state.OpticalLens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic Stability and Stress Test Suite for GXVcam.
 * Validates rapid lens switching, burst shutter triggers, and concurrency safety.
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
}
