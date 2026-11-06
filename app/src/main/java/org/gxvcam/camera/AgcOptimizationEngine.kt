package org.gxvcam.camera

import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import org.gxvcam.ui.state.AgcLibPatcherSettings
import org.gxvcam.ui.state.OpticalLens

object AgcOptimizationEngine {

    @OptIn(ExperimentalCamera2Interop::class)
    fun buildAgcRequestOptions(
        agc: AgcLibPatcherSettings,
        activeLens: OpticalLens = OpticalLens.MAIN_1X,
        supportedKeys: Set<String> = emptySet()
    ): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()

        fun <T : Any> setIfSupported(key: CaptureRequest.Key<T>?, value: T) {
            if (key != null && (supportedKeys.isEmpty() || supportedKeys.contains(key.name))) {
                builder.setCaptureRequestOption(key, value)
            }
        }

        // 1. Sharpness Level (gsharpen)
        val edgeMode = if (agc.sharpnessLevel >= 0.5f) {
            CaptureRequest.EDGE_MODE_HIGH_QUALITY
        } else {
            CaptureRequest.EDGE_MODE_OFF
        }
        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, edgeMode)

        // 2. Luma Denoise Level (Spatial)
        val noiseReductionMode = when {
            agc.lumaDenoiseLevel >= 1.0f -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            agc.lumaDenoiseLevel >= 0.3f -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            else -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
        }
        builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode)

        // 3. Contrast & Tonemap: Use ISP HIGH_QUALITY tonemapping to preserve sRGB / D65 color calibration
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

        // 4. Optical Aberration & Hot Pixel Modes
        builder.setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.HOT_PIXEL_MODE,
            CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
        )

        // 5. Motorola Tintless & In-Sensor Crop tuning (checked against supported keys)
        setIfSupported(MtkMotoVendorTags.MOTO_TINTLESS_ENABLE, 1)
        setIfSupported(MtkMotoVendorTags.MOTO_ADRC_ENABLE, 1)
        if (activeLens == OpticalLens.CROP_2X) {
            setIfSupported(MtkMotoVendorTags.MOTO_IN_SENSOR_CROP_ENABLE, 1)
        }

        // 6. MediaTek Dimensity Multi-Frame Noise Reduction (MFNR) & Best Shot Selector
        setIfSupported(MtkMotoVendorTags.MTK_MFNR_MFB_MODE, 1)
        setIfSupported(MtkMotoVendorTags.MTK_MFNR_BSS_COUNT, 4)
        setIfSupported(MtkMotoVendorTags.MTK_ZSL_MODE, 1)
        setIfSupported(MtkMotoVendorTags.MOTO_ZSL_MODE, 1)

        // 7. MediaTek Dimensity / Motorola ZSL Session Fix
        if (agc.hdrNetMultiFrameBoost) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        }

        return builder.build()
    }
}
