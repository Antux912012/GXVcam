package org.gxvcam.camera

import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.MotoVideoStabilization

object MotoVideoEngine {

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyVideoInterop(
        builder: CaptureRequestOptions.Builder,
        settings: MotoVideoSettings,
        zoomRatio: Float = 1.0f,
        rollDegrees: Float = 0f,
        supportedKeyNames: Set<String> = emptySet()
    ) {
        // 1. Target AE FPS range (e.g. 30..30, 60..60, or 120..120)
        // MediaTek Dimensity 7300 supports up to 30fps for 4K UHD and dual concurrent capture
        val maxFps = if (settings.isDualCaptureEnabled || settings.resolution == org.gxvcam.ui.state.VideoResolution.UHD_4K) 30 else settings.frameRate.fps
        val targetFps = minOf(settings.frameRate.fps, maxFps)
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(targetFps, targetFps)
        )

        // 2. Motorola Video Night Vision (low-light boost for Dimensity ISP)
        val nvValue = if (settings.isNightVisionVideoEnabled) 1 else 0
        setIfSupported(builder, MtkMotoVendorTags.MOTO_VIDEO_NV, nvValue, supportedKeyNames)

        // 3. Motorola HDR10+ / 10-bit HLG Video Mode Tuning
        val hdrValue = if (settings.isHdr10BitEnabled) 1 else 0
        setIfSupported(builder, MtkMotoVendorTags.MOTO_HDR10_TUNING, hdrValue, supportedKeyNames)
        setIfSupported(builder, MtkMotoVendorTags.MOTO_3D_HDR_MODE, hdrValue, supportedKeyNames)
        setIfSupported(builder, MtkMotoVendorTags.MOTO_VHDR_STATE, hdrValue, supportedKeyNames)

        // 4. Motorola Edge 50 Neo Dual Capture / PIP Tag
        val dualVal = if (settings.isDualCaptureEnabled) 1 else 0
        setIfSupported(builder, MtkMotoVendorTags.MOTO_IS_DUAL_CAPTURE, dualVal, supportedKeyNames)
        setIfSupported(builder, MtkMotoVendorTags.MTK_PIP_DEVICES, dualVal, supportedKeyNames)

        // 5. Motorola Edge 50 Neo Stabilization Pipeline (OIS + Smart EIS + 360° Horizon Lock)
        // Android minSdk = 34 guarantees PREVIEW_STABILIZATION support
        when (settings.stabilization) {
            MotoVideoStabilization.OFF -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_SMARTEIS, 0, supportedKeyNames)
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_IHC, 0, supportedKeyNames)
            }
            MotoVideoStabilization.ADAPTIVE -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                )
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_SMARTEIS, 1, supportedKeyNames)
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_IHC, 0, supportedKeyNames)
            }
            MotoVideoStabilization.HORIZON_LOCK -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                )
                // IHC = In-Hardware Horizon Correction (360° horizon lock)
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_SMARTEIS, 1, supportedKeyNames)
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_IHC, 1, supportedKeyNames)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T : Any> setIfSupported(
        builder: CaptureRequestOptions.Builder,
        key: CaptureRequest.Key<T>?,
        value: T,
        supportedKeyNames: Set<String>
    ) {
        if (key != null && (supportedKeyNames.isEmpty() || supportedKeyNames.contains(key.name))) {
            builder.setCaptureRequestOption(key, value)
        }
    }
}
