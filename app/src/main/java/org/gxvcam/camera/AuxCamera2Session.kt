package org.gxvcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.FlashMode
import org.gxvcam.ui.state.ProHardwareCapabilities
import org.gxvcam.ui.state.ProSettings
import org.gxvcam.ui.state.SonyAeMode
import org.gxvcam.ui.state.SonyAfMode
import org.gxvcam.ui.state.WhiteBalanceMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Direct Camera2 controller for Motorola Edge 50/60 Neo auxiliary physical lenses
 * ("2" Ultra-Wide 0.6x and "3" 3x Optical Telephoto) that are writable via
 * [CameraManager.openCamera] even when hidden from [CameraManager.getCameraIdList].
 */
class AuxCamera2Session(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val cameraExecutor: ExecutorService,
    private val mainHandler: Handler
) {
    companion object {
        private const val TAG = "AuxCamera2Session"
    }

    interface Callbacks {
        fun onPreviewConfigured(cameraId: String, previewSize: Size, sensorOrientation: Int, capabilities: ProHardwareCapabilities)
        fun onCaptureResult(result: TotalCaptureResult)
        fun onError(message: String, cause: Throwable? = null)
    }

    private val stateLock = Any()

    @Volatile var activeCameraId: String? = null
        private set
    @Volatile var previewSize: Size = Size(1920, 1080)
        private set
    @Volatile var sensorOrientation: Int = 90
        private set
    @Volatile var zoomRange: Range<Float> = Range(1.0f, 10.0f)
        private set
    @Volatile var capabilities: ProHardwareCapabilities = ProHardwareCapabilities()
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var callbacks: Callbacks? = null

    private var supportedRequestKeyNames: Set<String> = emptySet()
    private var supportedFpsRanges: List<Range<Int>> = emptyList()
    private var sensorActiveRect: Rect? = null
    private var hasFlash: Boolean = false
    private var hasOis: Boolean = false
    private var isVideoMode: Boolean = false
    private var targetFps: Int = 30
    private var currentZoomRatio: Float = 1.0f
    private var currentFlashMode: FlashMode = FlashMode.OFF
    private var currentManualSettings: CameraManualSettings = CameraManualSettings()
    private var currentProSettings: ProSettings = ProSettings()
    private var currentIsProMode: Boolean = false
    private var isAeLocked: Boolean = false
    private var isAfLocked: Boolean = false
    private var afMeteringRegions: Array<MeteringRectangle>? = null
    private var aeMeteringRegions: Array<MeteringRectangle>? = null

    @Volatile
    private var pendingPhotoCallback: ((ByteArray, Int) -> Unit)? = null
    @Volatile
    private var pendingPhotoError: ((Throwable) -> Unit)? = null
    private var pendingPhotoOrientationDeg: Int = 0

    @Volatile
    private var sessionToken: Int = 0

    @SuppressLint("MissingPermission")
    fun open(
        cameraId: String,
        displaySurface: Surface,
        isVideoMode: Boolean,
        targetFps: Int,
        initialZoom: Float,
        isProMode: Boolean,
        manualSettings: CameraManualSettings,
        proSettings: ProSettings,
        flashMode: FlashMode,
        callbacks: Callbacks
    ) {
        closeSync()
        val token: Int
        synchronized(stateLock) {
            sessionToken++
            token = sessionToken
            this.activeCameraId = cameraId
            this.previewSurface = displaySurface
            this.recordSurface = null
            this.isVideoMode = isVideoMode
            this.targetFps = targetFps
            this.currentZoomRatio = initialZoom
            this.currentIsProMode = isProMode
            this.currentManualSettings = manualSettings
            this.currentProSettings = proSettings
            this.currentFlashMode = flashMode
            this.isAeLocked = false
            this.isAfLocked = false
            this.afMeteringRegions = null
            this.aeMeteringRegions = null
            this.callbacks = callbacks
        }

        cameraExecutor.execute {
            if (token != sessionToken) return@execute
            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                readCharacteristics(chars)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1920, 1080))
                val chosenPreview = selectPreviewSize(previewSizes, isVideoMode)
                previewSize = chosenPreview

                if (!isVideoMode) {
                    val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(4096, 3072))
                    val maxJpeg = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(4096, 3072)
                    jpegReader?.close()
                    jpegReader = ImageReader.newInstance(maxJpeg.width, maxJpeg.height, ImageFormat.JPEG, 2).apply {
                        setOnImageAvailableListener({ reader ->
                            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                            val bytes = try {
                                val buf = image.planes[0].buffer
                                ByteArray(buf.remaining()).also { buf.get(it) }
                            } finally {
                                image.close()
                            }
                            val cb = pendingPhotoCallback
                            pendingPhotoCallback = null
                            pendingPhotoError = null
                            cb?.invoke(bytes, 0)
                        }, mainHandler)
                    }
                } else {
                    jpegReader?.close()
                    jpegReader = null
                }

                fun attemptOpenCamera(retryCount: Int) {
                    if (token != sessionToken) return
                    val deviceCallback = object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (token != sessionToken) {
                                runCatching { camera.close() }
                                return
                            }
                            synchronized(stateLock) {
                                cameraDevice = camera
                            }
                            createSessionInternal(token)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            runCatching { camera.close() }
                            synchronized(stateLock) {
                                if (cameraDevice === camera) cameraDevice = null
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            runCatching { camera.close() }
                            synchronized(stateLock) {
                                if (cameraDevice === camera) cameraDevice = null
                            }
                            val transientBusy = error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                                error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                                error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
                            if (transientBusy && retryCount < 4 && token == sessionToken) {
                                Log.w(TAG, "Aux camera $cameraId open error $error (attempt ${retryCount + 1}), retrying in 180ms")
                                mainHandler.postDelayed({
                                    cameraExecutor.execute { attemptOpenCamera(retryCount + 1) }
                                }, 180L)
                            } else {
                                callbacks.onError("Aux camera $cameraId open error $error")
                            }
                        }
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            cameraManager.openCamera(cameraId, cameraExecutor, deviceCallback)
                        } else {
                            @Suppress("DEPRECATION")
                            cameraManager.openCamera(cameraId, deviceCallback, mainHandler)
                        }
                    } catch (openErr: Exception) {
                        if (retryCount < 4 && token == sessionToken) {
                            Log.w(TAG, "Aux camera $cameraId open threw ${openErr.message}, retrying in 180ms")
                            mainHandler.postDelayed({
                                cameraExecutor.execute { attemptOpenCamera(retryCount + 1) }
                            }, 180L)
                        } else {
                            callbacks.onError("Failed opening aux camera $cameraId: ${openErr.message}", openErr)
                        }
                    }
                }
                attemptOpenCamera(0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed opening aux camera $cameraId: ${e.message}", e)
                callbacks.onError("Failed opening aux camera $cameraId: ${e.message}", e)
            }
        }
    }

    private fun readCharacteristics(chars: CameraCharacteristics) {
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        sensorActiveRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        hasOis = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        supportedRequestKeyNames = chars.availableCaptureRequestKeys.map { it.name }.toSet()
        supportedFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
            ?: listOf(Range(24, 30))

        zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1.0f, 10.0f)
        } else {
            val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
            Range(1.0f, maxDigital.coerceAtLeast(1.0f))
        }

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val isRaw = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

        capabilities = ProHardwareCapabilities(
            isoRange = if (isoRange != null) isoRange.lower..isoRange.upper else 50..3200,
            exposureTimeRangeNs = if (exposureRange != null) exposureRange.lower..exposureRange.upper else 125_000L..1_000_000_000L,
            evRange = if (evRange != null) evRange.lower..evRange.upper else -12..12,
            evStep = evStep,
            minFocusDistanceDiopters = minFocusDist,
            isRawSupported = isRaw
        )
    }

    private fun selectPreviewSize(sizes: Array<Size>, isVideo: Boolean): Size {
        val targetAspect = if (isVideo) 16f / 9f else 4f / 3f
        val filtered = sizes.filter { it.width in 960..1920 && it.height in 720..1440 }
        val pool = if (filtered.isNotEmpty()) filtered else sizes.toList()
        return pool.minByOrNull { s ->
            val ratio = s.width.toFloat() / s.height.coerceAtLeast(1)
            val aspectPenalty = abs(ratio - targetAspect) * 10f
            val resScore = -(s.width * s.height) / 1_000_000f
            aspectPenalty + resScore * 0.2f
        } ?: Size(1920, 1080)
    }

    private fun createSessionInternal(token: Int, onConfigured: (() -> Unit)? = null) {
        val device = synchronized(stateLock) { cameraDevice } ?: return
        val prevSurf = synchronized(stateLock) { previewSurface } ?: return
        val recSurf = synchronized(stateLock) { recordSurface }
        val readerSurf = synchronized(stateLock) { jpegReader?.surface }

        val surfaces = buildList {
            if (prevSurf.isValid) add(prevSurf)
            if (recSurf != null && recSurf.isValid) add(recSurf)
            if (readerSurf != null && readerSurf.isValid) add(readerSurf)
        }
        if (surfaces.isEmpty()) return

        try {
            runCatching { synchronized(stateLock) { captureSession?.close(); captureSession = null } }
            val sessionCb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (token != sessionToken) {
                        runCatching { session.close() }
                        return
                    }
                    synchronized(stateLock) { captureSession = session }
                    val camId = activeCameraId ?: return
                    callbacks?.onPreviewConfigured(camId, previewSize, sensorOrientation, capabilities)
                    updateRepeatingInternal()
                    onConfigured?.invoke()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runCatching { session.close() }
                    callbacks?.onError("Aux camera $activeCameraId session configure failed")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val configs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    configs,
                    cameraExecutor,
                    sessionCb
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, sessionCb, mainHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSessionInternal failed: ${e.message}", e)
            callbacks?.onError("createSession failed: ${e.message}", e)
        }
    }

    fun reconfigureForRecording(recordingSurface: Surface?, onReady: () -> Unit) {
        val token = sessionToken
        synchronized(stateLock) {
            this.recordSurface = recordingSurface
        }
        cameraExecutor.execute {
            if (token != sessionToken) return@execute
            createSessionInternal(token, onConfigured = onReady)
        }
    }

    fun setZoomRatio(sensorZoom: Float) {
        currentZoomRatio = sensorZoom.coerceIn(zoomRange.lower, zoomRange.upper)
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setFlashMode(flashMode: FlashMode) {
        currentFlashMode = flashMode
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setManualSettings(settings: CameraManualSettings) {
        currentManualSettings = settings
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setProSettings(settings: ProSettings, isProMode: Boolean, isVideoMode: Boolean) {
        currentProSettings = settings
        currentIsProMode = isProMode
        this.isVideoMode = isVideoMode
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun triggerTapToFocus(normX: Float, normY: Float, aeLocked: Boolean, afLocked: Boolean) {
        val rect = sensorActiveRect ?: return
        val x = (normY.coerceIn(0f, 1f) * rect.width()).toInt()
        val y = ((1f - normX.coerceIn(0f, 1f)) * rect.height()).toInt()
        val halfW = (rect.width() * 0.08f).toInt().coerceAtLeast(32)
        val halfH = (rect.height() * 0.08f).toInt().coerceAtLeast(32)
        val meterRect = Rect(
            (x - halfW).coerceIn(0, rect.width() - 1),
            (y - halfH).coerceIn(0, rect.height() - 1),
            (x + halfW).coerceIn(1, rect.width()),
            (y + halfH).coerceIn(1, rect.height())
        )
        val mr = arrayOf(MeteringRectangle(meterRect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        if (!afLocked && capabilities.minFocusDistanceDiopters > 0f) {
            afMeteringRegions = mr
        }
        if (!aeLocked) {
            aeMeteringRegions = mr
        }
        isAeLocked = aeLocked
        isAfLocked = afLocked
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setAeLock(locked: Boolean) {
        isAeLocked = locked
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun unlockAf() {
        isAfLocked = false
        afMeteringRegions = null
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setAfMode(mode: SonyAfMode, locked: Boolean) {
        isAfLocked = locked || mode == SonyAfMode.AF_L
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun setAeMode(mode: SonyAeMode, locked: Boolean) {
        isAeLocked = locked || mode == SonyAeMode.AE_L
        cameraExecutor.execute { updateRepeatingInternal() }
    }

    fun captureStillPhoto(
        deviceOrientationAngle: Int,
        onCaptured: (jpegBytes: ByteArray, rotationDegrees: Int) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        pendingPhotoOrientationDeg = deviceOrientationAngle
        pendingPhotoCallback = onCaptured
        pendingPhotoError = onError

        cameraExecutor.execute {
            val device = synchronized(stateLock) { cameraDevice }
            val session = synchronized(stateLock) { captureSession }
            val readerSurf = synchronized(stateLock) { jpegReader?.surface }
            if (device == null || session == null || readerSurf == null || !readerSurf.isValid) {
                val cb = pendingPhotoError
                pendingPhotoCallback = null
                pendingPhotoError = null
                cb?.invoke(IllegalStateException("Aux camera not ready for capture"))
                return@execute
            }

            try {
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerSurf)
                    applyCommonControls(this, isStillCapture = true)
                    val jpegRot = (sensorOrientation + pendingPhotoOrientationDeg) % 360
                    set(CaptureRequest.JPEG_ORIENTATION, jpegRot)
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                }.build()
                session.capture(req, object : CameraCaptureSession.CaptureCallback() {}, mainHandler)
            } catch (e: Exception) {
                val cb = pendingPhotoError
                pendingPhotoCallback = null
                pendingPhotoError = null
                cb?.invoke(e)
            }
        }
    }

    private fun updateRepeatingInternal() {
        val device = synchronized(stateLock) { cameraDevice } ?: return
        val session = synchronized(stateLock) { captureSession } ?: return
        val prevSurf = synchronized(stateLock) { previewSurface } ?: return
        if (!prevSurf.isValid) return
        val recSurf = synchronized(stateLock) { recordSurface }

        try {
            val template = if (isVideoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = device.createCaptureRequest(template).apply {
                addTarget(prevSurf)
                if (recSurf != null && recSurf.isValid) {
                    addTarget(recSurf)
                }
                applyCommonControls(this, isStillCapture = false)
            }
            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    callbacks?.onCaptureResult(result)
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.d(TAG, "updateRepeatingInternal ignored: ${e.message}")
        }
    }

    private fun applyCommonControls(builder: CaptureRequest.Builder, isStillCapture: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                currentZoomRatio.coerceIn(zoomRange.lower, zoomRange.upper)
            )
        }

        val oisMode = if (hasOis) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (isVideoMode) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

        if (isVideoMode) {
            val bestRange = supportedFpsRanges
                .filter { it.upper <= targetFps }
                .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
                ?: supportedFpsRanges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
                ?: Range(24, 30)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
        }

        val abMode = when (currentManualSettings.antiBandingMode) {
            0 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            1 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            2 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, abMode)

        if (hasFlash && currentFlashMode == FlashMode.TORCH) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        } else if (hasFlash && isStillCapture && currentFlashMode == FlashMode.ON) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        val pro = currentProSettings
        if (currentIsProMode && pro.isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                pro.iso.coerceIn(capabilities.isoRange.first, capabilities.isoRange.last)
            )
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                pro.shutterSpeedNs.coerceIn(capabilities.exposureTimeRangeNs.first, capabilities.exposureTimeRangeNs.last)
            )
        } else {
            val aeMode = when {
                hasFlash && currentFlashMode == FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                hasFlash && currentFlashMode == FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CaptureRequest.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
            if (currentIsProMode) {
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    pro.evIndex.coerceIn(capabilities.evRange.first, capabilities.evRange.last)
                )
            }
        }

        if (currentIsProMode && pro.whiteBalanceMode == WhiteBalanceMode.CUSTOM) {
            val k = pro.whiteBalanceKelvin.coerceIn(2000, 10000)
            val rawRGain = (1.0f + (k - 2000f) / 8000f * 1.5f).coerceIn(0.8f, 3.5f)
            val rawBGain = (3.0f - (k - 2000f) / 8000f * 2.0f).coerceIn(0.8f, 3.5f)
            val guarded = CieUvAwbGuard.guardGains(rawRGain, 1.0f, 1.0f, rawBGain, hasFace = false)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, guarded.toRggbChannelVector())
        } else {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                if (currentIsProMode) pro.whiteBalanceMode.awbMode else CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
        }

        val fixedFocus = capabilities.minFocusDistanceDiopters <= 0f
        if (fixedFocus || (currentIsProMode && pro.isManualFocus)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            if (!fixedFocus && currentIsProMode && pro.isManualFocus) {
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    pro.focusDistanceDiopters.coerceIn(0f, capabilities.minFocusDistanceDiopters)
                )
            }
        } else if (isAfLocked) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (isVideoMode) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        afMeteringRegions?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
        aeMeteringRegions?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
    }

    fun closeSync() {
        val deviceToClose: CameraDevice?
        val sessionToClose: CameraCaptureSession?
        val readerToClose: ImageReader?
        synchronized(stateLock) {
            sessionToken++
            activeCameraId = null
            sessionToClose = captureSession
            captureSession = null
            deviceToClose = cameraDevice
            cameraDevice = null
            readerToClose = jpegReader
            jpegReader = null
            previewSurface = null
            recordSurface = null
            callbacks = null
        }
        runCatching { sessionToClose?.close() }
        runCatching { readerToClose?.close() }
        if (deviceToClose != null) {
            val latch = CountDownLatch(1)
            cameraExecutor.execute {
                runCatching { deviceToClose.close() }
                latch.countDown()
            }
            runCatching { latch.await(450L, TimeUnit.MILLISECONDS) }
        }
    }
}
