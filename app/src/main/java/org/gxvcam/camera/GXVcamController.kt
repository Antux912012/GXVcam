package org.gxvcam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.gxvcam.ui.state.AgcColorProfile
import org.gxvcam.ui.state.AgcLibPatcherSettings
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.CameraMode
import org.gxvcam.ui.state.FlashMode
import org.gxvcam.ui.state.GXVcamEvent
import org.gxvcam.ui.state.HistogramData
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.OpticalLens
import org.gxvcam.ui.state.ProHardwareCapabilities
import org.gxvcam.ui.state.ProSettings
import org.gxvcam.ui.state.SonyAeMode
import org.gxvcam.ui.state.SonyAfMode
import org.gxvcam.ui.state.VideoResolution
import org.gxvcam.ui.state.WhiteBalanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GXVcamController(
    private val context: Context,
    private var lifecycleOwner: LifecycleOwner? = null,
    private var onEvent: ((GXVcamEvent) -> Unit)? = null,
) {

    var currentManualSettings: CameraManualSettings = CameraManualSettings()
        private set
    var currentAgcSettings: AgcLibPatcherSettings = AgcLibPatcherSettings()
        private set
    var currentActiveLens: OpticalLens = OpticalLens.MAIN_1X
        private set
    var currentCameraMode: CameraMode = CameraMode.PHOTO
        private set
    var portraitAperture: Float = 2.8f
    private var supportedRequestKeyNames: Set<String> = emptySet()

    /** Last parameters used in [bindCamera], reused when a lens switch requires a sensor rebind. */
    private data class BindConfig(
        val isBackCamera: Boolean,
        val aspectRatio: AspectRatio,
        val enableHistogram: Boolean,
        val enableRawJpeg: Boolean,
        val isVideoMode: Boolean,
        val cameraMode: CameraMode,
        val isDualCapture: Boolean = false,
        val isDualCaptureSwapped: Boolean = false,
    )

    private data class ResolvedCamera(
        val selector: CameraSelector,
        val cameraId: String?,
        val isPhysicalLens: Boolean,
    )

    private var lastBindConfig: BindConfig? = null
    private var boundCameraId: String? = null

    /** True when bound directly to the physical sensor of a lens (0, 2 or 3). */
    private var isPhysicalLensBinding = false

    /** Equivalent zoom of the currently bound sensor at its native 1.0 ratio. */
    private var boundSensorBaseZoom = 1.0f

    companion object {
        private const val TAG = "GXVcamController"

        /** Front sensor (ID 4 is a secure-only LIMITED camera and must be avoided). */
        private const val FRONT_CAMERA_ID = "1"

        /** Logical multi-camera (physical IDs 0, 2, 3), zoom range 0.5x..30x. */
        private const val LOGICAL_BACK_CAMERA_ID = "5"
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    val secondaryPreviewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private var cameraProvider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set
    var secondaryCamera: Camera? = null
        private set
    var concurrentCamera: ConcurrentCamera? = null
        private set
    var isConcurrentCameraActive: Boolean = false
        private set

    var preview: Preview? = null
        private set
    var secondaryPreview: Preview? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var imageAnalysis: ImageAnalysis? = null
        private set

    private var activeRecording: Recording? = null
    private var lastWasDualCapture: Boolean = false
    private val inFlightCaptures = java.util.concurrent.atomic.AtomicInteger(0)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBindRunnable: Runnable? = null

    var deviceSurfaceRotation: Int = Surface.ROTATION_0
        private set
    var deviceOrientationAngle: Int = 0
        private set
    private var orientationEventListener: OrientationEventListener? = null
    var currentVideoSettings: MotoVideoSettings = MotoVideoSettings()
        private set

    var onCapabilitiesReady: ((ProHardwareCapabilities) -> Unit)? = null
    var onHistogramReady: ((HistogramData) -> Unit)? = null
    var hardwareCapabilities: ProHardwareCapabilities = ProHardwareCapabilities()
        private set

    init {
        updateVideoCapture(currentVideoSettings)

        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val surfaceRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                val cardinalAngle = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                updateDeviceOrientation(surfaceRotation, cardinalAngle)
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    fun updateDeviceOrientation(surfaceRotation: Int, cardinalAngle: Int) {
        this.deviceSurfaceRotation = surfaceRotation
        this.deviceOrientationAngle = cardinalAngle
        try {
            imageCapture?.targetRotation = surfaceRotation
            videoCapture?.targetRotation = surfaceRotation
        } catch (e: Exception) {
            Log.d(TAG, "targetRotation update ignored: ${e.message}")
        }
    }

    fun updateVideoCapture(settings: MotoVideoSettings = currentVideoSettings) {
        this.currentVideoSettings = settings
        // Fallback dual capture concurrent recording to 1080p FHD to stay within MediaTek Dimensity 7300 HAL encoder limits
        val effectiveRes = if (settings.isDualCaptureEnabled && settings.resolution == VideoResolution.UHD_4K) {
            VideoResolution.FHD_1080P
        } else {
            settings.resolution
        }
        val quality = when (effectiveRes) {
            VideoResolution.UHD_4K -> Quality.UHD
            VideoResolution.FHD_1080P -> Quality.FHD
            VideoResolution.HD_720P -> Quality.HD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    quality,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
            )
            .setExecutor(cameraExecutor)
            .build()
        videoCapture = VideoCapture.Builder(recorder)
            .setTargetRotation(deviceSurfaceRotation)
            .build()
    }

    fun setLifecycleOwner(owner: LifecycleOwner) {
        this.lifecycleOwner = owner
    }

    fun setOnEventListener(listener: (GXVcamEvent) -> Unit) {
        this.onEvent = listener
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        isBackCamera: Boolean = true,
        aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
        enableHistogram: Boolean = false,
        enableRawJpeg: Boolean = false,
        isVideoMode: Boolean = false,
        activeLens: OpticalLens = OpticalLens.MAIN_1X,
        cameraMode: CameraMode = CameraMode.PHOTO,
        displayZoom: Float = activeLens.zoomRatio,
        isDualCapture: Boolean = (isVideoMode || cameraMode == CameraMode.PHOTO) && currentVideoSettings.isDualCaptureEnabled,
        isDualCaptureSwapped: Boolean = currentVideoSettings.isDualCaptureSwapped,
    ) {
        this.currentActiveLens = activeLens
        this.currentCameraMode = cameraMode
        this.lastBindConfig = BindConfig(
            isBackCamera = isBackCamera,
            aspectRatio = aspectRatio,
            enableHistogram = enableHistogram,
            enableRawJpeg = enableRawJpeg,
            isVideoMode = isVideoMode,
            cameraMode = cameraMode,
            isDualCapture = isDualCapture,
            isDualCaptureSwapped = isDualCaptureSwapped,
        )
        val owner = lifecycleOwner ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                val provider = cameraProvider ?: return@addListener

                val wasDual = lastWasDualCapture
                val isDual = isDualCapture
                this.lastWasDualCapture = isDual
                val needsTransitionDelay = (wasDual != isDual)

                provider.unbindAll()
                isConcurrentCameraActive = false
                secondaryCamera = null
                secondaryPreview = null
                concurrentCamera = null
                camera = null

                pendingBindRunnable?.let { mainHandler.removeCallbacks(it) }

                val targetRatio = if (isVideoMode) {
                    androidx.camera.core.AspectRatio.RATIO_16_9
                } else {
                    when (aspectRatio) {
                        AspectRatio.RATIO_16_9,
                        AspectRatio.RATIO_21_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
                        AspectRatio.RATIO_4_3,
                        AspectRatio.RATIO_1_1,
                        AspectRatio.RATIO_3_2 -> androidx.camera.core.AspectRatio.RATIO_4_3
                    }
                }

                val bindAction = Runnable {
                    val activeProvider = cameraProvider ?: return@Runnable
                    try {
                        // Motorola Edge 50 Neo Dual Capture Concurrent Camera (Rear + Front in both Photo & Video modes)
                        if (isDualCapture) {
                            try {
                                val concurrentCombos = activeProvider.availableConcurrentCameraInfos
                                Log.i(TAG, "Concurrent combos from CameraX: ${concurrentCombos.map { list -> list.map { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() } }}")

                                val combo = concurrentCombos.firstOrNull() ?: emptyList()
                                val backInfo = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                                val frontInfo = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }

                                val rearSelector = backInfo?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
                                val frontSelector = frontInfo?.cameraSelector ?: CameraSelector.DEFAULT_FRONT_CAMERA

                                val (firstSelector, secondSelector) = if (!isDualCaptureSwapped) {
                                    rearSelector to frontSelector
                                } else {
                                    frontSelector to rearSelector
                                }

                                val primaryPreview = Preview.Builder()
                                    .setTargetAspectRatio(targetRatio)
                                    .setTargetRotation(deviceSurfaceRotation)
                                    .build().apply {
                                        setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                val secPreview = Preview.Builder()
                                    .setTargetAspectRatio(targetRatio)
                                    .setTargetRotation(deviceSurfaceRotation)
                                    .build().apply {
                                        setSurfaceProvider(secondaryPreviewView.surfaceProvider)
                                    }

                                this.preview = primaryPreview
                                this.secondaryPreview = secPreview

                                val primaryGroupBuilder = UseCaseGroup.Builder().addUseCase(primaryPreview)
                                if (isVideoMode) {
                                    updateVideoCapture(currentVideoSettings)
                                    videoCapture?.targetRotation = deviceSurfaceRotation
                                    videoCapture?.let { primaryGroupBuilder.addUseCase(it) }
                                    imageCapture = null
                                    imageAnalysis = null
                                } else {
                                    val imgCap = ImageCapture.Builder()
                                        .setTargetAspectRatio(targetRatio)
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                        .setTargetRotation(deviceSurfaceRotation)
                                        .build()
                                    this.imageCapture = imgCap
                                    primaryGroupBuilder.addUseCase(imgCap)
                                    imageAnalysis = null
                                }

                                val primaryGroup = primaryGroupBuilder.build()
                                val secondaryGroup = UseCaseGroup.Builder()
                                    .addUseCase(secPreview)
                                    .build()

                                val firstConfig = ConcurrentCamera.SingleCameraConfig(firstSelector, primaryGroup, owner)
                                val secondConfig = ConcurrentCamera.SingleCameraConfig(secondSelector, secondaryGroup, owner)

                                val concurrentCam = activeProvider.bindToLifecycle(listOf(firstConfig, secondConfig))
                                camera = concurrentCam.cameras.firstOrNull()
                                secondaryCamera = concurrentCam.cameras.getOrNull(1)
                                concurrentCamera = concurrentCam
                                isConcurrentCameraActive = true

                                applyMotoVideoSettings(currentVideoSettings, zoomRatio = activeLens.zoomRatio)
                                readHardwareCapabilities()
                                return@Runnable
                            } catch (e: Exception) {
                                Log.w(TAG, "Concurrent camera binding failed, falling back to single camera: ${e.message}", e)
                                isConcurrentCameraActive = false
                                secondaryCamera = null
                                secondaryPreview = null
                                concurrentCamera = null
                            }
                        }

                        val resolved = resolveCamera(activeProvider, isBackCamera, activeLens)
                        val baseSelector = resolved.selector

                        // Night Vision extension if requested and available on this lens
                        val selector = if ((cameraMode == CameraMode.NIGHT) && !isVideoMode) {
                            val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(context, activeProvider)
                            val extensionsManager = try {
                                extensionsManagerFuture.get(400, TimeUnit.MILLISECONDS)
                            } catch (_: Exception) {
                                null
                            }
                            if (extensionsManager != null && extensionsManager.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)) {
                                Log.i(TAG, "Enabling OEM Night Vision Extension")
                                extensionsManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                            } else {
                                baseSelector
                            }
                        } else {
                            baseSelector
                        }

                        preview = Preview.Builder()
                            .setTargetAspectRatio(targetRatio)
                            .build().apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }

                        val useCases = mutableListOf<UseCase>(preview!!)

                        if (isVideoMode) {
                            updateVideoCapture(currentVideoSettings)
                            videoCapture?.targetRotation = deviceSurfaceRotation
                            videoCapture?.let { useCases.add(it) }
                            imageCapture = null
                            imageAnalysis = null
                        } else {
                            val imageCaptureBuilder = ImageCapture.Builder()
                                .setTargetAspectRatio(targetRatio)
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setTargetRotation(deviceSurfaceRotation)
                            imageCapture = imageCaptureBuilder.build()
                            useCases.add(imageCapture!!)

                            if (enableHistogram) {
                                imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                    .build().apply {
                                        setAnalyzer(cameraExecutor, HistogramAnalyzer { histogram ->
                                            onHistogramReady?.invoke(histogram)
                                            onEvent?.invoke(GXVcamEvent.UpdateHistogram(histogram))
                                        })
                                    }
                                useCases.add(imageAnalysis!!)
                            } else {
                                imageAnalysis = null
                            }
                        }

                        camera = activeProvider.bindToLifecycle(
                            owner,
                            selector,
                            *useCases.toTypedArray()
                        )
                        boundCameraId = resolved.cameraId
                        isPhysicalLensBinding = resolved.isPhysicalLens
                        boundSensorBaseZoom = if (resolved.isPhysicalLens) activeLens.sensorBaseZoom else 1.0f

                        readHardwareCapabilities()
                        applyStabilization(isVideoMode)
                        applyLensConfiguration(activeLens, displayZoom)
                        applyManualSettings(currentManualSettings)
                    } catch (e: Exception) {
                        Log.e(TAG, "Use case binding failed: ${e.message}", e)
                    }
                }

                pendingBindRunnable = bindAction
                if (needsTransitionDelay) {
                    Log.i(TAG, "Transitioning camera mode (dual=$isDual, wasDual=$wasDual): pausing 250ms for HAL cleanup")
                    mainHandler.postDelayed(bindAction, 250L)
                } else {
                    bindAction.run()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider initialization failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Resolves the CameraX selector for the requested lens.
     *
     * `DEFAULT_BACK_CAMERA` resolves to physical ID 0, whose zoom range is 1.0..10.0, so any
     * sub-1x zoom request was silently clamped and the ultra-wide (ID 2) was never used.
     * Here we bind the physical sensor directly (like the original Camera2 pipeline did),
     * falling back to the logical multi-camera (ID 5, 0.5..30x) and then to the default.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun resolveCamera(
        provider: ProcessCameraProvider,
        isBackCamera: Boolean,
        lens: OpticalLens,
    ): ResolvedCamera {
        val availableIds = provider.availableCameraInfos.mapNotNull { info ->
            runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
        }.toSet()
        Log.i(TAG, "CameraX visible camera IDs: $availableIds")

        if (!isBackCamera) {
            return if (FRONT_CAMERA_ID in availableIds) {
                ResolvedCamera(selectorForCameraId(FRONT_CAMERA_ID), FRONT_CAMERA_ID, isPhysicalLens = false)
            } else {
                ResolvedCamera(CameraSelector.DEFAULT_FRONT_CAMERA, null, isPhysicalLens = false)
            }
        }

        // On Motorola Edge 50 Neo (MediaTek Dimensity 7300), LOGICAL_BACK_CAMERA_ID ("5")
        // integrates all 3 physical sensors (0.5x..30.0x) with native hardware Spatial Alignment & Transition (SAT).
        // Binding this logical multi-camera enables continuous, smooth zoom (1.1x, 1.2x... up to 20x)
        // with hardware sensor handoff and zero viewfinder freeze or flicker.
        if (LOGICAL_BACK_CAMERA_ID in availableIds) {
            Log.i(TAG, "Using logical multi-camera $LOGICAL_BACK_CAMERA_ID (seamless 0.5x-30x SAT)")
            return ResolvedCamera(selectorForCameraId(LOGICAL_BACK_CAMERA_ID), LOGICAL_BACK_CAMERA_ID, isPhysicalLens = false)
        }

        if (lens.cameraId in availableIds) {
            return ResolvedCamera(selectorForCameraId(lens.cameraId), lens.cameraId, isPhysicalLens = true)
        }

        Log.w(TAG, "Neither logical camera nor camera ${lens.cameraId} exposed; using default back camera")
        return ResolvedCamera(CameraSelector.DEFAULT_BACK_CAMERA, null, isPhysicalLens = false)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorForCameraId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }
            .build()

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readHardwareCapabilities() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            Log.i(TAG, "Successfully bound to camera ID: ${camera2Info.cameraId}")

            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val chars = cameraManager.getCameraCharacteristics(camera2Info.cameraId)
                supportedRequestKeyNames = chars.availableCaptureRequestKeys.map { it.name }.toSet()
            } catch (e: Exception) {
                Log.d(TAG, "Could not query available capture request keys: ${e.message}")
            }

            val isoRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocusDist = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?: 10.0f
            val evRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evStepVal = camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val evStep = evStepVal?.toFloat() ?: 0.33f

            val availableCaps = camera2Info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isRawSupported = availableCaps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

            val capabilities = ProHardwareCapabilities(
                isoRange = if (isoRange != null) isoRange.lower..isoRange.upper else 50..6400,
                exposureTimeRangeNs = if (exposureRange != null) exposureRange.lower..exposureRange.upper else 125_000L..32_000_000_000L,
                evRange = if (evRange != null) evRange.lower..evRange.upper else -12..12,
                evStep = evStep,
                minFocusDistanceDiopters = minFocusDist,
                isRawSupported = isRawSupported
            )
            this.hardwareCapabilities = capabilities

            onCapabilitiesReady?.invoke(capabilities)
            onEvent?.invoke(GXVcamEvent.UpdateHardwareCapabilities(capabilities))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Camera2 capabilities: ${e.message}")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T : Any> CaptureRequestOptions.Builder.setIfSupported(key: CaptureRequest.Key<T>?, value: T) {
        if (key != null && (supportedRequestKeyNames.isEmpty() || supportedRequestKeyNames.contains(key.name))) {
            try {
                setCaptureRequestOption(key, value)
            } catch (_: IllegalArgumentException) {
                // Key not supported by current Camera2 HAL
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualSettings(settings: CameraManualSettings) {
        this.currentManualSettings = settings
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()

            // 50MP Quad-Bayer remosaic toggle on supported sensors (Sony IMX896)
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_DO_REMOSAIC,
                if (settings.isUltraRes50M) 1 else 0
            )

            // Motorola ADRC (Auto Dynamic Range Control)
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_ADRC_ENABLE,
                if (settings.isAdrcEnabled) 1 else 0
            )

            // Motorola Tintless lens shading correction
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_TINTLESS_ENABLE,
                if (settings.isTintlessEnabled) 1 else 0
            )

            // ZSL (Zero Shutter Lag) modes
            val zslVal = if (settings.isZslEnabled) 1 else 0
            optionsBuilder.setIfSupported(MtkMotoVendorTags.MOTO_ZSL_MODE, zslVal)
            optionsBuilder.setIfSupported(MtkMotoVendorTags.MTK_ZSL_MODE, zslVal)

            // Motorola SuperNight mode
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_SUPERNIGHT,
                if (settings.isSuperNightEnabled) 1 else 0
            )

            // Anti-banding mode (0=OFF, 1=50Hz, 2=60Hz, 3=AUTO)
            val abMode = when (settings.antiBandingMode) {
                0 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
                1 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                2 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            }
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, abMode)

            control2.addCaptureRequestOptions(optionsBuilder.build())
            Log.d(TAG, "Applied manual settings: 50MP=${settings.isUltraRes50M}, ADRC=${settings.isAdrcEnabled}, ZSL=${settings.isZslEnabled}")
        } catch (e: Exception) {
            Log.w(TAG, "Error applying manual settings: ${e.message}")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyProSettings(settings: ProSettings, isVideoMode: Boolean = false) {
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()

            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (isVideoMode) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )

            // High quality tonemap pipeline
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.TONEMAP_MODE,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            )

            // White Balance
            when (settings.whiteBalanceMode) {
                WhiteBalanceMode.CUSTOM -> {
                    val k = settings.whiteBalanceKelvin.coerceIn(2000, 10000)
                    val rGain = (1.0f + (k - 2000f) / 8000f * 1.5f).coerceIn(0.8f, 3.5f)
                    val bGain = (3.0f - (k - 2000f) / 8000f * 2.0f).coerceIn(0.8f, 3.5f)
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_OFF
                    )
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_MODE,
                        CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                    )
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_GAINS,
                        RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
                    )
                }
                else -> {
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE,
                        settings.whiteBalanceMode.awbMode
                    )
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_MODE,
                        CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                    )
                }
            }

            // Motorola Tintless (lens shading) & ADRC (if supported by HAL)
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_TINTLESS_ENABLE,
                if (currentManualSettings.isTintlessEnabled) 1 else 0
            )
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_ADRC_ENABLE,
                if (currentManualSettings.isAdrcEnabled) 1 else 0
            )

            if (settings.isManualExposure) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    settings.iso
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    settings.shutterSpeedNs
                )
            } else {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
                cam.cameraControl.setExposureCompensationIndex(settings.evIndex)
            }

            if (settings.isManualFocus) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.focusDistanceDiopters
                )
            } else {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            control2.addCaptureRequestOptions(optionsBuilder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Error applying pro settings: ${e.message}")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyAgcSettings(agc: AgcLibPatcherSettings, activeLens: OpticalLens = OpticalLens.MAIN_1X) {
        this.currentAgcSettings = agc
        this.currentActiveLens = activeLens
        updateLiveViewfinderLut(agc.isLiveViewfinderLutEnabled, agc.activeProfile, activeLens, agc)
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val options = AgcOptimizationEngine.buildAgcRequestOptions(agc, activeLens, supportedRequestKeyNames)
            control2.addCaptureRequestOptions(options)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying AGC settings: ${e.message}")
        }
    }

    fun updateLiveViewfinderLut(
        enabled: Boolean = currentAgcSettings.isLiveViewfinderLutEnabled,
        profile: AgcColorProfile = currentAgcSettings.activeProfile,
        lens: OpticalLens = currentActiveLens,
        customSettings: AgcLibPatcherSettings? = currentAgcSettings
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    if (enabled) {
                        val colorFilter = GXVImageProcessor.buildSensorColorFilter(
                            profile = profile,
                            activeLens = lens,
                            customSettings = customSettings,
                            forLiveViewfinder = true
                        )
                        previewView.setRenderEffect(RenderEffect.createColorFilterEffect(colorFilter))
                    } else {
                        previewView.setRenderEffect(null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply live viewfinder RenderEffect: ${e.message}")
                }
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyMotoVideoSettings(settings: MotoVideoSettings, zoomRatio: Float = 1.0f, rollDegrees: Float = 0f) {
        val previousResolution = currentVideoSettings.resolution
        val previousDualCapture = currentVideoSettings.isDualCaptureEnabled
        val previousDualCaptureSwapped = currentVideoSettings.isDualCaptureSwapped
        this.currentVideoSettings = settings

        val needsRebind = (settings.resolution != previousResolution && activeRecording == null) ||
                (settings.isDualCaptureEnabled != previousDualCapture) ||
                (settings.isDualCaptureSwapped != previousDualCaptureSwapped)

        if (needsRebind) {
            if (settings.resolution != previousResolution || settings.isDualCaptureEnabled != previousDualCapture) {
                updateVideoCapture(settings)
            }
            lastBindConfig?.let { cfg ->
                bindCamera(
                    isBackCamera = cfg.isBackCamera,
                    aspectRatio = cfg.aspectRatio,
                    enableHistogram = cfg.enableHistogram,
                    enableRawJpeg = cfg.enableRawJpeg,
                    isVideoMode = cfg.isVideoMode,
                    activeLens = currentActiveLens,
                    cameraMode = cfg.cameraMode,
                    displayZoom = currentActiveLens.zoomRatio,
                    isDualCapture = settings.isDualCaptureEnabled,
                    isDualCaptureSwapped = settings.isDualCaptureSwapped,
                )
            }
        }

        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            val hasOis = (currentActiveLens != OpticalLens.UW)
            MotoVideoEngine.applyVideoInterop(builder, settings, zoomRatio, rollDegrees, supportedRequestKeyNames, hasOis)
            control2.addCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Error applying Moto video settings: ${e.message}")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyStabilization(isVideoMode: Boolean = false) {
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val oisMode = if (lastBindConfig?.isBackCamera == true && currentActiveLens != OpticalLens.UW) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    oisMode
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (isVideoMode) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
                .setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                )
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                )

            builder.setIfSupported(MtkMotoVendorTags.MOTO_TINTLESS_ENABLE, 1)
            builder.setIfSupported(MtkMotoVendorTags.MOTO_ADRC_ENABLE, 1)

            control2.addCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.d(TAG, "Stabilization tag not applied: ${e.message}")
        }
    }

    /**
     * Applies an equivalent (main-lens relative) zoom ratio, converting it to the zoom ratio
     * of the currently bound physical sensor (e.g. 0.6x on the UW sensor => 1.0 sensor zoom).
     */
    fun setZoom(displayZoom: Float) {
        val cam = camera ?: return
        this.currentActiveLens = OpticalLens.forZoom(displayZoom)
        val sensorZoom = displayZoom / boundSensorBaseZoom
        val zoomState = cam.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 0.5f
        val maxZoom = zoomState?.maxZoomRatio ?: 20.0f
        cam.cameraControl.setZoomRatio(sensorZoom.coerceIn(minZoom, maxZoom))
    }

    /**
     * Switches to [lens]. When the lens lives on a different physical sensor, the camera is
     * rebound to that sensor (unless a recording is in progress).
     */
    fun setLens(lens: OpticalLens, displayZoom: Float = lens.zoomRatio) {
        val config = lastBindConfig
        if (config != null && config.isBackCamera && isPhysicalLensBinding && lens.cameraId != boundCameraId) {
            if (activeRecording == null) {
                bindCamera(
                    isBackCamera = true,
                    aspectRatio = config.aspectRatio,
                    enableHistogram = config.enableHistogram,
                    enableRawJpeg = config.enableRawJpeg,
                    isVideoMode = config.isVideoMode,
                    activeLens = lens,
                    cameraMode = config.cameraMode,
                    displayZoom = displayZoom,
                    isDualCapture = config.isDualCapture,
                    isDualCaptureSwapped = config.isDualCaptureSwapped,
                )
                return
            }
            Log.w(TAG, "Recording in progress: lens switch to ${lens.name} limited to digital zoom")
        }
        applyLensConfiguration(lens, displayZoom)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyLensConfiguration(lens: OpticalLens, displayZoom: Float) {
        this.currentActiveLens = lens
        setZoom(displayZoom)
        updateLiveViewfinderLut(currentAgcSettings.isLiveViewfinderLutEnabled, currentAgcSettings.activeProfile, lens, currentAgcSettings)
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            builder.setIfSupported(MtkMotoVendorTags.MOTO_IN_SENSOR_CROP_ENABLE, if (lens == OpticalLens.CROP_2X) 1 else 0)
            control2.addCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.d(TAG, "Error applying in-sensor crop tag: ${e.message}")
        }
    }

    fun triggerTapToFocus(
        x: Float,
        y: Float,
        isAeLocked: Boolean = false,
        isAfLocked: Boolean = false
    ) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            var flags = 0
            if (!isAfLocked) {
                flags = flags or FocusMeteringAction.FLAG_AF
            }
            if (!isAeLocked) {
                flags = flags or FocusMeteringAction.FLAG_AE
            }

            if (flags == 0) {
                // Both AE and AF are locked, keep active lock without changing
                return
            }

            val builder = FocusMeteringAction.Builder(point, flags)
            if (isAfLocked || isAeLocked) {
                builder.disableAutoCancel()
            } else {
                builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
            }
            cam.cameraControl.startFocusAndMetering(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Trigger tap to focus failed: ${e.message}")
        }
    }

    // Overload for backward compatibility
    fun triggerTapToFocus(x: Float, y: Float, isLocked: Boolean) {
        triggerTapToFocus(x, y, isAeLocked = isLocked, isAfLocked = isLocked)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setAeLock(locked: Boolean) {
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            control2.addCaptureRequestOptions(optionsBuilder.build())
            Log.d(TAG, "Applied separate AE Lock: $locked")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply AE lock: ${e.message}")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setAfMode(mode: SonyAfMode, locked: Boolean = false) {
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()
            val afMode = when {
                locked || mode == SonyAfMode.AF_L -> CaptureRequest.CONTROL_AF_MODE_AUTO
                mode == SonyAfMode.MF -> CaptureRequest.CONTROL_AF_MODE_OFF
                mode == SonyAfMode.AF_S -> CaptureRequest.CONTROL_AF_MODE_AUTO
                mode == SonyAfMode.AF_AUTO -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
            control2.addCaptureRequestOptions(optionsBuilder.build())
            Log.d(TAG, "Applied Sony AF Mode: $mode, locked: $locked")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Sony AF Mode: ${e.message}")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setAeMode(mode: SonyAeMode, locked: Boolean = false) {
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()
            if (locked || mode == SonyAeMode.AE_L) {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            } else {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                when (mode) {
                    SonyAeMode.AE_M -> {
                        optionsBuilder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF
                        )
                    }
                    else -> {
                        optionsBuilder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                        )
                    }
                }
            }
            control2.addCaptureRequestOptions(optionsBuilder.build())
            Log.d(TAG, "Applied Sony AE Mode: $mode, locked: $locked")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Sony AE Mode: ${e.message}")
        }
    }

    fun unlockAe() {
        setAeLock(false)
    }

    fun unlockAf() {
        val cam = camera ?: return
        try {
            cam.cameraControl.cancelFocusAndMetering()
        } catch (e: Exception) {
            Log.w(TAG, "Unlock AF failed: ${e.message}")
        }
    }

    fun unlockAeAf() {
        setAeLock(false)
        unlockAf()
    }

    fun setExposureCompensation(evIndex: Int) {
        val cam = camera ?: return
        try {
            cam.cameraControl.setExposureCompensationIndex(evIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Set exposure compensation failed: ${e.message}")
        }
    }

    fun setFlashMode(flashMode: FlashMode) {
        val capture = imageCapture ?: return
        capture.flashMode = when (flashMode) {
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.OFF, FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
        }
        if (flashMode == FlashMode.TORCH) {
            camera?.cameraControl?.enableTorch(true)
        } else {
            camera?.cameraControl?.enableTorch(false)
        }
    }

    fun capturePhoto(saveRawAndJpeg: Boolean = false) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "capturePhoto failed: imageCapture is null (active in video mode?)")
            return
        }

        if (inFlightCaptures.get() >= 2) {
            Log.w(TAG, "capturePhoto dropped: pipeline saturated (${inFlightCaptures.get()} in-flight captures)")
            return
        }
        inFlightCaptures.incrementAndGet()

        val profile = currentAgcSettings.activeProfile
        val lens = currentActiveLens
        val agc = currentAgcSettings
        val format = currentManualSettings.captureFormat
        val colorSpace = currentManualSettings.colorSpace
        val jpegQuality = currentManualSettings.jpegQuality
        val ratio = lastBindConfig?.aspectRatio ?: AspectRatio.RATIO_4_3

        // Grab secondary preview frame on the UI thread at the instant of shutter tap
        val secBitmap = if (isConcurrentCameraActive) {
            try {
                secondaryPreviewView.bitmap
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture secondary preview frame for dual capture: ${e.message}")
                null
            }
        } else null

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val (bytes, rotationDegrees) = try {
                        image.use { img ->
                            val buffer = img.planes[0].buffer
                            val b = ByteArray(buffer.remaining())
                            buffer.get(b)
                            Pair(b, img.imageInfo.rotationDegrees)
                        }
                    } catch (e: Exception) {
                        inFlightCaptures.decrementAndGet()
                        Log.e(TAG, "Failed to read image buffer from proxy: ${e.message}", e)
                        return
                    }

                    // Offload heavy image enhancement and MediaStore disk I/O to IO thread
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val timestamp = System.currentTimeMillis()
                            var dngUri: android.net.Uri? = null
                            var jpegUri: android.net.Uri? = null

                            // 1. RAW / DNG Digital Negative Capture (if format is RAW or RAW_AND_JPEG)
                            if (format.isRaw) {
                                if (hardwareCapabilities.isRawSupported) {
                                    try {
                                        val dngValues = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, "GXV_$timestamp.dng")
                                            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/GXVcam")
                                                put(MediaStore.Images.Media.IS_PENDING, 1)
                                            }
                                        }
                                        dngUri = context.contentResolver.insert(
                                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                            dngValues
                                        )
                                        if (dngUri != null) {
                                            context.contentResolver.openOutputStream(dngUri)?.use { out ->
                                                out.write(bytes)
                                                out.flush()
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                val updateValues = ContentValues().apply {
                                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                                }
                                                context.contentResolver.update(dngUri, updateValues, null, null)
                                            }
                                            Log.i(TAG, "RAW DNG saved to $dngUri (${bytes.size} bytes)")
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error saving RAW DNG file: ${e.message}")
                                    }
                                } else {
                                    Log.w(TAG, "RAW capture requested but sensor does not support REQUEST_AVAILABLE_CAPABILITIES_RAW")
                                }
                            }

                            // 2. Processed JPEG Capture (if format is JPEG or RAW_AND_JPEG)
                            if (format.isJpeg) {
                                val isPortrait = lastBindConfig?.cameraMode == CameraMode.PORTRAIT || currentCameraMode == CameraMode.PORTRAIT
                                val processed = GXVImageProcessor.processJpeg(
                                    inputJpeg = bytes,
                                    profile = profile,
                                    activeLens = lens,
                                    customSettings = agc,
                                    aspectRatio = ratio,
                                    colorSpace = colorSpace,
                                    jpegQuality = jpegQuality,
                                    rotationDegrees = rotationDegrees,
                                    secondaryBitmap = secBitmap,
                                    dualCaptureLayout = currentVideoSettings.dualCaptureLayout,
                                    isDualCaptureSwapped = currentVideoSettings.isDualCaptureSwapped,
                                    isPortraitMode = isPortrait,
                                    portraitAperture = portraitAperture,
                                    watermarkStyle = currentManualSettings.watermarkStyle,
                                    watermarkSignature = currentManualSettings.watermarkSignature
                                )

                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, "GXV_$timestamp.jpg")
                                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/GXVcam")
                                        put(MediaStore.Images.Media.IS_PENDING, 1)
                                    }
                                }
                                jpegUri = context.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    contentValues
                                )

                                if (jpegUri != null) {
                                    context.contentResolver.openOutputStream(jpegUri)?.use { out ->
                                        out.write(processed)
                                        out.flush()
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val updateValues = ContentValues().apply {
                                            put(MediaStore.Images.Media.IS_PENDING, 0)
                                        }
                                        context.contentResolver.update(jpegUri, updateValues, null, null)
                                    }

                                    Log.i(TAG, "Enhanced photo saved to $jpegUri (${processed.size} bytes, profile: ${profile.label}, colorSpace: ${colorSpace.label}, Q=${jpegQuality}%)")
                                }
                            }

                            // Notify UI with the appropriate URI
                            val primaryUri = if (format == org.gxvcam.ui.state.CaptureFormat.RAW) dngUri else (jpegUri ?: dngUri)
                            if (primaryUri != null) {
                                ContextCompat.getMainExecutor(context).execute {
                                    onEvent?.invoke(GXVcamEvent.OnPhotoCaptured(primaryUri))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in photo processing pipeline: ${e.message}", e)
                        } finally {
                            inFlightCaptures.decrementAndGet()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    inFlightCaptures.decrementAndGet()
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun toggleVideoRecording() {
        val video = videoCapture ?: run {
            Log.e(TAG, "toggleVideoRecording failed: videoCapture is null")
            return
        }

        if (activeRecording != null) {
            Log.i(TAG, "Stopping active video recording session...")
            activeRecording?.stop()
            activeRecording = null
            onEvent?.invoke(GXVcamEvent.SetRecordingState(isRecording = false))
            return
        }

        Log.i(TAG, "Starting video recording... isDualCapture=$isConcurrentCameraActive, resolution=${currentVideoSettings.resolution.label}")
        val timestamp = System.currentTimeMillis()
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "GXV_VID_$timestamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/GXVcam")
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        var pendingRecording = video.output.prepareRecording(context, mediaStoreOutput)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording = pendingRecording.withAudioEnabled()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission not granted; recording muted video")
        }

        try {
            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.i(TAG, "Video recording started successfully")
                        onEvent?.invoke(GXVcamEvent.SetRecordingState(true))
                    }
                    is VideoRecordEvent.Status -> {
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        onEvent?.invoke(GXVcamEvent.OnRecordingDurationUpdated(durationMs))
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        onEvent?.invoke(GXVcamEvent.SetRecordingState(false))
                        if (!event.hasError()) {
                            val uri = event.outputResults.outputUri
                            Log.i(TAG, "Video recorded successfully to $uri (${event.recordingStats.numBytesRecorded} bytes)")
                            onEvent?.invoke(GXVcamEvent.OnPhotoCaptured(uri))
                        } else {
                            Log.e(TAG, "Video recording finalize error: ${event.error}, cause: ${event.cause?.message}", event.cause)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording: ${e.message}", e)
            activeRecording = null
            onEvent?.invoke(GXVcamEvent.SetRecordingState(false))
        }
    }

    fun release() {
        pendingBindRunnable?.let { mainHandler.removeCallbacks(it) }
        orientationEventListener?.disable()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
