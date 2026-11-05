package org.gxvcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.gxvcam.NativeEngineBridge

sealed class CameraUiState {
    data object Idle : CameraUiState()
    data object Opening : CameraUiState()
    data object Ready : CameraUiState()
    data object Capturing : CameraUiState()
    data class Error(val message: String) : CameraUiState()
}

class Camera2Controller(private val context: Context) {

    companion object {
        private const val TAG = "Camera2Controller"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var rawImageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Idle)
    val uiState: StateFlow<CameraUiState> = _uiState

    private val _activeAuxButton = MutableStateFlow(DeviceCameraProfiles.SGCAM_AUX_BUTTONS[1]) // "1x"
    val activeAuxButton: StateFlow<AuxLensButton> = _activeAuxButton

    private val _currentSensor = MutableStateFlow(DeviceCameraProfiles.EDGE_50_NEO_MAIN)
    val currentSensor: StateFlow<CameraSensorSpec> = _currentSensor

    // Settings State
    var flashMode = "OFF" // OFF, AUTO, ON
    var isAwbEnabled = true
    var hdrMode = "HDR+" // OFF, HDR+, HDR_ENHANCED
    var currentEv = 0f // -2.0f to +2.0f
    private var currentZoomRatio = 1.0f

    // Pro Mode & Ultra-Res State
    var isProMode = false
    var isUltraRes50M = false
    var manualIso: Int? = null // null = Auto, or 100..19200
    var manualExposureNs: Long? = null // null = Auto, or up to 32,000,000,000ns (32s)
    var manualFocusDist: Float? = null // null = Auto, 0.0f = Infinity, 10.0f = Macro

    private var previewSurface: Surface? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("GXVcamBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(sensorSpec: CameraSensorSpec, previewSurface: Surface) {
        this.previewSurface = previewSurface
        _currentSensor.value = sensorSpec
        _uiState.value = CameraUiState.Opening

        try {
            cameraManager.openCamera(
                sensorSpec.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(sensorSpec)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        _uiState.value = CameraUiState.Idle
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        _uiState.value = CameraUiState.Error("Camera open error: $error")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            _uiState.value = CameraUiState.Error(e.message ?: "Unknown camera error")
        }
    }

    fun switchAuxLens(auxButton: AuxLensButton) {
        _activeAuxButton.value = auxButton
        val targetSpec = auxButton.sensorSpec
        val targetZoom = auxButton.zoomRatio

        if (cameraDevice != null && _currentSensor.value.cameraId == targetSpec.cameraId) {
            // Same sensor (e.g. 1x -> 2x or 3x -> 6x): apply digital crop zoom without restarting session
            setZoomRatio(targetZoom)
        } else {
            // Different physical sensor (e.g. 1x -> 3x or 1x -> .6): switch hardware device
            closeSessionOnly()
            currentZoomRatio = targetZoom
            val surface = previewSurface ?: return
            openCamera(targetSpec, surface)
        }
    }

    fun toggleCameraFacing() {
        val nextSensor = if (_currentSensor.value.role == LensRole.FRONT) {
            DeviceCameraProfiles.EDGE_50_NEO_MAIN
        } else {
            DeviceCameraProfiles.EDGE_50_NEO_FRONT
        }
        closeSessionOnly()
        currentZoomRatio = 1.0f
        val surface = previewSurface ?: return
        openCamera(nextSensor, surface)
    }

    private fun setZoomRatio(zoomRatio: Float) {
        currentZoomRatio = zoomRatio
        updatePreviewRepeatingRequest()
    }

    fun setExposureCompensation(evDelta: Float) {
        currentEv = evDelta
        updatePreviewRepeatingRequest()
    }

    fun setManualIso(iso: Int?) {
        manualIso = iso
        updatePreviewRepeatingRequest()
    }

    fun setManualExposureNs(expNs: Long?) {
        manualExposureNs = expNs
        updatePreviewRepeatingRequest()
    }

    fun setManualFocusDist(dist: Float?) {
        manualFocusDist = dist
        updatePreviewRepeatingRequest()
    }

    private fun createCaptureSession(spec: CameraSensorSpec) {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

        // Configure RAW_SENSOR ImageReader for 16-bit Bayer capture
        rawImageReader = ImageReader.newInstance(
            spec.rawSize.width,
            spec.rawSize.height,
            ImageFormat.RAW_SENSOR,
            5 // Ring buffer depth
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processRawImage(image, spec)
                    image.close()
                }
            }, backgroundHandler)
        }

        val surfaces = listOf(surface, rawImageReader!!.surface)

        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview(session, surface)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    _uiState.value = CameraUiState.Error("Failed to configure capture session")
                }
            },
            backgroundHandler
        )
    }

    private fun startPreview(session: CameraCaptureSession, previewSurface: Surface) {
        val device = cameraDevice ?: return
        try {
            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                applyCommonRequestSettings(this)
            }

            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
            _uiState.value = CameraUiState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview: ${e.message}", e)
            _uiState.value = CameraUiState.Error("Preview error: ${e.message}")
        }
    }

    private fun updatePreviewRepeatingRequest() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCommonRequestSettings(this)
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview request: ${e.message}")
        }
    }

    private fun applyCommonRequestSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

        // Apply Flash
        when (flashMode) {
            "ON" -> builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            "AUTO" -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            else -> builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        // Apply AWB
        if (isAwbEnabled) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        // Apply Pro Manual Controls
        if (isProMode) {
            if (manualIso != null || manualExposureNs != null) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                manualExposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            }
            manualFocusDist?.let {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
        }

        // Apply Zoom Ratio (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        }

        // Apply MediaTek & Motorola Vendor Tags
        MtkMotoVendorTags.MTK_ZSL_MODE?.let { key -> builder.set(key, 1) }
        MtkMotoVendorTags.MOTO_ZSL_MODE?.let { key -> builder.set(key, 1) }
        if (isUltraRes50M) {
            MtkMotoVendorTags.MOTO_DO_REMOSAIC?.let { key -> builder.set(key, 1) }
        }
    }

    fun takePicture() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val rawSurface = rawImageReader?.surface ?: return

        _uiState.value = CameraUiState.Capturing

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawSurface)
                previewSurface?.let { addTarget(it) }

                applyCommonRequestSettings(this)

                // Inject MediaTek vendor tags for burst / ISP tuning
                val burstCount = if (hdrMode == "HDR_ENHANCED") 12 else 8
                MtkMotoVendorTags.MTK_ISP_FRAME_COUNT?.let { set(it, burstCount) }
                MtkMotoVendorTags.MTK_ISP_FRAME_INDEX?.let { set(it, 0) }
                MtkMotoVendorTags.MOTO_ADRC_ENABLE?.let { set(it, 1) }
            }

            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.i(TAG, "Capture completed successfully")
                        _uiState.value = CameraUiState.Ready
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Capture failed: ${failure.reason}")
                        _uiState.value = CameraUiState.Ready
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take picture: ${e.message}", e)
            _uiState.value = CameraUiState.Ready
        }
    }

    private fun processRawImage(image: android.media.Image, spec: CameraSensorSpec) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val size = buffer.remaining()

        Log.i(TAG, "Processing RAW frame: ${image.width}x${image.height}, stride: $rowStride, bytes: $size")

        val success = NativeEngineBridge.unpackRaw16Native(
            directBuffer = buffer,
            bufferSize = size,
            width = image.width,
            height = image.height,
            rowStrideBytes = rowStride,
            whiteLevel = spec.whiteLevel,
            blackLevel = spec.defaultBlackLevel
        )

        Log.i(TAG, "Native RAW unpack result: $success")
    }

    private fun closeSessionOnly() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        rawImageReader?.close()
        rawImageReader = null
    }

    fun closeCamera() {
        closeSessionOnly()
        _uiState.value = CameraUiState.Idle
    }
}
