package org.gxvcam.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import org.gxvcam.ui.state.CameraMode
import java.util.concurrent.Executor

enum class MotoAiSceneMode {
    AUTO,   // CameraExtensionCharacteristics.EXTENSION_AUTO (Moto AI Scene Optimization)
    HDR,    // CameraExtensionCharacteristics.EXTENSION_HDR (Moto AI Multi-Frame HDR)
    NIGHT,  // CameraExtensionCharacteristics.EXTENSION_NIGHT (Moto Night Vision)
    BOKEH   // CameraExtensionCharacteristics.EXTENSION_BOKEH (Moto Portrait Depth)
}

/**
 * Routes still captures through Motorola's built-in Moto AI / MediaTek Imagiq 950 hardware ISP
 * pipeline (`com.motorola.androidx.camera.extensions` + `CameraExtensionCharacteristics`) when the
 * user selects Moto AI Fallback or when battery/thermal auto-fallback engages.
 */
class MotoAiFallbackController(private val cameraManager: CameraManager) {

    companion object {
        private const val TAG = "MotoAiFallback"

        fun sceneModeFor(cameraMode: CameraMode): MotoAiSceneMode = when (cameraMode) {
            CameraMode.NIGHT -> MotoAiSceneMode.NIGHT
            CameraMode.PORTRAIT -> MotoAiSceneMode.BOKEH
            CameraMode.PHOTO -> MotoAiSceneMode.HDR
            CameraMode.PRO, CameraMode.VIDEO -> MotoAiSceneMode.AUTO
        }
    }

    /**
     * Checks if Motorola's OEM Camera2 Extension implementation supports the requested Moto AI
     * mode on the active physical/logical camera ID, falling back to AUTO or HDR if unavailable.
     */
    fun getSupportedMotoAiExtension(cameraId: String, requestedMode: MotoAiSceneMode): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val extChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
            val supported = extChars.supportedExtensions

            val targetExt = when (requestedMode) {
                MotoAiSceneMode.AUTO -> CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
                MotoAiSceneMode.HDR -> CameraExtensionCharacteristics.EXTENSION_HDR
                MotoAiSceneMode.NIGHT -> CameraExtensionCharacteristics.EXTENSION_NIGHT
                MotoAiSceneMode.BOKEH -> CameraExtensionCharacteristics.EXTENSION_BOKEH
            }

            when {
                supported.contains(targetExt) -> targetExt
                supported.contains(CameraExtensionCharacteristics.EXTENSION_AUTOMATIC) ->
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
                supported.contains(CameraExtensionCharacteristics.EXTENSION_HDR) ->
                    CameraExtensionCharacteristics.EXTENSION_HDR
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Extension characteristics query failed for camera $cameraId: ${e.message}")
            null
        }
    }

    /**
     * Opens Motorola's built-in Moto AI `ExtensionSessionConfiguration` if supported on the ROM,
     * or falls back to the MediaTek Imagiq 950 hardware ISP High-Quality pipeline.
     */
    fun openMotoAiFallbackSession(
        device: CameraDevice,
        cameraId: String,
        previewSurface: Surface,
        jpegReader: ImageReader,
        mode: MotoAiSceneMode,
        executor: Executor,
        onSessionReady: (anySession: Any) -> Unit
    ) {
        val extType = getSupportedMotoAiExtension(cameraId, mode)
        val outputs = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(jpegReader.surface)
        )

        if (extType != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val extConfig = ExtensionSessionConfiguration(
                    extType,
                    outputs,
                    executor,
                    object : CameraExtensionSession.StateCallback() {
                        override fun onConfigured(session: CameraExtensionSession) {
                            onSessionReady(session)
                        }

                        override fun onConfigureFailed(session: CameraExtensionSession) {
                            Log.w(TAG, "ExtensionSession configure failed (ext=$extType); falling back to Imagiq 950 regular session")
                            openImagiq950HardwareIspSession(device, outputs, executor, onSessionReady)
                        }
                    }
                )
                device.createExtensionSession(extConfig)
                return
            } catch (e: Exception) {
                Log.w(TAG, "createExtensionSession failed (${e.message}); falling back to Imagiq 950 regular session")
            }
        }

        openImagiq950HardwareIspSession(device, outputs, executor, onSessionReady)
    }

    private fun openImagiq950HardwareIspSession(
        device: CameraDevice,
        outputs: List<OutputConfiguration>,
        executor: Executor,
        onSessionReady: (anySession: Any) -> Unit
    ) {
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    onSessionReady(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Imagiq 950 regular CaptureSession configuration failed")
                }
            }
        )
        device.createCaptureSession(sessionConfig)
    }

    /**
     * Configures a standalone Camera2 `CaptureRequest.Builder` for the built-in MediaTek
     * Imagiq 950 / Moto AI hardware ISP.
     */
    fun applyBuiltInMotoIspParameters(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
    }

    /**
     * Applies built-in Moto AI / Imagiq 950 hardware ISP parameters onto a CameraX
     * `CaptureRequestOptions.Builder` for seamless CameraX interop fallback.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun applyBuiltInMotoIspInterop(
        builder: CaptureRequestOptions.Builder,
        cameraMode: CameraMode,
        supportedKeyNames: Set<String> = emptySet()
    ) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_SCENE_MODE,
            if (cameraMode == CameraMode.NIGHT) CaptureRequest.CONTROL_SCENE_MODE_NIGHT else CaptureRequest.CONTROL_SCENE_MODE_HDR
        )
        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T : Any> setIfSupported(
        builder: CaptureRequestOptions.Builder,
        key: CaptureRequest.Key<T>?,
        value: T,
        supportedKeyNames: Set<String>
    ) {
        if (key != null && (supportedKeyNames.isEmpty() || supportedKeyNames.contains(key.name))) {
            try {
                builder.setCaptureRequestOption(key, value)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
