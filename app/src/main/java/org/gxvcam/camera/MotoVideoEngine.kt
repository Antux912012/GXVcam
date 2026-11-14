package org.gxvcam.camera

import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.MotoVideoStabilization
import org.gxvcam.ui.state.VideoResolution
import org.gxvcam.video.SeamlessLensZoomController

object MotoVideoEngine {

    fun computeTargetFps(settings: MotoVideoSettings): Int {
        val maxFps = when {
            settings.isDualCaptureEnabled -> 30
            settings.resolution == VideoResolution.UHD_4K -> 30
            settings.resolution == VideoResolution.FHD_1080P -> 60
            else -> settings.frameRate.fps
        }
        return minOf(settings.frameRate.fps, maxFps)
    }

    fun compute180ShutterTargetNs(settings: MotoVideoSettings): Long {
        return SeamlessLensZoomController.compute180ShutterExposureNs(computeTargetFps(settings))
    }

    fun computeEquivalentIso(
        meteredExposureNs: Long,
        meteredIso: Int,
        targetExposureNs: Long,
        isoRange: IntRange = 50..6400
    ): Int {
        if (meteredExposureNs <= 0L || targetExposureNs <= 0L) {
            return meteredIso.coerceIn(isoRange.first, isoRange.last)
        }
        val evProduct = meteredExposureNs.toDouble() * meteredIso.coerceAtLeast(1).toDouble()
        val computed = (evProduct / targetExposureNs.toDouble()).toInt()
        return computed.coerceIn(isoRange.first, isoRange.last)
    }

    fun computeTargetBitRate(settings: MotoVideoSettings): Int {
        val effectiveRes = if (settings.isDualCaptureEnabled && settings.resolution == VideoResolution.UHD_4K) {
            VideoResolution.FHD_1080P
        } else {
            settings.resolution
        }
        val targetFps = computeTargetFps(settings)
        return when (effectiveRes) {
            VideoResolution.UHD_4K -> 65_000_000
            VideoResolution.FHD_1080P -> if (targetFps >= 60) 35_000_000 else 24_000_000
            VideoResolution.HD_720P -> if (targetFps >= 60) 18_000_000 else 12_000_000
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyVideoInterop(
        builder: CaptureRequestOptions.Builder,
        settings: MotoVideoSettings,
        zoomRatio: Float = 1.0f,
        rollDegrees: Float = 0f,
        supportedKeyNames: Set<String> = emptySet(),
        hasOis: Boolean = true,
        manualIsoFor180Shutter: Int? = null
    ) {
        // 1. Hardware-friendly AE FPS range (Night Video drops floor to 24fps for brighter frames)
        val targetFps = computeTargetFps(settings)
        val fpsRange = if (settings.isNightVisionVideoEnabled && targetFps <= 30) {
            Range(24, 30)
        } else {
            Range(targetFps, targetFps)
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        // Optional 180-degree cinema shutter lock when an explicit manual ISO is supplied
        if (settings.isShutter180Locked && manualIsoFor180Shutter != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, compute180ShutterTargetNs(settings))
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIsoFor180Shutter)
        }

        // 2. Standard Camera2 Video Quality Pipeline
        // NOTE: Do NOT inject raw MTK/Moto PIP or EIS vendor tags (MTK_PIP_DEVICES, MTK_EIS_MODE,
        // MTK_PREVIEW_EIS, MOTO_VSTAB_*, MTK_HFPS_MODE, MTK_VIDEO_AINR_MODES) here: on MT6878
        // camerahalserver, setting those without Motorola's proprietary HAL session triggers
        // PIPCameraGPUControl errors and outputs all-zero YUV buffers (solid green viewfinder).
        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )

        // 3. Standard Android Camera2 OIS & Video Stabilization
        val effectiveOis = if (hasOis && settings.stabilization != MotoVideoStabilization.OFF) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }
        builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, effectiveOis)

        val videoStabMode = if (settings.stabilization != MotoVideoStabilization.OFF) {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        } else {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoStabMode)
    }
}
