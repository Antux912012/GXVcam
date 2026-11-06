package org.gxvcam

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.camera2.internal.Camera2CameraFactory
import androidx.camera.core.CameraXConfig

/**
 * Exposes the Motorola/MediaTek "aux" cameras to CameraX.
 *
 * On the Edge 50 Neo the ultra-wide (ID 2), telephoto (ID 3) and logical multi-camera (ID 5)
 * are hidden from [CameraManager.getCameraIdList] for every package that is not listed in
 * `vendor.camera.aux.packagelist` (only Moto's own camera apps). They can still be opened by ID,
 * which is why the original direct Camera2 pipeline could use the ultra-wide, while CameraX
 * (which only enumerates `getCameraIdList()`) only ever saw cameras 0 and 1.
 *
 * We wrap the default [Camera2CameraFactory] and append the hidden, backward-compatible IDs to
 * its available camera list so CameraX can bind them like any other camera.
 */
class GXVcamApplication : Application(), CameraXConfig.Provider {

    @SuppressLint("RestrictedApi")
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setCameraFactoryProvider { context, threadConfig, limiter, openRetryTimeoutMs ->
                Camera2CameraFactory(context, threadConfig, limiter, openRetryTimeoutMs).also {
                    exposeHiddenAuxCameras(context, it)
                }
            }
            .build()

    @SuppressLint("RestrictedApi")
    private fun exposeHiddenAuxCameras(context: Context, factory: Camera2CameraFactory) {
        try {
            val field = Camera2CameraFactory::class.java.getDeclaredField("mAvailableCameraIds")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val availableIds = field[factory] as MutableList<String>

            val cameraManager = context.getSystemService(CameraManager::class.java)
            val hiddenIds = HIDDEN_CAMERA_ID_CANDIDATES.filter { id ->
                (id !in availableIds) && isBackwardCompatible(cameraManager, id)
            }
            availableIds.addAll(hiddenIds)
            Log.i(TAG, "CameraX camera IDs: $availableIds (exposed hidden aux: $hiddenIds)")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to expose hidden aux cameras to CameraX: ${e.message}")
        }
    }

    private fun isBackwardCompatible(cameraManager: CameraManager, cameraId: String): Boolean =
        try {
            cameraManager.getCameraCharacteristics(cameraId)[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        } catch (_: Exception) {
            false
        }

    private companion object {
        const val TAG = "GXVcamApplication"

        /** Aux IDs observed on the Edge 50 Neo probe (2 = UW, 3 = tele, 5 = logical). */
        val HIDDEN_CAMERA_ID_CANDIDATES = listOf("2", "3", "5")
    }
}
