package org.gxvcam.camera

import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import org.gxvcam.ui.state.AgcLibPatcherSettings
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.OpticalLens

object AgcOptimizationEngine {

    @OptIn(ExperimentalCamera2Interop::class)
    fun buildAgcRequestOptions(
        agc: AgcLibPatcherSettings,
        activeLens: OpticalLens = OpticalLens.MAIN_1X,
        supportedKeys: Set<String> = emptySet(),
        cameraMode: CameraMode = CameraMode.PHOTO,
        hasOis: Boolean = true
    ): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()

        fun <T : Any> setIfSupported(key: CaptureRequest.Key<T>?, value: T) {
            if (key != null && (supportedKeys.isEmpty() || supportedKeys.contains(key.name))) {
                try {
                    builder.setCaptureRequestOption(key, value)
                } catch (_: IllegalArgumentException) {
                    // Ignored on HAL implementations lacking this vendor tag
                }
            }
        }

        // 1. Sharpness & Edge Enhancement
        val edgeMode = if (agc.sharpnessLevel >= 0.5f) {
            CaptureRequest.EDGE_MODE_HIGH_QUALITY
        } else {
            CaptureRequest.EDGE_MODE_OFF
        }
        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, edgeMode)

        // 2. Spatial & Multi-Frame Noise Reduction
        val noiseReductionMode = when {
            agc.lumaDenoiseLevel >= 1.0f -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            agc.lumaDenoiseLevel >= 0.3f -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
        }
        builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode)

        // 3. Tonemap, Color Correction, Aberration, Hot Pixel & OIS
        builder.setCaptureRequestOption(
            CaptureRequest.TONEMAP_MODE,
            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )
        builder.setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.HOT_PIXEL_MODE,
            CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (hasOis) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )

        // 4. Safe MediaTek Imagiq 950 & Motorola In-Sensor Crop Hints
        // NOTE: Do NOT set MTK_HDR_MODE / MTK_SESSION_HDR_MODE / MTK_SINGLE_YUV_NR / MTK_HIGH_QUALITY_YUV
        // on repeating CameraX Preview requests, as Imagiq 950 switches to an unconfigured staggered
        // VHDR sensor channel on Camera 0, 1, and 2, causing a solid green viewfinder.
        setIfSupported(MtkMotoVendorTags.MTK_MFNR_MFB_MODE, 1)

        val isInSensorCrop = activeLens == OpticalLens.CROP_2X
        setIfSupported(MtkMotoVendorTags.MOTO_IN_SENSOR_CROP_ENABLE, (if (isInSensorCrop) 1 else 0).toByte())

        if (agc.hdrNetMultiFrameBoost) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        }

        return builder.build()
    }
}
