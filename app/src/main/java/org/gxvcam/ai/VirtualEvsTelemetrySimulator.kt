package org.gxvcam.ai

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.Immutable
import org.gxvcam.camera.ViennaLensRouter
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

@Immutable
data class LightTelemetrySnapshot(
    val ambientLux: Float = 150f,
    val luxDerivativePerSec: Float = 0f,
    val sceneFlickerMode: Int = CaptureResult.STATISTICS_SCENE_FLICKER_NONE,
    val rollingShutterSkewNs: Long = 14_200_000L,
    val adaptiveContrastThreshold: Float = 0.13f
)

@Immutable
data class VirtualEvsOutput(
    val frameSequence: Long,
    val isHardwareEvsPresent: Boolean,
    val eventDensityRatio: Float,
    val recommendedShutterNs: Long,
    val ambientLux: Float,
    val sceneFlickerMode: Int,
    val eventPolarityMask256x192: ByteBuffer
)

/**
 * Virtual Event Vision Sensor (vEVS) simulator for Motorola Edge 50 Neo (`vienna` / `MT6878`).
 *
 * Fuses:
 * 1. `Sensor.TYPE_LIGHT` + Camera2 `STATISTICS_SCENE_FLICKER` + `SENSOR_ROLLING_SHUTTER_SKEW`
 *    to reject 50Hz/60Hz indoor AC lighting flicker across the LYT-700C rolling shutter readout.
 * 2. 400-800 Hz `Sensor.TYPE_GYROSCOPE` ego-motion warping to cancel hand shake.
 * 3. Internal MediaTek APU 655 (`mtk-neuron` INT8 via LiteRT) or analytical SIMD fallback
 *    to emit a continuous 256x192 (+1 ON / -1 OFF) neuromorphic event polarity mask.
 */
class VirtualEvsTelemetrySimulator(private val context: Context) : SensorEventListener, AutoCloseable {

    companion object {
        private const val TAG = "VirtualEvsSimulator"
        private const val MODEL_ASSET_PATH = "models/vevs_logluma_gru_int8.tflite"
        const val MASK_WIDTH = 256
        const val MASK_HEIGHT = 192
        const val MASK_PIXELS = MASK_WIDTH * MASK_HEIGHT
        private const val GYRO_RING_SIZE = 256

        fun computeAdaptiveContrastThreshold(lux: Float, flickerMode: Int): Float {
            val baseThreshold = when {
                lux < 15f -> 0.24f
                lux < 80f -> 0.18f
                else -> 0.13f
            }
            val flickerPenalty = if (flickerMode != CaptureResult.STATISTICS_SCENE_FLICKER_NONE) 0.04f else 0.0f
            return baseThreshold + flickerPenalty
        }

        fun computeFlickerCompensation(
            rowIndex: Int,
            height: Int,
            rollingShutterSkewNs: Long,
            flickerMode: Int
        ): Float {
            val flickerFreqHz = when (flickerMode) {
                CaptureResult.STATISTICS_SCENE_FLICKER_50HZ -> 100f
                CaptureResult.STATISTICS_SCENE_FLICKER_60HZ -> 120f
                else -> 0f
            }
            if (flickerFreqHz <= 0f || height <= 0) return 0f
            val rowReadoutSec = (rowIndex.toFloat() / height.toFloat()) * (rollingShutterSkewNs * 1e-9f)
            return 0.035f * cos(2.0 * Math.PI * flickerFreqHz * rowReadoutSec).toFloat()
        }

        /**
         * Analytical flicker-compensated, ego-motion-warped log-luminance event generator.
         * Writes INT8 polarity bytes (-127 OFF, 0 static, +127 ON) into [outMask] and returns active event count.
         */
        fun synthesizeAnalyticalEvents(
            prevLuma256x192: ByteArray,
            currLuma256x192: ByteArray,
            shiftX: Int,
            shiftY: Int,
            rollingShutterSkewNs: Long,
            flickerMode: Int,
            threshold: Float,
            outMask: ByteBuffer
        ): Int {
            var activeEvents = 0
            outMask.clear()
            for (y in 0 until MASK_HEIGHT) {
                val flickerComp = computeFlickerCompensation(y, MASK_HEIGHT, rollingShutterSkewNs, flickerMode)
                val py = (y + shiftY).coerceIn(0, MASK_HEIGHT - 1)
                val rowBase = y * MASK_WIDTH
                val prevRowBase = py * MASK_WIDTH
                for (x in 0 until MASK_WIDTH) {
                    val px = (x + shiftX).coerceIn(0, MASK_WIDTH - 1)
                    val iCurr = ((currLuma256x192[rowBase + x].toInt() and 0xFF) + 1) / 256f
                    val iPrev = ((prevLuma256x192[prevRowBase + px].toInt() and 0xFF) + 1) / 256f
                    val deltaLogLuma = (ln(iCurr) - ln(iPrev)) - flickerComp
                    val eventByte: Byte = when {
                        deltaLogLuma >= threshold -> {
                            activeEvents++
                            127.toByte()
                        }
                        deltaLogLuma <= -threshold -> {
                            activeEvents++
                            (-127).toByte()
                        }
                        else -> 0.toByte()
                    }
                    outMask.put(rowBase + x, eventByte)
                }
            }
            outMask.rewind()
            return activeEvents
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasPhysicalEvsHardware: Boolean = ViennaLensRouter.hasPhysicalEvsSensor(context)

    @Volatile private var currentLux: Float = 150f
    @Volatile private var prevLux: Float = 150f
    @Volatile private var prevLuxTimestampNs: Long = 0L
    @Volatile private var luxDerivative: Float = 0f

    @Volatile private var latestLightTelemetry: LightTelemetrySnapshot = LightTelemetrySnapshot()
    @Volatile private var lastBaseExposureNs: Long = 8_000_000L

    // Zero-allocation primitive ring buffer for 400-800 Hz gyro samples
    private val gyroLock = Any()
    private val gyroTs = LongArray(GYRO_RING_SIZE)
    private val gyroWx = FloatArray(GYRO_RING_SIZE)
    private val gyroWy = FloatArray(GYRO_RING_SIZE)
    private var gyroWriteIdx = 0
    private var gyroCount = 0

    // Pre-allocated luma history & double-buffered direct polarity masks (zero GC churn)
    private var prevLumaBuffer = ByteArray(MASK_PIXELS)
    private var currLumaBuffer = ByteArray(MASK_PIXELS)
    private var hasPrevFrame = false
    private val polarityBuffers = arrayOf(
        ByteBuffer.allocateDirect(MASK_PIXELS).order(ByteOrder.nativeOrder()),
        ByteBuffer.allocateDirect(MASK_PIXELS).order(ByteOrder.nativeOrder())
    )
    private var activeMaskSlot = 0
    private var frameSeq = 0L
    private val latestMaskBytes = ByteArray(MASK_PIXELS)
    @Volatile private var hasValidMask = false

    private val npuExecutor = Executors.newSingleThreadExecutor()
    private val isRegistered = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    private var nnApiDelegate: NnApiDelegate? = null
    private var vevsNpuInterpreter: Interpreter? = null
    private var npuInputTensor: ByteBuffer? = null

    init {
        npuExecutor.execute { initApu655VevsModel(strictInternalNpuOnly = true) }
    }

    @Synchronized
    private fun initApu655VevsModel(strictInternalNpuOnly: Boolean) {
        if (isClosed.get()) return
        runCatching {
            val nnOptions = NnApiDelegate.Options().apply {
                setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER)
                setUseNnapiCpu(!strictInternalNpuOnly)
                setAllowFp16(true)
                setAcceleratorName("mtk-neuron")
            }
            nnApiDelegate = NnApiDelegate(nnOptions)
            val options = Interpreter.Options().addDelegate(nnApiDelegate)
            context.assets.openFd(MODEL_ASSET_PATH).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    vevsNpuInterpreter = Interpreter(mapped, options)
                    npuInputTensor = ByteBuffer.allocateDirect(MASK_PIXELS * 2).order(ByteOrder.nativeOrder())
                }
            }
        }.onFailure {
            Log.d(TAG, "Bundled vEVS model '$MODEL_ASSET_PATH' absent; using flicker-compensated analytical vEVS engine")
            vevsNpuInterpreter = null
        }
    }

    fun start() {
        if (isClosed.get() || !isRegistered.compareAndSet(false, true)) return
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() {
        if (!isRegistered.compareAndSet(true, false)) return
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val now = event.timestamp
                val newLux = event.values[0]
                if (prevLuxTimestampNs > 0L && now > prevLuxTimestampNs) {
                    val dtSec = (now - prevLuxTimestampNs) * 1e-9f
                    luxDerivative = (newLux - prevLux) / max(dtSec, 1e-3f)
                }
                prevLux = currentLux
                currentLux = newLux
                prevLuxTimestampNs = now
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(gyroLock) {
                    gyroTs[gyroWriteIdx] = event.timestamp
                    gyroWx[gyroWriteIdx] = event.values[0]
                    gyroWy[gyroWriteIdx] = event.values[1]
                    gyroWriteIdx = (gyroWriteIdx + 1) % GYRO_RING_SIZE
                    if (gyroCount < GYRO_RING_SIZE) gyroCount++
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Captures flicker and rolling-shutter telemetry from the live Camera2 frame metadata.
     */
    fun captureLightTelemetry(result: TotalCaptureResult): LightTelemetrySnapshot {
        val flicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER)
            ?: CaptureResult.STATISTICS_SCENE_FLICKER_NONE
        val skewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 14_200_000L
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exp ->
            if (exp > 0L) lastBaseExposureNs = exp
        }

        val snapshot = LightTelemetrySnapshot(
            ambientLux = currentLux,
            luxDerivativePerSec = luxDerivative,
            sceneFlickerMode = flicker,
            rollingShutterSkewNs = skewNs,
            adaptiveContrastThreshold = computeAdaptiveContrastThreshold(currentLux, flicker)
        )
        latestLightTelemetry = snapshot
        return snapshot
    }

    fun getLatestLightTelemetry(): LightTelemetrySnapshot = latestLightTelemetry

    /**
     * Extracts a 256x192 luminance frame from CameraX YUV_420_888 [ImageProxy] without heap allocation
     * and synthesizes the latest flicker-compensated neuromorphic event output.
     */
    fun processYuvFrame(image: ImageProxy, rotationDegrees: Int): VirtualEvsOutput? {
        if (isClosed.get()) return null
        val yPlane = image.planes.firstOrNull() ?: return null
        val yBuffer = yPlane.buffer
        val srcW = image.width
        val srcH = image.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        if (srcW <= 0 || srcH <= 0) return null

        val rot = ((rotationDegrees % 360) + 360) % 360
        for (dy in 0 until MASK_HEIGHT) {
            val vNorm = dy.toFloat() / MASK_HEIGHT.toFloat()
            for (dx in 0 until MASK_WIDTH) {
                val uNorm = dx.toFloat() / MASK_WIDTH.toFloat()
                val (sx, sy) = when (rot) {
                    90 -> (vNorm * srcW).toInt() to ((1f - uNorm) * srcH).toInt()
                    180 -> ((1f - uNorm) * srcW).toInt() to ((1f - vNorm) * srcH).toInt()
                    270 -> ((1f - vNorm) * srcW).toInt() to (uNorm * srcH).toInt()
                    else -> (uNorm * srcW).toInt() to (vNorm * srcH).toInt()
                }
                val clampedX = sx.coerceIn(0, srcW - 1)
                val clampedY = sy.coerceIn(0, srcH - 1)
                val idx = clampedY * rowStride + clampedX * pixelStride
                currLumaBuffer[dy * MASK_WIDTH + dx] = yBuffer.get(idx)
            }
        }

        if (!hasPrevFrame) {
            System.arraycopy(currLumaBuffer, 0, prevLumaBuffer, 0, MASK_PIXELS)
            hasPrevFrame = true
            return null
        }

        val out = synthesizeNeuromorphicEvents(
            prevLuma256x192 = prevLumaBuffer,
            currLuma256x192 = currLumaBuffer,
            frameTimestampNs = image.imageInfo.timestamp,
            baseExposureNs = lastBaseExposureNs,
            telemetry = latestLightTelemetry
        )

        // Swap prev and curr references with zero array allocation
        val tmp = prevLumaBuffer
        prevLumaBuffer = currLumaBuffer
        currLumaBuffer = tmp
        return out
    }

    @Synchronized
    fun synthesizeNeuromorphicEvents(
        prevLuma256x192: ByteArray,
        currLuma256x192: ByteArray,
        frameTimestampNs: Long,
        baseExposureNs: Long,
        telemetry: LightTelemetrySnapshot
    ): VirtualEvsOutput {
        activeMaskSlot = 1 - activeMaskSlot
        val polarityMask = polarityBuffers[activeMaskSlot]
        polarityMask.clear()

        var sumWx = 0f
        var sumWy = 0f
        var count = 0
        synchronized(gyroLock) {
            for (i in 0 until gyroCount) {
                val ts = gyroTs[i]
                if (abs(ts - frameTimestampNs) <= 33_000_000L) {
                    sumWx += gyroWx[i]
                    sumWy += gyroWy[i]
                    count++
                }
            }
        }
        val shiftX = if (count > 0) ((sumWy / count) * 4.5f).toInt().coerceIn(-8, 8) else 0
        val shiftY = if (count > 0) (-(sumWx / count) * 4.5f).toInt().coerceIn(-8, 8) else 0

        val interpreter = vevsNpuInterpreter
        val inputTensor = npuInputTensor
        val activeEvents = if (interpreter != null && inputTensor != null) {
            inputTensor.clear()
            inputTensor.put(prevLuma256x192, 0, MASK_PIXELS)
            inputTensor.put(currLuma256x192, 0, MASK_PIXELS)
            inputTensor.rewind()
            interpreter.run(inputTensor, polarityMask)
            polarityMask.rewind()
            var n = 0
            for (i in 0 until MASK_PIXELS) {
                if (abs(polarityMask.get(i).toInt()) > 32) n++
            }
            n
        } else {
            synthesizeAnalyticalEvents(
                prevLuma256x192 = prevLuma256x192,
                currLuma256x192 = currLuma256x192,
                shiftX = shiftX,
                shiftY = shiftY,
                rollingShutterSkewNs = telemetry.rollingShutterSkewNs,
                flickerMode = telemetry.sceneFlickerMode,
                threshold = telemetry.adaptiveContrastThreshold,
                outMask = polarityMask
            )
        }

        polarityMask.rewind()
        polarityMask.get(latestMaskBytes, 0, MASK_PIXELS)
        polarityMask.rewind()
        hasValidMask = true

        val density = activeEvents.toFloat() / MASK_PIXELS.toFloat()
        val recommendedShutter = when {
            density > 0.12f -> minOf(baseExposureNs, 2_000_000L)
            density > 0.05f -> minOf(baseExposureNs, 4_000_000L)
            else -> baseExposureNs
        }

        frameSeq++
        return VirtualEvsOutput(
            frameSequence = frameSeq,
            isHardwareEvsPresent = hasPhysicalEvsHardware,
            eventDensityRatio = density,
            recommendedShutterNs = recommendedShutter,
            ambientLux = telemetry.ambientLux,
            sceneFlickerMode = telemetry.sceneFlickerMode,
            eventPolarityMask256x192 = polarityMask
        )
    }

    @Synchronized
    fun snapshotLatestEventMaskBytes(): ByteArray? {
        if (!hasValidMask) return null
        return latestMaskBytes.clone()
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        stop()
        npuExecutor.execute {
            runCatching { vevsNpuInterpreter?.close() }
            vevsNpuInterpreter = null
            runCatching { nnApiDelegate?.close() }
            nnApiDelegate = null
        }
        npuExecutor.shutdown()
    }
}
