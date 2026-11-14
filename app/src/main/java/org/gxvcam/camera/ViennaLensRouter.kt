package org.gxvcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import org.gxvcam.ui.state.OpticalLens
import java.util.concurrent.Executor

/**
 * Camera2 physical lens router for Motorola Edge 50 Neo (`vienna` / `MT6878` Imagiq 950).
 *
 * Verified physical IDs from probe_out/edge50neo:
 * - MAIN ("0"): Sony IMX896 / LYT-700C (12.5MP binned 4096x3072, 50MP max-res 8192x6144)
 * - FRONT ("1"): Samsung S5KJD1 (32MP Quad-Bayer, 3280x2460 RAW / 6560x4920 high-res)
 * - ULTRA_WIDE ("2"): GalaxyCore GC13A2 (13MP, 4160x3120 RAW)
 * - TELE_3X ("3"): Samsung S5K3K1 (10MP, 3648x2736 RAW)
 * - LOGICAL_BACK ("5"): Multi-camera logical wrapper over [0, 2, 3]
 */
object ViennaLensRouter {

    private const val TAG = "ViennaLensRouter"

    fun hasPhysicalEvsSensor(context: Context): Boolean {
        return runCatching {
            context.packageManager.hasSystemFeature("com.motorola.hardware.evs")
        }.getOrDefault(false)
    }

    enum class LensRole(val defaultId: String) {
        MAIN("0"),
        FRONT("1"),
        ULTRA_WIDE("2"),
        TELE_3X("3"),
        LOGICAL_BACK("5")
    }

    data class SensorProfile(
        val cameraId: String,
        val role: LensRole,
        val supportsRaw: Boolean,
        val defaultRawSize: Size,
        val maxResRawSize: Size?,
        val focalLengthMm: Float,
        val whiteLevel: Float,
        val blackLevelPattern: FloatArray,
        val cfaPattern: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SensorProfile) return false
            return cameraId == other.cameraId &&
                role == other.role &&
                supportsRaw == other.supportsRaw &&
                defaultRawSize == other.defaultRawSize &&
                maxResRawSize == other.maxResRawSize &&
                focalLengthMm == other.focalLengthMm &&
                whiteLevel == other.whiteLevel &&
                blackLevelPattern.contentEquals(other.blackLevelPattern) &&
                cfaPattern == other.cfaPattern
        }

        override fun hashCode(): Int {
            var res = cameraId.hashCode()
            res = 31 * res + role.hashCode()
            res = 31 * res + supportsRaw.hashCode()
            res = 31 * res + defaultRawSize.hashCode()
            res = 31 * res + (maxResRawSize?.hashCode() ?: 0)
            res = 31 * res + focalLengthMm.hashCode()
            res = 31 * res + whiteLevel.hashCode()
            res = 31 * res + blackLevelPattern.contentHashCode()
            res = 31 * res + cfaPattern
            return res
        }
    }

    data class RawResolutionPlan(
        val width: Int,
        val height: Int,
        val pixelMode: Int,
        val estimatedSlotBytes: Long = width.toLong() * height.toLong() * 2L
    ) {
        val size: Size get() = Size(width, height)
    }

    fun resolvePhysicalIds(manager: CameraManager): Map<LensRole, String> {
        val result = linkedMapOf<LensRole, String>()
        val publicIds = runCatching { manager.cameraIdList.toList() }.getOrElse { emptyList() }
        val logicalPhysicalIds = mutableSetOf<String>()
        for (id in publicIds) {
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    logicalPhysicalIds.addAll(chars.physicalCameraIds)
                }
            }
        }
        val candidates = (publicIds + logicalPhysicalIds + listOf("0", "1", "2", "3", "5")).distinct()

        for (id in candidates) {
            try {
                val chars = manager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    continue
                }
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                val isLogical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

                when {
                    facing == CameraCharacteristics.LENS_FACING_FRONT -> {
                        result.putIfAbsent(LensRole.FRONT, id)
                    }
                    isLogical -> {
                        result.putIfAbsent(LensRole.LOGICAL_BACK, id)
                    }
                    focal in 1.2f..3.2f -> {
                        result.putIfAbsent(LensRole.ULTRA_WIDE, id)
                    }
                    focal >= 6.2f -> {
                        result.putIfAbsent(LensRole.TELE_3X, id)
                    }
                    else -> {
                        result.putIfAbsent(LensRole.MAIN, id)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Skipping camera ID $id: ${e.message}")
            }
        }
        return result
    }

    fun cameraIdForOpticalLens(
        lens: OpticalLens,
        isBackCamera: Boolean,
        resolvedIds: Map<LensRole, String> = emptyMap()
    ): String {
        val role = roleForOpticalLens(lens, isBackCamera)
        return resolvedIds[role] ?: if (!isBackCamera) LensRole.FRONT.defaultId else lens.cameraId
    }

    fun querySensorProfile(manager: CameraManager, cameraId: String, role: LensRole): SensorProfile? {
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val supportsRaw = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val defaultSize = rawSizes?.maxByOrNull { it.width * it.height } ?: when (role) {
                LensRole.MAIN -> Size(4096, 3072)
                LensRole.ULTRA_WIDE -> Size(4160, 3120)
                LensRole.TELE_3X -> Size(3648, 2736)
                LensRole.FRONT -> Size(3280, 2460)
                LensRole.LOGICAL_BACK -> Size(4096, 3072)
            }

            val maxResMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            } else null
            val maxRawSize = maxResMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width * it.height }

            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 5.56f
            val wl = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val blPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val blArray = if (blPattern != null) {
                val ints = IntArray(4)
                blPattern.copyTo(ints, 0)
                floatArrayOf(ints[0].toFloat(), ints[1].toFloat(), ints[2].toFloat(), ints[3].toFloat())
            } else {
                floatArrayOf(64f, 64f, 64f, 64f)
            }
            val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR

            SensorProfile(
                cameraId = cameraId,
                role = role,
                supportsRaw = supportsRaw,
                defaultRawSize = defaultSize,
                maxResRawSize = maxRawSize,
                focalLengthMm = focal,
                whiteLevel = wl,
                blackLevelPattern = blArray,
                cfaPattern = cfa
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query sensor profile for $cameraId: ${e.message}")
            null
        }
    }

    /**
     * Enforces the Edge 50 Neo LPDDR4X RAM budget:
     * Uses 12.5MP binned RAW16 (4096x3072 ~= 25MB/slot, ~350MB for 14 slots) at 1.0x,
     * and only switches to 50MP SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION (8192x6144)
     * during 1.5x..2.99x in-sensor zoom or explicit Ultra-Res 50MP mode.
     */
    fun selectRawResolution(
        role: LensRole,
        zoomRatio: Float,
        isUltraRes50M: Boolean,
        isFrontHighRes32Mp: Boolean = false
    ): RawResolutionPlan {
        val useMaxResMain = (role == LensRole.MAIN) &&
            (isUltraRes50M || (zoomRatio >= 1.5f && zoomRatio < 2.99f))
        val useMaxResFront = (role == LensRole.FRONT) && isFrontHighRes32Mp

        return when {
            useMaxResMain -> {
                RawResolutionPlan(8192, 6144, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            useMaxResFront -> {
                RawResolutionPlan(6560, 4920, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            role == LensRole.ULTRA_WIDE -> {
                RawResolutionPlan(4160, 3120, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            }
            role == LensRole.TELE_3X -> {
                RawResolutionPlan(3648, 2736, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            }
            role == LensRole.FRONT -> {
                RawResolutionPlan(3280, 2460, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            }
            else -> {
                RawResolutionPlan(4096, 3072, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            }
        }
    }

    fun roleForOpticalLens(lens: OpticalLens, isBackCamera: Boolean): LensRole {
        if (!isBackCamera) return LensRole.FRONT
        return when (lens) {
            OpticalLens.UW -> LensRole.ULTRA_WIDE
            OpticalLens.TELE_3X -> LensRole.TELE_3X
            OpticalLens.MAIN_1X, OpticalLens.CROP_2X -> LensRole.MAIN
        }
    }

    /**
     * Returns true for fixed-focus sensors (Samsung S5KJD1 front 32MP and GalaxyCore GC13A2
     * when focus distance is zero) where `CONTROL_AF_MODE_OFF` must be forced so captures
     * never stall waiting for an AF convergence state.
     */
    fun isFixedFocusRole(role: LensRole, minFocusDistanceDiopters: Float = 0f): Boolean {
        return role == LensRole.FRONT || minFocusDistanceDiopters <= 0.0f
    }

    /**
     * Configures a Camera2 ZSL RAW16 + Preview session with explicit physical camera routing
     * via [OutputConfiguration.setPhysicalCameraId].
     */
    fun configureZslRawSession(
        device: CameraDevice,
        physicalId: String,
        previewSurface: Surface,
        rawReader: ImageReader,
        pixelMode: Int,
        executor: Executor,
        callback: CameraCaptureSession.StateCallback
    ): Boolean {
        return try {
            val previewOut = OutputConfiguration(previewSurface).apply {
                setPhysicalCameraId(physicalId)
            }
            val rawOut = OutputConfiguration(rawReader.surface).apply {
                setPhysicalCameraId(physicalId)
            }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewOut, rawOut),
                executor,
                callback
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                pixelMode == CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
            ) {
                Log.i(TAG, "Configuring high-res ZSL RAW session on physical ID $physicalId")
            }
            device.createCaptureSession(sessionConfig)
            true
        } catch (e: Exception) {
            Log.e(TAG, "configureZslRawSession failed for physicalId=$physicalId: ${e.message}", e)
            false
        }
    }

    /**
     * Diagnostic probe for Motorola/MediaTek aux camera IDs ("2", "3", "5") hidden from
     * [CameraManager.getCameraIdList] via `vendor.camera.aux.packagelist`.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun probeAuxCameraWritable(manager: CameraManager, cameraId: String, executor: Executor): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opened.set(true)
                    runCatching { camera.close() }
                    latch.countDown()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    latch.countDown()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Aux camera $cameraId open onError=$error")
                    runCatching { camera.close() }
                    latch.countDown()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.openCamera(cameraId, executor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.openCamera(cameraId, callback, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            latch.await(450L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Aux camera $cameraId open threw ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "Aux camera $cameraId writable probe -> ${opened.get()}")
        return opened.get()
    }
}
