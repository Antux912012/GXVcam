package org.gxvcam.camera

import android.hardware.camera2.CaptureRequest
import android.media.AudioRecord
import android.media.MicrophoneDirection
import android.os.Build
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
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(settings.frameRate.fps, settings.frameRate.fps)
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

        // 4. Motorola Edge 50 Neo Stabilization Pipeline (OIS + Smart EIS + 360° Horizon Lock)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_SMARTEIS, 1, supportedKeyNames)
                setIfSupported(builder, MtkMotoVendorTags.MOTO_VSTAB_IHC, 0, supportedKeyNames)
            }
            MotoVideoStabilization.HORIZON_LOCK -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
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

    fun applyMicrophoneAudioZoom(
        audioRecord: AudioRecord?,
        isEnabled: Boolean,
        zoomRatio: Float,
        isBackCamera: Boolean = true
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isEnabled && audioRecord != null) {
            try {
                val direction = if (isBackCamera) {
                    MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER
                } else {
                    MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER
                }
                audioRecord.setPreferredMicrophoneDirection(direction)
                val fieldDimension = ((zoomRatio - 1.0f) / 9.0f).coerceIn(0f, 1f)
                audioRecord.setPreferredMicrophoneFieldDimension(fieldDimension)
            } catch (_: Exception) {
                // Device may not support directional mic API
            }
        }
    }
}
