package org.gxvcam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.RenderEffect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.DynamicRange
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
import org.gxvcam.ui.state.MotoVideoStabilization
import org.gxvcam.ui.state.OpticalLens
import org.gxvcam.ui.state.ProHardwareCapabilities
import org.gxvcam.ui.state.ProSettings
import org.gxvcam.ui.state.SonyAeMode
import org.gxvcam.ui.state.SonyAfMode
import org.gxvcam.ui.state.VideoColorProfile
import org.gxvcam.ui.state.VideoResolution
import org.gxvcam.ui.state.WhiteBalanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.gxvcam.NativeEngineBridge
import org.gxvcam.ai.Apu655HardwareAccelerator
import org.gxvcam.ai.VirtualEvsTelemetrySimulator
import org.gxvcam.audio.ProAudioInputRouter
import org.gxvcam.sensors.GyroMotionTracker
import org.gxvcam.video.GxvVideoCodecManager
import org.gxvcam.video.GxvVideoGlRenderer
import org.gxvcam.video.SeamlessLensZoomController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GXVcamController(
    private val context: Context,
    private var lifecycleOwner: LifecycleOwner? = null,
    private var onEvent: ((GXVcamEvent) -> Unit)? = null,
) {

    var gyroMotionTracker: GyroMotionTracker? = null
    val zslRingBuffer: ZslRawRingBuffer = ZslRawRingBuffer(maxSlots = ZslRawRingBuffer.DEFAULT_MAX_SLOTS)
    val apu655Accelerator: Apu655HardwareAccelerator = Apu655HardwareAccelerator(context)
    val virtualEvsSimulator: VirtualEvsTelemetrySimulator = VirtualEvsTelemetrySimulator(context)
    val motoAiFallbackController: MotoAiFallbackController by lazy {
        MotoAiFallbackController(context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
    }
    val seamlessLensZoomController: SeamlessLensZoomController = SeamlessLensZoomController()
    val proAudioInputRouter: ProAudioInputRouter = ProAudioInputRouter(context)

    private var videoGlRenderer: GxvVideoGlRenderer? = null
    private var activeProVideoSession: GxvVideoCodecManager.VideoRecordingSession? = null
    private var proVideoRecordingStartMs: Long = 0L
    private var proVideoTimerRunnable: Runnable? = null
    private val auxCamera2Session: AuxCamera2Session by lazy {
        AuxCamera2Session(
            context = context,
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
            cameraExecutor = cameraExecutor,
            mainHandler = mainHandler
        )
    }
    private val auxWritableIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile
    private var isAuxCameraActive: Boolean = false
    @Volatile
    private var isAuxVideoShaderMode: Boolean = false
    private var auxDisplaySurface: Surface? = null
    private var pendingAuxOpen: (() -> Unit)? = null
    @Volatile
    private var lastColorTelemetryDispatchMs: Long = 0L

    val resolvedPhysicalIds: Map<ViennaLensRouter.LensRole, String> by lazy {
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            ViennaLensRouter.resolvePhysicalIds(manager)
        }.getOrDefault(emptyMap())
    }

    fun getActiveRawPlan(zoomRatio: Float = currentActiveLens.zoomRatio): ViennaLensRouter.RawResolutionPlan {
        val role = ViennaLensRouter.roleForOpticalLens(
            lens = currentActiveLens,
            isBackCamera = lastBindConfig?.isBackCamera ?: true
        )
        return ViennaLensRouter.selectRawResolution(
            role = role,
            zoomRatio = zoomRatio,
            isUltraRes50M = currentManualSettings.isUltraRes50M,
            isFrontHighRes32Mp = currentManualSettings.isFrontHighRes32MpEnabled
        )
    }

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
        val requestedPhysicalId: String? = null,
    )

    private var lastBindConfig: BindConfig? = null
    private var boundCameraId: String? = null
    @Volatile
    private var pendingBindPhysicalId: String? = null

    /** True when bound directly to the physical sensor of a lens (0, 2 or 3). */
    private var isPhysicalLensBinding = false

    /** Equivalent zoom of the currently bound sensor at its native 1.0 ratio. */
    private var boundSensorBaseZoom = 1.0f

    @Volatile
    private var cachedExtensionsManager: ExtensionsManager? = null

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

    val auxTextureView: android.view.TextureView = android.view.TextureView(context).apply {
        visibility = android.view.View.GONE
        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                auxDisplaySurface?.release()
                auxDisplaySurface = Surface(st)
                pendingAuxOpen?.invoke()
                pendingAuxOpen = null
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (isAuxCameraActive) {
                    if (isAuxVideoShaderMode && videoGlRenderer != null) {
                        videoGlRenderer?.attachDirectDisplaySurface(
                            auxDisplaySurface,
                            width,
                            height,
                            auxCamera2Session.sensorOrientation
                        )
                    } else {
                        configureAuxTextureTransform(auxCamera2Session.previewSize, auxCamera2Session.sensorOrientation)
                    }
                }
            }

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                pendingAuxOpen = null
                if (isAuxVideoShaderMode) {
                    videoGlRenderer?.attachDirectDisplaySurface(null, 0, 0)
                }
                auxDisplaySurface?.release()
                auxDisplaySurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    val viewfinderContainer: android.widget.FrameLayout = android.widget.FrameLayout(context).apply {
        addView(
            previewView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            auxTextureView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setAuxViewfinderActive(active: Boolean) {
        isAuxCameraActive = active
        val runnable = Runnable {
            auxTextureView.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
            previewView.visibility = if (active) android.view.View.GONE else android.view.View.VISIBLE
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private fun configureAuxTextureTransform(previewSize: android.util.Size, sensorOrientation: Int) {
        mainHandler.post {
            val viewW = auxTextureView.width
            val viewH = auxTextureView.height
            if (viewW <= 0 || viewH <= 0) return@post
            if (isAuxVideoShaderMode) {
                auxTextureView.setTransform(null)
                return@post
            }
            val isPortraitDisplay = (deviceSurfaceRotation == Surface.ROTATION_0 || deviceSurfaceRotation == Surface.ROTATION_180)
            val rotated = (isPortraitDisplay && (sensorOrientation % 180 != 0)) ||
                (!isPortraitDisplay && (sensorOrientation % 180 == 0))
            val contentW = if (rotated) previewSize.height else previewSize.width
            val contentH = if (rotated) previewSize.width else previewSize.height
            val scale = maxOf(viewW.toFloat() / contentW.coerceAtLeast(1), viewH.toFloat() / contentH.coerceAtLeast(1))
            val sx = (contentW * scale) / viewW.toFloat()
            val sy = (contentH * scale) / viewH.toFloat()
            val matrix = android.graphics.Matrix()
            matrix.setScale(sx, sy, viewW / 2f, viewH / 2f)
            auxTextureView.setTransform(matrix)
        }
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
    private val auxCameraProbed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val auxProbeCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBindRunnable: Runnable? = null
    @Volatile
    private var lastSelfieFaceCount: Int = -1
    @Volatile
    private var lastSelfieFaceDispatchMs: Long = 0L

    var deviceSurfaceRotation: Int = Surface.ROTATION_0
        private set
    var deviceOrientationAngle: Int = 0
        private set
    var currentVideoSettings: MotoVideoSettings = MotoVideoSettings()
        private set

    var onCapabilitiesReady: ((ProHardwareCapabilities) -> Unit)? = null
    var onHistogramReady: ((HistogramData) -> Unit)? = null
    var hardwareCapabilities: ProHardwareCapabilities = ProHardwareCapabilities()
        private set

    init {
        updateVideoCapture(currentVideoSettings)
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

    fun updateVideoCapture(
        settings: MotoVideoSettings = currentVideoSettings,
        allowHdr10: Boolean = true
    ) {
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
        val targetFps = MotoVideoEngine.computeTargetFps(settings)
        val targetBitRate = MotoVideoEngine.computeTargetBitRate(settings)
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    quality,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
            )
            .setTargetVideoEncodingBitRate(targetBitRate)
            .setExecutor(cameraExecutor)
            .build()
        val videoBuilder = VideoCapture.Builder(recorder)
            .setTargetRotation(deviceSurfaceRotation)
            .setVideoStabilizationEnabled(settings.stabilization != MotoVideoStabilization.OFF)
            .setTargetFrameRate(Range(targetFps, targetFps))
            .setDynamicRange(
                if (allowHdr10 && settings.isHdr10BitEnabled && !settings.isDualCaptureEnabled) {
                    DynamicRange.HLG_10_BIT
                } else {
                    DynamicRange.SDR
                }
            )
        videoCapture = videoBuilder.build()
    }

    fun setLifecycleOwner(owner: LifecycleOwner) {
        this.lifecycleOwner = owner
    }

    fun setOnEventListener(listener: (GXVcamEvent) -> Unit) {
        this.onEvent = listener
        listener(GXVcamEvent.UpdateHardwareEvsSupport(virtualEvsSimulator.hasPhysicalEvsHardware))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        isBackCamera: Boolean = true,
        aspectRatio: AspectRatio = AspectRatio.RATIO_21_9,
        enableHistogram: Boolean = false,
        enableRawJpeg: Boolean = false,
        isVideoMode: Boolean = false,
        activeLens: OpticalLens = OpticalLens.MAIN_1X,
        cameraMode: CameraMode = CameraMode.PHOTO,
        displayZoom: Float = activeLens.zoomRatio,
        isDualCapture: Boolean = (isVideoMode || cameraMode == CameraMode.PHOTO) && currentVideoSettings.isDualCaptureEnabled,
        isDualCaptureSwapped: Boolean = currentVideoSettings.isDualCaptureSwapped,
        enableVevsAnalysis: Boolean = false,
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
                if (cachedExtensionsManager == null) {
                    val extFuture = ExtensionsManager.getInstanceAsync(context, provider)
                    extFuture.addListener({
                        runCatching { cachedExtensionsManager = extFuture.get() }
                    }, cameraExecutor)
                }

                val wasDual = lastWasDualCapture
                val wasAux = isAuxCameraActive
                val isDual = isDualCapture
                this.lastWasDualCapture = isDual
                val wantsAuxLens = !isDual && isBackCamera &&
                    (activeLens == OpticalLens.UW || activeLens == OpticalLens.TELE_3X)
                val needsTransitionDelay = (wasDual != isDual) || (wantsAuxLens && !wasAux && boundCameraId != null)

                auxCamera2Session.closeSync()
                pendingAuxOpen = null
                isAuxVideoShaderMode = false
                setAuxViewfinderActive(false)
                provider.unbindAll()
                isConcurrentCameraActive = false
                secondaryCamera = null
                secondaryPreview = null
                concurrentCamera = null
                camera = null

                pendingBindRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingBindPhysicalId = if (isDual) {
                    null
                } else {
                    ViennaLensRouter.cameraIdForOpticalLens(activeLens, isBackCamera, resolvedPhysicalIds)
                }

                val targetRatio = when (aspectRatio) {
                    AspectRatio.RATIO_16_9,
                    AspectRatio.RATIO_21_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
                    AspectRatio.RATIO_4_3,
                    AspectRatio.RATIO_1_1,
                    AspectRatio.RATIO_3_2 -> androidx.camera.core.AspectRatio.RATIO_4_3
                }

                val bindAction = Runnable {
                    val activeProvider = cameraProvider ?: return@Runnable
                    try {
                        // Motorola Edge 50 Neo Dual Capture Concurrent Camera (Rear + Front in both Photo & Video modes)
                        if (isDualCapture) {
                            proAudioInputRouter.stopMonitoring()
                            videoGlRenderer?.close()
                            videoGlRenderer = null
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
                                        .setJpegQuality(100)
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
                                pendingBindPhysicalId = null

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
                        val targetPhysicalId = resolved.requestedPhysicalId ?: resolved.cameraId
                        val availableIds = activeProvider.availableCameraInfos.mapNotNull { info ->
                            runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
                        }.toSet()
                        val useAuxCamera2 = isBackCamera &&
                            (activeLens == OpticalLens.UW || activeLens == OpticalLens.TELE_3X) &&
                            targetPhysicalId != null &&
                            targetPhysicalId !in availableIds &&
                            auxWritableIds.contains(targetPhysicalId)

                        if (useAuxCamera2 && targetPhysicalId != null) {
                            pendingBindPhysicalId = null
                            bindAuxCamera2(
                                physicalId = targetPhysicalId,
                                isVideoMode = isVideoMode,
                                activeLens = activeLens,
                                cameraMode = cameraMode,
                                displayZoom = displayZoom
                            )
                            return@Runnable
                        }
                        val baseSelector = resolved.selector

                        // Use OEM CameraX Extensions only for dedicated Night mode or explicit Moto AI fallback.
                        // On Motorola Edge 50/60 Neo, OEM Extensions in standard PHOTO/PORTRAIT modes can cause
                        // green preview tint or sensor remapping on IMX896 ("0") / Front ("1").
                        val allowOemExtensionsOnLens = !isBackCamera ||
                            activeLens == OpticalLens.MAIN_1X ||
                            activeLens == OpticalLens.CROP_2X
                        val useMotoAiFallback = apu655Accelerator.shouldUseMotoAiFallback(currentManualSettings)
                        val shouldUseOemExtension = !isVideoMode &&
                            cameraMode != CameraMode.PRO &&
                            allowOemExtensionsOnLens &&
                            (cameraMode == CameraMode.NIGHT || useMotoAiFallback)
                        val selector = if (shouldUseOemExtension) {
                            val extensionsManager = cachedExtensionsManager ?: try {
                                ExtensionsManager.getInstanceAsync(context, activeProvider)
                                    .get(150, TimeUnit.MILLISECONDS)
                                    ?.also { cachedExtensionsManager = it }
                            } catch (_: Exception) {
                                null
                            }
                            if (extensionsManager != null) {
                                val candidateModes = if (useMotoAiFallback) {
                                    when (MotoAiFallbackController.sceneModeFor(cameraMode)) {
                                        MotoAiSceneMode.NIGHT -> listOf(ExtensionMode.NIGHT, ExtensionMode.AUTO)
                                        MotoAiSceneMode.BOKEH -> listOf(ExtensionMode.BOKEH, ExtensionMode.AUTO)
                                        MotoAiSceneMode.HDR -> listOf(ExtensionMode.HDR, ExtensionMode.AUTO)
                                        MotoAiSceneMode.AUTO -> listOf(ExtensionMode.AUTO, ExtensionMode.HDR)
                                    }
                                } else {
                                    listOf(ExtensionMode.NIGHT, ExtensionMode.AUTO)
                                }
                                val matchedMode = candidateModes.firstOrNull { mode ->
                                    runCatching { extensionsManager.isExtensionAvailable(baseSelector, mode) }
                                        .getOrDefault(false)
                                }
                                if (matchedMode != null) {
                                    val extSelector = runCatching {
                                        extensionsManager.getExtensionEnabledCameraSelector(baseSelector, matchedMode)
                                    }.getOrNull()
                                    val resolvedExtIds = runCatching {
                                        extSelector?.filter(activeProvider.availableCameraInfos)?.mapNotNull { info ->
                                            runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
                                        }
                                    }.getOrNull()
                                    if (extSelector != null &&
                                        (resolved.cameraId == null || resolvedExtIds?.contains(resolved.cameraId) == true)
                                    ) {
                                        Log.i(TAG, "Enabling OEM ExtensionMode=$matchedMode for $cameraMode on camera ${resolved.cameraId}")
                                        extSelector
                                    } else {
                                        Log.i(TAG, "Skipping OEM ExtensionMode=$matchedMode because it remaps camera ${resolved.cameraId} -> $resolvedExtIds")
                                        baseSelector
                                    }
                                } else {
                                    baseSelector
                                }
                            } else {
                                baseSelector
                            }
                        } else {
                            baseSelector
                        }

                        val subSessionPhysicalId: String? = if (!resolved.isPhysicalLens &&
                            isBackCamera &&
                            resolved.cameraId != null &&
                            resolved.requestedPhysicalId != null &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ) {
                            runCatching {
                                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                val logicalChars = cm.getCameraCharacteristics(resolved.cameraId)
                                if (logicalChars.physicalCameraIds.contains(resolved.requestedPhysicalId)) {
                                    resolved.requestedPhysicalId
                                } else null
                            }.getOrNull()
                        } else null

                        lastSelfieFaceCount = -1
                        val previewBuilder = Preview.Builder()
                            .setTargetAspectRatio(targetRatio)
                        val previewExtender = Camera2Interop.Extender(previewBuilder)
                        if (subSessionPhysicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            previewExtender.setPhysicalCameraId(subSessionPhysicalId)
                        }
                        if (!isBackCamera) {
                            previewExtender.setCaptureRequestOption(
                                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                                CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                            )
                        }
                        previewExtender.setSessionCaptureCallback(
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    if (isVideoMode) {
                                        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                                        if (gains != null) {
                                            val role = ViennaLensRouter.roleForOpticalLens(
                                                lens = currentActiveLens,
                                                isBackCamera = isBackCamera
                                            )
                                            val colorState = seamlessLensZoomController.onColorCorrectionGains(role, gains)
                                            val nowMs = SystemClock.elapsedRealtime()
                                            if (nowMs - lastColorTelemetryDispatchMs >= 100L) {
                                                lastColorTelemetryDispatchMs = nowMs
                                                mainHandler.post {
                                                    onEvent?.invoke(GXVcamEvent.UpdateCrossLensColorState(colorState))
                                                }
                                            }
                                        }
                                    }
                                    if (!isBackCamera) {
                                        val count = result.get(CaptureResult.STATISTICS_FACES)?.size ?: 0
                                        val now = SystemClock.elapsedRealtime()
                                        if (count != lastSelfieFaceCount && (now - lastSelfieFaceDispatchMs) >= 250L) {
                                            lastSelfieFaceCount = count
                                            lastSelfieFaceDispatchMs = now
                                            mainHandler.post {
                                                onEvent?.invoke(GXVcamEvent.OnSelfieFaceCountUpdated(count))
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        preview = previewBuilder.build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val useCases = mutableListOf<UseCase>(preview!!)
                        var proVideoEffect: androidx.camera.core.CameraEffect? = null

                        if (isVideoMode) {
                            imageCapture = null
                            imageAnalysis = null
                            if (currentVideoSettings.isProVideoEngineEnabled) {
                                videoCapture = null
                                val renderer = videoGlRenderer ?: GxvVideoGlRenderer(
                                    zoomController = seamlessLensZoomController,
                                    onColorStateStepped = null
                                ).also { videoGlRenderer = it }
                                syncVideoGlRendererSettings(currentVideoSettings)
                                syncAudioRouterSettings(currentVideoSettings, displayZoom)
                                proAudioInputRouter.startMonitoring()
                                proVideoEffect = renderer.createCameraEffect { err ->
                                    Log.e(TAG, "Pro Video GL CameraEffect error: ${err.message}", err)
                                }
                            } else {
                                proAudioInputRouter.stopMonitoring()
                                videoGlRenderer?.close()
                                videoGlRenderer = null
                                updateVideoCapture(currentVideoSettings, allowHdr10 = true)
                                videoCapture?.targetRotation = deviceSurfaceRotation
                                videoCapture?.let { useCases.add(it) }
                            }
                        } else {
                            proAudioInputRouter.stopMonitoring()
                            videoGlRenderer?.close()
                            videoGlRenderer = null
                            val imageCaptureBuilder = ImageCapture.Builder()
                                .setTargetAspectRatio(targetRatio)
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setJpegQuality(100)
                                .setTargetRotation(deviceSurfaceRotation)
                            if (subSessionPhysicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(subSessionPhysicalId)
                            }
                            imageCapture = imageCaptureBuilder.build()
                            useCases.add(imageCapture!!)

                            val needHistogram = enableHistogram && cameraMode == CameraMode.PRO
                            val needVevs = enableVevsAnalysis && currentManualSettings.isSyntheticEvsDeblurEnabled
                            if (needHistogram || needVevs) {
                                val histogramAnalyzer = if (needHistogram) {
                                    HistogramAnalyzer { histogram ->
                                        onHistogramReady?.invoke(histogram)
                                        onEvent?.invoke(GXVcamEvent.UpdateHistogram(histogram))
                                    }
                                } else null

                                imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                    .build().apply {
                                        setAnalyzer(cameraExecutor) { image ->
                                            try {
                                                histogramAnalyzer?.analyzeWithoutClosing(image)
                                                if (needVevs) {
                                                    val evsOut = virtualEvsSimulator.processYuvFrame(
                                                        image = image,
                                                        rotationDegrees = image.imageInfo.rotationDegrees
                                                    )
                                                    if (evsOut != null) {
                                                        onEvent?.invoke(GXVcamEvent.UpdateVirtualEvsOutput(evsOut))
                                                    }
                                                }
                                            } finally {
                                                image.close()
                                            }
                                        }
                                    }
                                useCases.add(imageAnalysis!!)
                            } else {
                                imageAnalysis = null
                            }
                        }

                        camera = try {
                            if (proVideoEffect != null) {
                                val groupBuilder = UseCaseGroup.Builder()
                                useCases.forEach { groupBuilder.addUseCase(it) }
                                groupBuilder.addEffect(proVideoEffect)
                                activeProvider.bindToLifecycle(owner, selector, groupBuilder.build())
                            } else {
                                activeProvider.bindToLifecycle(
                                    owner,
                                    selector,
                                    *useCases.toTypedArray()
                                )
                            }
                        } catch (bindErr: Exception) {
                            if (proVideoEffect != null) {
                                Log.w(TAG, "Pro Video GL effect bind failed (${bindErr.message}); falling back to standard VideoCapture")
                                proAudioInputRouter.stopMonitoring()
                                videoGlRenderer?.close()
                                videoGlRenderer = null
                                activeProvider.unbindAll()
                                updateVideoCapture(currentVideoSettings, allowHdr10 = false)
                                val fallbackUseCases = mutableListOf<UseCase>(preview!!)
                                videoCapture?.let { fallbackUseCases.add(it) }
                                activeProvider.bindToLifecycle(owner, baseSelector, *fallbackUseCases.toTypedArray())
                            } else if (selector !== baseSelector) {
                                Log.w(TAG, "OEM extension bind failed (${bindErr.message}); falling back to base selector")
                                activeProvider.unbindAll()
                                activeProvider.bindToLifecycle(owner, baseSelector, *useCases.toTypedArray())
                            } else if (isVideoMode && currentVideoSettings.isHdr10BitEnabled) {
                                Log.w(TAG, "HLG 10-bit video bind failed (${bindErr.message}); falling back to SDR")
                                activeProvider.unbindAll()
                                updateVideoCapture(currentVideoSettings, allowHdr10 = false)
                                val sdrUseCases = mutableListOf<UseCase>(preview!!)
                                videoCapture?.let { sdrUseCases.add(it) }
                                activeProvider.bindToLifecycle(owner, baseSelector, *sdrUseCases.toTypedArray())
                            } else {
                                throw bindErr
                            }
                        }
                        boundCameraId = resolved.cameraId
                        pendingBindPhysicalId = null
                        isPhysicalLensBinding = resolved.isPhysicalLens
                        boundSensorBaseZoom = if (resolved.isPhysicalLens) activeLens.sensorBaseZoom else 1.0f

                        readHardwareCapabilities()
                        applyStabilization(isVideoMode)
                        applyLensConfiguration(activeLens, displayZoom)
                        applyManualSettings(currentManualSettings)
                        if (isVideoMode) {
                            applyMotoVideoSettings(currentVideoSettings, zoomRatio = displayZoom)
                        } else {
                            applyAgcSettings(currentAgcSettings, activeLens)
                        }
                    } catch (e: Exception) {
                        pendingBindPhysicalId = null
                        Log.e(TAG, "Use case binding failed: ${e.message}", e)
                    }
                }

                pendingBindRunnable = bindAction
                if (boundCameraId == null &&
                    auxCameraProbed.compareAndSet(false, true) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val uwWritable = ViennaLensRouter.probeAuxCameraWritable(cm, "2", cameraExecutor)
                        val teleWritable = ViennaLensRouter.probeAuxCameraWritable(cm, "3", cameraExecutor)
                        val logicalWritable = ViennaLensRouter.probeAuxCameraWritable(cm, "5", cameraExecutor)
                        if (uwWritable) auxWritableIds.add("2")
                        if (teleWritable) auxWritableIds.add("3")
                        if (logicalWritable) auxWritableIds.add("5")
                        Log.i(TAG, "Aux camera writable summary: uw(2)=$uwWritable, tele(3)=$teleWritable, logical(5)=$logicalWritable")
                        auxProbeCompleted.set(true)
                        mainHandler.post {
                            pendingBindRunnable?.run()
                        }
                    }
                    return@addListener
                }
                if (!auxProbeCompleted.get() && auxCameraProbed.get()) {
                    return@addListener
                }
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
        Log.i(TAG, "CameraX visible camera IDs: $availableIds, resolvedPhysicalIds=$resolvedPhysicalIds, targetLens=$lens")

        if (!isBackCamera) {
            val frontId = ViennaLensRouter.cameraIdForOpticalLens(
                lens = lens,
                isBackCamera = false,
                resolvedIds = resolvedPhysicalIds
            )
            return if (frontId in availableIds) {
                ResolvedCamera(selectorForCameraId(frontId), frontId, isPhysicalLens = false)
            } else if (FRONT_CAMERA_ID in availableIds) {
                ResolvedCamera(selectorForCameraId(FRONT_CAMERA_ID), FRONT_CAMERA_ID, isPhysicalLens = false)
            } else {
                ResolvedCamera(CameraSelector.DEFAULT_FRONT_CAMERA, null, isPhysicalLens = false)
            }
        }

        val targetPhysicalId = ViennaLensRouter.cameraIdForOpticalLens(
            lens = lens,
            isBackCamera = true,
            resolvedIds = resolvedPhysicalIds
        )

        // Priority 1: Bind directly to the physical sensor on Motorola Edge 50 Neo & Edge 60 Neo ("2" UW, "0" Main/2x, "3" 3x Tele)
        // when exposed in CameraX availableCameraInfos. This guarantees that 0.6x Ultra-Wide ("2") and 3x Optical Telephoto ("3")
        // physically activate instead of being clamped or digitally cropped on primary sensor "0".
        if (targetPhysicalId in availableIds) {
            Log.i(TAG, "Binding directly to physical sensor ID=$targetPhysicalId for lens=${lens.name} (baseZoom=${lens.sensorBaseZoom}x)")
            return ResolvedCamera(
                selector = selectorForCameraId(targetPhysicalId),
                cameraId = targetPhysicalId,
                isPhysicalLens = true,
                requestedPhysicalId = targetPhysicalId
            )
        }
        if (lens.cameraId in availableIds) {
            Log.i(TAG, "Binding directly to default physical sensor ID=${lens.cameraId} for lens=${lens.name}")
            return ResolvedCamera(
                selector = selectorForCameraId(lens.cameraId),
                cameraId = lens.cameraId,
                isPhysicalLens = true,
                requestedPhysicalId = lens.cameraId
            )
        }

        // Priority 2: Logical multi-camera ("5") fallback with explicit physical sub-session routing
        val logicalId = resolvedPhysicalIds[ViennaLensRouter.LensRole.LOGICAL_BACK] ?: LOGICAL_BACK_CAMERA_ID
        if (logicalId in availableIds) {
            Log.i(TAG, "Physical ID $targetPhysicalId not directly in CameraX list; using logical multi-camera $logicalId with physical target $targetPhysicalId")
            return ResolvedCamera(
                selector = selectorForCameraId(logicalId),
                cameraId = logicalId,
                isPhysicalLens = false,
                requestedPhysicalId = targetPhysicalId
            )
        }

        val mainId = resolvedPhysicalIds[ViennaLensRouter.LensRole.MAIN] ?: OpticalLens.MAIN_1X.cameraId
        if (mainId in availableIds) {
            return ResolvedCamera(
                selector = selectorForCameraId(mainId),
                cameraId = mainId,
                isPhysicalLens = false,
                requestedPhysicalId = targetPhysicalId
            )
        }

        Log.w(TAG, "Neither physical camera $targetPhysicalId nor logical camera $logicalId exposed; using default back camera")
        return ResolvedCamera(CameraSelector.DEFAULT_BACK_CAMERA, null, isPhysicalLens = false, requestedPhysicalId = targetPhysicalId)
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

    private fun bindAuxCamera2(
        physicalId: String,
        isVideoMode: Boolean,
        activeLens: OpticalLens,
        cameraMode: CameraMode,
        displayZoom: Float
    ) {
        Log.i(TAG, "Binding direct Aux Camera2 physical ID=$physicalId for lens=${activeLens.name}, video=$isVideoMode")
        boundCameraId = physicalId
        isPhysicalLensBinding = true
        boundSensorBaseZoom = activeLens.sensorBaseZoom
        isAuxCameraActive = true
        setAuxViewfinderActive(true)

        val useShaderVideo = isVideoMode && currentVideoSettings.isProVideoEngineEnabled
        isAuxVideoShaderMode = useShaderVideo
        val targetFps = MotoVideoEngine.computeTargetFps(currentVideoSettings)
        val sensorZoom = (displayZoom / activeLens.sensorBaseZoom).coerceAtLeast(1.0f)
        val initialFlash = FlashMode.OFF

        val openBlock: () -> Unit = {
            val dispSurf = auxDisplaySurface
            val tex = auxTextureView.surfaceTexture
            if (dispSurf != null && dispSurf.isValid && tex != null) {
                val cb = object : AuxCamera2Session.Callbacks {
                    override fun onPreviewConfigured(
                        cameraId: String,
                        previewSize: android.util.Size,
                        sensorOrientation: Int,
                        capabilities: ProHardwareCapabilities
                    ) {
                        hardwareCapabilities = capabilities
                        mainHandler.post {
                            if (isAuxVideoShaderMode) {
                                videoGlRenderer?.updateDirectInputResolution(
                                    previewSize.width,
                                    previewSize.height,
                                    sensorOrientation
                                )
                            } else {
                                tex.setDefaultBufferSize(previewSize.width, previewSize.height)
                                configureAuxTextureTransform(previewSize, sensorOrientation)
                            }
                            onCapabilitiesReady?.invoke(capabilities)
                            onEvent?.invoke(GXVcamEvent.UpdateHardwareCapabilities(capabilities))
                            updateLiveViewfinderLut(
                                currentAgcSettings.isLiveViewfinderLutEnabled,
                                currentAgcSettings.activeProfile,
                                activeLens,
                                currentAgcSettings
                            )
                        }
                    }

                    override fun onCaptureResult(result: TotalCaptureResult) {
                        if (isVideoMode) {
                            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                            if (gains != null) {
                                val role = ViennaLensRouter.roleForOpticalLens(currentActiveLens, isBackCamera = true)
                                val colorState = seamlessLensZoomController.onColorCorrectionGains(role, gains)
                                val nowMs = SystemClock.elapsedRealtime()
                                if (nowMs - lastColorTelemetryDispatchMs >= 100L) {
                                    lastColorTelemetryDispatchMs = nowMs
                                    mainHandler.post {
                                        onEvent?.invoke(GXVcamEvent.UpdateCrossLensColorState(colorState))
                                    }
                                }
                            }
                        }
                    }

                    override fun onError(message: String, cause: Throwable?) {
                        Log.e(TAG, "AuxCamera2 error: $message", cause)
                    }
                }

                if (useShaderVideo) {
                    auxTextureView.setTransform(null)
                    val renderer = videoGlRenderer ?: GxvVideoGlRenderer(
                        zoomController = seamlessLensZoomController,
                        onColorStateStepped = null
                    ).also { videoGlRenderer = it }
                    syncVideoGlRendererSettings(currentVideoSettings)
                    syncAudioRouterSettings(currentVideoSettings, displayZoom)
                    proAudioInputRouter.startMonitoring()
                    val w = auxTextureView.width.coerceAtLeast(1080)
                    val h = auxTextureView.height.coerceAtLeast(1920)
                    renderer.attachDirectDisplaySurface(dispSurf, w, h, 90)
                    renderer.createDirectInputSurface(1920, 1080) { glInputSurface ->
                        auxCamera2Session.open(
                            cameraId = physicalId,
                            displaySurface = glInputSurface,
                            isVideoMode = true,
                            targetFps = targetFps,
                            initialZoom = sensorZoom,
                            isProMode = cameraMode == CameraMode.PRO,
                            manualSettings = currentManualSettings,
                            proSettings = ProSettings(),
                            flashMode = initialFlash,
                            callbacks = cb
                        )
                    }
                } else {
                    proAudioInputRouter.stopMonitoring()
                    videoGlRenderer?.close()
                    videoGlRenderer = null
                    auxCamera2Session.open(
                        cameraId = physicalId,
                        displaySurface = dispSurf,
                        isVideoMode = isVideoMode,
                        targetFps = targetFps,
                        initialZoom = sensorZoom,
                        isProMode = cameraMode == CameraMode.PRO,
                        manualSettings = currentManualSettings,
                        proSettings = ProSettings(),
                        flashMode = initialFlash,
                        callbacks = cb
                    )
                }
            }
        }

        if (auxTextureView.isAvailable && auxDisplaySurface?.isValid == true) {
            openBlock()
        } else {
            pendingAuxOpen = openBlock
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualSettings(settings: CameraManualSettings) {
        this.currentManualSettings = settings
        if (isAuxCameraActive) {
            auxCamera2Session.setManualSettings(settings)
            return
        }
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()

            // 50MP Quad-Bayer remosaic toggle on supported sensors (Sony IMX896)
            optionsBuilder.setIfSupported(
                MtkMotoVendorTags.MOTO_DO_REMOSAIC,
                (if (settings.isUltraRes50M) 1 else 0).toByte()
            )

            // ZSL (Zero Shutter Lag) mode (Byte on MTK Imagiq 950 HAL)
            val zslByte: Byte = if (settings.isZslEnabled) 1 else 0
            optionsBuilder.setIfSupported(MtkMotoVendorTags.MTK_ZSL_MODE, zslByte)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, settings.isZslEnabled)

            // Anti-banding mode (0=OFF, 1=50Hz, 2=60Hz, 3=AUTO)
            val abMode = when (settings.antiBandingMode) {
                0 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
                1 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                2 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            }
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, abMode)

            val useMotoAiFallback = apu655Accelerator.shouldUseMotoAiFallback(settings)
            if (useMotoAiFallback) {
                motoAiFallbackController.applyBuiltInMotoIspInterop(
                    builder = optionsBuilder,
                    cameraMode = currentCameraMode,
                    supportedKeyNames = supportedRequestKeyNames
                )
            }

            control2.addCaptureRequestOptions(optionsBuilder.build())
            Log.d(
                TAG,
                "Applied manual settings: 50MP=${settings.isUltraRes50M}, ADRC=${settings.isAdrcEnabled}, ZSL=${settings.isZslEnabled}, aiBackend=${settings.aiBackend}, fallback=$useMotoAiFallback"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error applying manual settings: ${e.message}")
        } finally {
            // Configure LiteRT NNAPI/GPU delegate off the main thread
            cameraExecutor.execute {
                runCatching {
                    val telemetry = apu655Accelerator.inspectTelemetry(settings)
                    PipelineTelemetry.recordNpuTelemetry(telemetry)
                }.onFailure { err ->
                    Log.w(TAG, "APU 655 telemetry inspection failed: ${err.message}")
                }
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyProSettings(settings: ProSettings, isVideoMode: Boolean = false) {
        if (isAuxCameraActive) {
            auxCamera2Session.setProSettings(
                settings = settings,
                isProMode = currentCameraMode == CameraMode.PRO,
                isVideoMode = isVideoMode
            )
            return
        }
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val optionsBuilder = CaptureRequestOptions.Builder()

            val oisMode = if (lastBindConfig?.isBackCamera == true && currentActiveLens != OpticalLens.UW) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                oisMode
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

            // White Balance + CIE 1976 (u', v') AWB Guard
            when (settings.whiteBalanceMode) {
                WhiteBalanceMode.CUSTOM -> {
                    val k = settings.whiteBalanceKelvin.coerceIn(2000, 10000)
                    val rawRGain = (1.0f + (k - 2000f) / 8000f * 1.5f).coerceIn(0.8f, 3.5f)
                    val rawBGain = (3.0f - (k - 2000f) / 8000f * 2.0f).coerceIn(0.8f, 3.5f)
                    val guarded = CieUvAwbGuard.guardGains(
                        rGain = rawRGain,
                        gEvenGain = 1.0f,
                        gOddGain = 1.0f,
                        bGain = rawBGain,
                        hasFace = currentCameraMode == CameraMode.PORTRAIT
                    )
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
                        guarded.toRggbChannelVector()
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

            val activeRole = ViennaLensRouter.roleForOpticalLens(
                lens = currentActiveLens,
                isBackCamera = lastBindConfig?.isBackCamera ?: true
            )
            val forceAfOff = ViennaLensRouter.isFixedFocusRole(activeRole, hardwareCapabilities.minFocusDistanceDiopters)

            if (forceAfOff || settings.isManualFocus) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                if (settings.isManualFocus && !forceAfOff) {
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        settings.focusDistanceDiopters
                    )
                }
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
            val hasOis = (lastBindConfig?.isBackCamera == true && activeLens != OpticalLens.UW)
            val options = AgcOptimizationEngine.buildAgcRequestOptions(
                agc = agc,
                activeLens = activeLens,
                supportedKeys = supportedRequestKeyNames,
                cameraMode = currentCameraMode,
                hasOis = hasOis
            )
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
                    val effect = if (enabled) {
                        val colorFilter = GXVImageProcessor.buildSensorColorFilter(
                            profile = profile,
                            activeLens = lens,
                            customSettings = customSettings,
                            forLiveViewfinder = true
                        )
                        RenderEffect.createColorFilterEffect(colorFilter)
                    } else {
                        null
                    }
                    previewView.setRenderEffect(effect)
                    auxTextureView.setRenderEffect(effect)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply live viewfinder RenderEffect: ${e.message}")
                }
            }
        }
    }

    private fun syncVideoGlRendererSettings(settings: MotoVideoSettings) {
        val renderer = videoGlRenderer ?: return
        renderer.lutWeights = FloatArray(4) { i -> if (i == settings.activeLutStyle.index) 1f else 0f }
        renderer.lutMix = settings.lutBlend
        renderer.vibrance = settings.vibrance
        renderer.isLogCurveEnabled = (settings.colorProfile == VideoColorProfile.GXV_LOG_ACES)
    }

    private fun syncAudioRouterSettings(settings: MotoVideoSettings, zoomRatio: Float) {
        proAudioInputRouter.configureRouting(
            source = settings.audioInputSource,
            deviceId = settings.selectedAudioDeviceId,
            windCutEnabled = settings.isWindNoiseCutEnabled,
            zoomRatio = zoomRatio
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyMotoVideoSettings(settings: MotoVideoSettings, zoomRatio: Float = 1.0f, rollDegrees: Float = 0f) {
        val previousResolution = currentVideoSettings.resolution
        val previousDualCapture = currentVideoSettings.isDualCaptureEnabled
        val previousDualCaptureSwapped = currentVideoSettings.isDualCaptureSwapped
        val previousProEngine = currentVideoSettings.isProVideoEngineEnabled
        this.currentVideoSettings = settings

        syncVideoGlRendererSettings(settings)
        syncAudioRouterSettings(settings, zoomRatio)

        val isIdle = (activeRecording == null && activeProVideoSession == null)
        val needsRebind = (settings.resolution != previousResolution && isIdle) ||
                (settings.isDualCaptureEnabled != previousDualCapture && isIdle) ||
                (settings.isDualCaptureSwapped != previousDualCaptureSwapped && isIdle) ||
                (settings.isProVideoEngineEnabled != previousProEngine && isIdle)

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

        if (isAuxCameraActive) {
            auxCamera2Session.setTargetFps(MotoVideoEngine.computeTargetFps(settings))
            return
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

            val activeRole = ViennaLensRouter.roleForOpticalLens(
                lens = currentActiveLens,
                isBackCamera = lastBindConfig?.isBackCamera ?: true
            )
            if (ViennaLensRouter.isFixedFocusRole(activeRole, hardwareCapabilities.minFocusDistanceDiopters)) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
            }

            control2.addCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.d(TAG, "Stabilization tag not applied: ${e.message}")
        }
    }

    /**
     * Applies an equivalent (main-lens relative) zoom ratio, converting it to the zoom ratio
     * of the currently bound physical sensor (e.g. 0.6x on the UW sensor => 1.0 sensor zoom).
     * Automatically rebinds when crossing physical lens boundaries (UW 0.6x <-> Main 1x/2x <-> Tele 3x)
     * while idle, and updates in-sensor 50MP Quad-Bayer crop tags when crossing 1x <-> 2x.
     */
    fun setZoom(displayZoom: Float) {
        val previousLens = this.currentActiveLens
        val targetLens = OpticalLens.forZoom(displayZoom)
        this.currentActiveLens = targetLens

        val config = lastBindConfig
        if (config != null && config.isBackCamera && isPhysicalLensBinding && !config.isDualCapture) {
            val targetPhysicalId = ViennaLensRouter.cameraIdForOpticalLens(
                lens = targetLens,
                isBackCamera = true,
                resolvedIds = resolvedPhysicalIds
            )
            if (targetPhysicalId != boundCameraId &&
                targetPhysicalId != pendingBindPhysicalId &&
                activeRecording == null &&
                activeProVideoSession == null
            ) {
                bindCamera(
                    isBackCamera = true,
                    aspectRatio = config.aspectRatio,
                    enableHistogram = config.enableHistogram,
                    enableRawJpeg = config.enableRawJpeg,
                    isVideoMode = config.isVideoMode,
                    activeLens = targetLens,
                    cameraMode = config.cameraMode,
                    displayZoom = displayZoom,
                    isDualCapture = config.isDualCapture,
                    isDualCaptureSwapped = config.isDualCaptureSwapped,
                )
                return
            }
        }

        applySensorZoomRatio(displayZoom)
        if (previousLens != targetLens) {
            applyInSensorCropTags(targetLens, displayZoom)
        }
    }

    private fun applySensorZoomRatio(displayZoom: Float) {
        if (currentCameraMode == CameraMode.VIDEO) {
            val colorState = seamlessLensZoomController.updateZoom(displayZoom)
            proAudioInputRouter.updateAudioZoom(displayZoom)
            onEvent?.invoke(GXVcamEvent.UpdateCrossLensColorState(colorState))
        }
        val sensorZoom = displayZoom / boundSensorBaseZoom
        if (isAuxCameraActive) {
            auxCamera2Session.setZoomRatio(sensorZoom)
            return
        }
        val cam = camera ?: return
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
        val targetPhysicalId = ViennaLensRouter.cameraIdForOpticalLens(
            lens = lens,
            isBackCamera = config?.isBackCamera ?: true,
            resolvedIds = resolvedPhysicalIds
        )
        if (config != null &&
            config.isBackCamera &&
            isPhysicalLensBinding &&
            !config.isDualCapture &&
            targetPhysicalId != boundCameraId &&
            targetPhysicalId != pendingBindPhysicalId
        ) {
            if (activeRecording == null && activeProVideoSession == null) {
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

    private fun applyLensConfiguration(lens: OpticalLens, displayZoom: Float) {
        this.currentActiveLens = lens
        applySensorZoomRatio(displayZoom)
        applyInSensorCropTags(lens, displayZoom)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyInSensorCropTags(lens: OpticalLens, displayZoom: Float = lens.zoomRatio) {
        updateLiveViewfinderLut(currentAgcSettings.isLiveViewfinderLutEnabled, currentAgcSettings.activeProfile, lens, currentAgcSettings)
        val cam = camera ?: return
        try {
            val control2 = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            val isCrop2x: Byte = if (lens == OpticalLens.CROP_2X || (displayZoom >= 1.5f && displayZoom < 3.0f)) 1 else 0
            builder.setIfSupported(MtkMotoVendorTags.MOTO_IN_SENSOR_CROP_ENABLE, isCrop2x)
            builder.setIfSupported(MtkMotoVendorTags.MOTO_ZSL_CROP, isCrop2x)
            val oisMode = if (lastBindConfig?.isBackCamera == true && lens != OpticalLens.UW) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
            builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
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
        if (isAuxCameraActive) {
            val w = auxTextureView.width.coerceAtLeast(1).toFloat()
            val h = auxTextureView.height.coerceAtLeast(1).toFloat()
            auxCamera2Session.triggerTapToFocus(x / w, y / h, isAeLocked, isAfLocked)
            return
        }
        val cam = camera ?: return
        try {
            val activeRole = ViennaLensRouter.roleForOpticalLens(
                lens = currentActiveLens,
                isBackCamera = lastBindConfig?.isBackCamera ?: true
            )
            val isFixedFocus = ViennaLensRouter.isFixedFocusRole(activeRole, hardwareCapabilities.minFocusDistanceDiopters)
            val point = previewView.meteringPointFactory.createPoint(x, y)
            var flags = 0
            if (!isAfLocked && !isFixedFocus) {
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
        if (isAuxCameraActive) {
            auxCamera2Session.setAeLock(locked)
            return
        }
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
        if (isAuxCameraActive) {
            auxCamera2Session.setAfMode(mode, locked)
            return
        }
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
        if (isAuxCameraActive) {
            auxCamera2Session.setAeMode(mode, locked)
            return
        }
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
        if (isAuxCameraActive) {
            auxCamera2Session.unlockAf()
            return
        }
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
        if (isAuxCameraActive) {
            auxCamera2Session.setFlashMode(flashMode)
            return
        }
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
        if (!isAuxCameraActive && imageCapture == null) {
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
        val manualSnap = currentManualSettings
        val format = manualSnap.captureFormat
        val colorSpace = manualSnap.colorSpace
        val jpegQuality = manualSnap.jpegQuality
        val ratio = lastBindConfig?.aspectRatio ?: AspectRatio.RATIO_21_9

        // Thermal-aware burst budget & ZSL ring stats telemetry
        val recommendedBurst = PipelineTelemetry.updateThermalState(context, manualSnap.hdrFrameCount)
        PipelineTelemetry.recordZslStats(zslRingBuffer.slotCount, zslRingBuffer.totalDropped)
        val activeRawPlan = getActiveRawPlan(lens.zoomRatio)
        Log.d(TAG, "Capture plan: burst=$recommendedBurst, raw=${activeRawPlan.width}x${activeRawPlan.height}, physicalIds=$resolvedPhysicalIds")

        val gyroKernel: FloatArray? = null

        // Grab secondary preview frame on the UI thread at the instant of shutter tap
        val secBitmap = if (isConcurrentCameraActive) {
            try {
                secondaryPreviewView.bitmap
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture secondary preview frame for dual capture: ${e.message}")
                null
            }
        } else null

        val previewThumbBitmap = runCatching {
            if (isAuxCameraActive) auxTextureView.bitmap else previewView.bitmap
        }.getOrNull()
        val useMotoAiFallback = apu655Accelerator.shouldUseMotoAiFallback(manualSnap)
        val vevsMaskSnapshot: ByteArray? = null

        fun handleCapturedBytes(bytes: ByteArray, imageFormat: Int, rotationDegrees: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val timestamp = System.currentTimeMillis()
                    var dngUri: android.net.Uri? = null
                    var jpegUri: android.net.Uri? = null

                    if (format.isRaw) {
                        if (imageFormat == ImageFormat.RAW_SENSOR && hardwareCapabilities.isRawSupported) {
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
                            Log.w(
                                TAG,
                                "RAW requested, but delivered format=$imageFormat (JPEG); skipping fake DNG write and saving high-quality JPEG"
                            )
                        }
                    }

                    if (format.isJpeg || imageFormat != ImageFormat.RAW_SENSOR) {
                        var ownedThumb: android.graphics.Bitmap? = null
                        val thumbForNpu = previewThumbBitmap ?: runCatching {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }.getOrNull()?.also { ownedThumb = it }

                        val npuTelemetry = if (thumbForNpu != null) {
                            runCatching {
                                apu655Accelerator.evaluateSceneOnApu655(
                                    thumbnail256 = thumbForNpu,
                                    iso = 100,
                                    settings = manualSnap
                                )
                            }.getOrNull()
                        } else {
                            runCatching { apu655Accelerator.inspectTelemetry(manualSnap) }.getOrNull()
                        }
                        ownedThumb?.recycle()

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
                            watermarkStyle = manualSnap.watermarkStyle,
                            watermarkSignature = manualSnap.watermarkSignature,
                            useSignature27Pipeline = (profile == AgcColorProfile.SIGNATURE_LUT) && !useMotoAiFallback,
                            usePortraitDepthSlab = manualSnap.isPortraitDepthSlabEnabled,
                            useUltraHdrGainmap = manualSnap.isUltraHdrGainmapEnabled,
                            gyroPsfKernel = gyroKernel,
                            vevsEventMask = vevsMaskSnapshot
                        )

                        if (npuTelemetry != null) {
                            PipelineTelemetry.recordNpuTelemetry(npuTelemetry)
                        }

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

                    val primaryUri = jpegUri ?: dngUri
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

        if (isAuxCameraActive) {
            auxCamera2Session.captureStillPhoto(
                deviceOrientationAngle = deviceOrientationAngle,
                onCaptured = { bytes, rot ->
                    handleCapturedBytes(bytes, ImageFormat.JPEG, rot)
                },
                onError = { err ->
                    inFlightCaptures.decrementAndGet()
                    Log.e(TAG, "Aux photo capture failed: ${err.message}", err)
                }
            )
            return
        }

        val capture = imageCapture!!
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val imageFormat = image.format
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
                    handleCapturedBytes(bytes, imageFormat, rotationDegrees)
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
        val activeSession = activeProVideoSession
        if (activeSession != null) {
            Log.i(TAG, "Stopping GxvVideoCodecManager cinema recording session...")
            proVideoTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            proVideoTimerRunnable = null
            proAudioInputRouter.setPcmConsumer(null)
            if (isAuxCameraActive && !isAuxVideoShaderMode) {
                auxCamera2Session.reconfigureForRecording(null) {}
                proAudioInputRouter.stopMonitoring()
            } else {
                videoGlRenderer?.attachEncoderSurface(null, 0, 0)
            }
            activeProVideoSession = null
            onEvent?.invoke(GXVcamEvent.SetRecordingState(isRecording = false))
            CoroutineScope(Dispatchers.IO).launch {
                val uri = activeSession.stopAndSaveToMediaStore()
                if (uri != null) {
                    Log.i(TAG, "Pro Video saved to $uri")
                    mainHandler.post {
                        onEvent?.invoke(GXVcamEvent.OnPhotoCaptured(uri))
                    }
                }
            }
            return
        }

        val renderer = videoGlRenderer
        if ((renderer != null && currentVideoSettings.isProVideoEngineEnabled && !isConcurrentCameraActive) ||
            (isAuxCameraActive && !isConcurrentCameraActive)
        ) {
            val baseHeight = when (currentVideoSettings.resolution) {
                VideoResolution.UHD_4K -> 2160
                VideoResolution.FHD_1080P -> 1080
                VideoResolution.HD_720P -> 720
            }
            val videoRatio = lastBindConfig?.aspectRatio ?: AspectRatio.RATIO_16_9
            val (width, height) = when (videoRatio) {
                AspectRatio.RATIO_16_9 -> (baseHeight * 16 / 9) to baseHeight
                AspectRatio.RATIO_21_9 -> when (currentVideoSettings.resolution) {
                    VideoResolution.UHD_4K -> 3840 to 1648
                    VideoResolution.FHD_1080P -> 1920 to 816
                    VideoResolution.HD_720P -> 1280 to 544
                }
                AspectRatio.RATIO_4_3 -> when (currentVideoSettings.resolution) {
                    VideoResolution.UHD_4K -> 2880 to 2160
                    VideoResolution.FHD_1080P -> 1440 to 1080
                    VideoResolution.HD_720P -> 960 to 720
                }
                AspectRatio.RATIO_1_1 -> when (currentVideoSettings.resolution) {
                    VideoResolution.UHD_4K -> 2160 to 2160
                    VideoResolution.FHD_1080P -> 1088 to 1088
                    VideoResolution.HD_720P -> 720 to 720
                }
                AspectRatio.RATIO_3_2 -> when (currentVideoSettings.resolution) {
                    VideoResolution.UHD_4K -> 3232 to 2160
                    VideoResolution.FHD_1080P -> 1616 to 1080
                    VideoResolution.HD_720P -> 1088 to 720
                }
            }
            val fps = MotoVideoEngine.computeTargetFps(currentVideoSettings)
            val isBack = lastBindConfig?.isBackCamera ?: true
            val orientationHint = if (isBack) {
                (90 + deviceOrientationAngle) % 360
            } else {
                (270 - deviceOrientationAngle + 360) % 360
            }
            val hasAudioPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasAudioPerm) {
                proAudioInputRouter.startMonitoring()
            }

            val session = GxvVideoCodecManager.VideoRecordingSession(
                context = context,
                codecProfile = currentVideoSettings.codecProfile,
                colorProfile = currentVideoSettings.colorProfile,
                width = width,
                height = height,
                fps = fps,
                isAudioEnabled = hasAudioPerm,
                isStereoAudio = proAudioInputRouter.isStereoChannel(),
                orientationHintDegrees = orientationHint
            )
            if (session.start()) {
                activeProVideoSession = session
                if (isAuxCameraActive && !isAuxVideoShaderMode) {
                    auxCamera2Session.reconfigureForRecording(session.inputSurface) {}
                } else {
                    renderer?.attachEncoderSurface(session.inputSurface, width, height)
                }
                if (hasAudioPerm) {
                    proAudioInputRouter.setPcmConsumer { bytes, size, ptsUs ->
                        session.submitAudioPcm(bytes, size, ptsUs)
                    }
                }
                proVideoRecordingStartMs = SystemClock.elapsedRealtime()
                proVideoTimerRunnable?.let { mainHandler.removeCallbacks(it) }
                val timer = object : Runnable {
                    override fun run() {
                        if (activeProVideoSession === session) {
                            val elapsed = SystemClock.elapsedRealtime() - proVideoRecordingStartMs
                            onEvent?.invoke(GXVcamEvent.OnRecordingDurationUpdated(elapsed))
                            mainHandler.postDelayed(this, 250L)
                        }
                    }
                }
                proVideoTimerRunnable = timer
                mainHandler.postDelayed(timer, 250L)
                Log.i(
                    TAG,
                    "Started Video session (aux=$isAuxCameraActive, shader=$isAuxVideoShaderMode, ${currentVideoSettings.codecProfile.label}, ${width}x${height}@${fps}fps)"
                )
                onEvent?.invoke(GXVcamEvent.SetRecordingState(true))
                return
            } else {
                Log.w(TAG, "MediaCodec video session failed to start; checking VideoCapture fallback")
            }
        }

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
        proVideoTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        auxCamera2Session.closeSync()
        gyroMotionTracker?.stop()
        proAudioInputRouter.setPcmConsumer(null)
        activeProVideoSession?.let { session ->
            activeProVideoSession = null
            CoroutineScope(Dispatchers.IO).launch { session.stopAndSaveToMediaStore() }
        }
        proAudioInputRouter.close()
        videoGlRenderer?.close()
        videoGlRenderer = null
        virtualEvsSimulator.close()
        zslRingBuffer.close()
        apu655Accelerator.close()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
