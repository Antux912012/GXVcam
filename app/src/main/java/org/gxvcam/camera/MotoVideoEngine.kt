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
        rollDegrees: Float = 0f
    ) {
        // Set target FPS range (e.g. 30..30 or 60..60)
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(settings.frameRate.fps, settings.frameRate.fps)
        )

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
            }
            MotoVideoStabilization.ADAPTIVE,
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
            }
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
