package org.gxvcam.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import org.gxvcam.ui.state.AiProcessingBackend
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.NpuPowerProfile
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

@Immutable
data class NpuExecutionTelemetry(
    val activeAcceleratorName: String,
    val inferenceLatencyMs: Long,
    val isMotoAiFallbackActive: Boolean,
    val shadowLift: Float,
    val detailGain: Float,
    val dehazeVeil: Float,
    val lutWeights: FloatArray,
    val batteryPercent: Int = 100,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NpuExecutionTelemetry
        return activeAcceleratorName == other.activeAcceleratorName &&
            inferenceLatencyMs == other.inferenceLatencyMs &&
            isMotoAiFallbackActive == other.isMotoAiFallbackActive &&
            shadowLift == other.shadowLift &&
            detailGain == other.detailGain &&
            dehazeVeil == other.dehazeVeil &&
            lutWeights.contentEquals(other.lutWeights) &&
            batteryPercent == other.batteryPercent &&
            thermalStatus == other.thermalStatus
    }

    override fun hashCode(): Int {
        var result = activeAcceleratorName.hashCode()
        result = 31 * result + inferenceLatencyMs.hashCode()
        result = 31 * result + isMotoAiFallbackActive.hashCode()
        result = 31 * result + shadowLift.hashCode()
        result = 31 * result + detailGain.hashCode()
        result = 31 * result + dehazeVeil.hashCode()
        result = 31 * result + lutWeights.contentHashCode()
        result = 31 * result + batteryPercent
        result = 31 * result + thermalStatus
        return result
    }
}

/**
 * Power-aware MediaTek Dimensity 7300 (MT6878) APU 655 NPU / Mali-G615 hardware scheduler.
 *
 * Routes INT8 neural models through Android NNAPI (`mtk-neuron`) with `setUseNnapiCpu(false)`
 * when strict internal NPU execution is enabled, keeping Cortex-A78 CPU cores idle.
 */
class Apu655HardwareAccelerator(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "Apu655Accelerator"
        private const val MODEL_ASSET_PATH = "models/nilut_scene_predictor_int8.tflite"
        private const val INPUT_DIM = 256

        fun evaluateFallbackDecision(
            backend: AiProcessingBackend,
            batteryPercent: Int,
            thermalStatus: Int
        ): Boolean {
            return when (backend) {
                AiProcessingBackend.MOTO_AI_FALLBACK -> true
                AiProcessingBackend.GXV_HYBRID_AI -> false
                AiProcessingBackend.AUTO_BATTERY_FALLBACK -> {
                    batteryPercent in 1..20 || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                }
            }
        }
    }

    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null
    private var sceneInterpreter: Interpreter? = null
    private var activeProfile: NpuPowerProfile? = null
    private var activeInternalNpuOnly: Boolean? = null
    private var activeAcceleratorLabel: String = "MediaTek APU 655 (INT8 Eco)"

    private val reusableInputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * INPUT_DIM * INPUT_DIM * 3).order(ByteOrder.nativeOrder())
    private val reusablePixels = IntArray(INPUT_DIM * INPUT_DIM)
    private val reusableOutput = Array(1) { FloatArray(7) }

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun readBatteryPercent(): Int {
        return runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        }.getOrDefault(100)
    }

    fun readThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    /**
     * Determines whether GXVcam should route the current shot to Motorola's built-in
     * Moto AI / Imagiq 950 hardware ISP pipeline based on user choice, battery, or thermals.
     */
    fun shouldUseMotoAiFallback(settings: CameraManualSettings): Boolean {
        return evaluateFallbackDecision(
            backend = settings.aiBackend,
            batteryPercent = readBatteryPercent(),
            thermalStatus = readThermalStatus()
        )
    }

    /**
     * Re-initializes the LiteRT interpreter on the MediaTek APU 655 NPU or Mali-G615 GPU
     * when the user changes the NPU Power Profile or Strict NPU isolation toggle.
     */
    @Synchronized
    fun ensureConfigured(settings: CameraManualSettings) {
        if (activeProfile == settings.npuPowerProfile &&
            activeInternalNpuOnly == settings.useInternalNpuOnly &&
            (nnApiDelegate != null || gpuDelegate != null)
        ) {
            return
        }

        closeDelegates()
        activeProfile = settings.npuPowerProfile
        activeInternalNpuOnly = settings.useInternalNpuOnly

        val options = Interpreter.Options()

        when (settings.npuPowerProfile) {
            NpuPowerProfile.APU655_BATTERY_SAVER,
            NpuPowerProfile.APU655_SUSTAINED_SPEED -> {
                val execPref = if (settings.npuPowerProfile == NpuPowerProfile.APU655_BATTERY_SAVER) {
                    NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER
                } else {
                    NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                }

                val allowCpuFallback = !settings.useInternalNpuOnly
                val nnOptions = NnApiDelegate.Options().apply {
                    setExecutionPreference(execPref)
                    setAllowFp16(true)
                    setUseNnapiCpu(allowCpuFallback)
                    setAcceleratorName("mtk-neuron")
                }

                runCatching {
                    nnApiDelegate = NnApiDelegate(nnOptions)
                    options.addDelegate(nnApiDelegate)
                    activeAcceleratorLabel = if (settings.npuPowerProfile == NpuPowerProfile.APU655_BATTERY_SAVER) {
                        "MediaTek APU 655 NPU (Low-Power Eco)"
                    } else {
                        "MediaTek APU 655 NPU (Sustained Turbo)"
                    }
                }.onFailure { err ->
                    Log.d(TAG, "Named mtk-neuron NNAPI delegate unavailable (${err.message}); probing direct NNAPI")
                    runCatching {
                        val fallbackNnOptions = NnApiDelegate.Options().apply {
                            setExecutionPreference(execPref)
                            setAllowFp16(true)
                            setUseNnapiCpu(allowCpuFallback)
                        }
                        nnApiDelegate = NnApiDelegate(fallbackNnOptions)
                        options.addDelegate(nnApiDelegate)
                        activeAcceleratorLabel = "MediaTek APU 655 (NNAPI Direct)"
                    }.onFailure { fallbackErr ->
                        Log.w(TAG, "NNAPI delegate init failed: ${fallbackErr.message}")
                        activeAcceleratorLabel = if (settings.useInternalNpuOnly) {
                            "MediaTek APU 655 (Strict NPU Standby)"
                        } else {
                            "Cortex-A78 XNNPACK (CPU Fallback)"
                        }
                    }
                }
            }

            NpuPowerProfile.GPU_MALI_FP16 -> {
                val isSupported = runCatching { CompatibilityList().isDelegateSupportedOnThisDevice }
                    .getOrDefault(false)
                if (isSupported) {
                    runCatching {
                        gpuDelegate = GpuDelegate(CompatibilityList().bestOptionsForThisDevice)
                        options.addDelegate(gpuDelegate)
                        activeAcceleratorLabel = "ARM Mali-G615 MC2 (FP16 GPU)"
                    }.onFailure {
                        options.setNumThreads(2)
                        activeAcceleratorLabel = "Cortex-A78 XNNPACK (2 Threads)"
                    }
                } else {
                    options.setNumThreads(2)
                    activeAcceleratorLabel = "Cortex-A78 XNNPACK (2 Threads)"
                }
            }
        }

        runCatching {
            sceneInterpreter = Interpreter(loadMappedAsset(MODEL_ASSET_PATH), options)
        }.onFailure {
            Log.d(TAG, "Bundled model '$MODEL_ASSET_PATH' not present; using analytic ISP telemetry heuristics")
            sceneInterpreter = null
        }
    }

    /**
     * Returns current scheduler telemetry snapshot without running full frame inference.
     */
    @Synchronized
    fun inspectTelemetry(settings: CameraManualSettings, lastLatencyMs: Long = 0L): NpuExecutionTelemetry {
        ensureConfigured(settings)
        val battery = readBatteryPercent()
        val thermal = readThermalStatus()
        val fallbackActive = evaluateFallbackDecision(settings.aiBackend, battery, thermal)
        return NpuExecutionTelemetry(
            activeAcceleratorName = if (fallbackActive) "Built-in Moto AI (Imagiq 950 ISP)" else activeAcceleratorLabel,
            inferenceLatencyMs = lastLatencyMs,
            isMotoAiFallbackActive = fallbackActive,
            shadowLift = 0.12f,
            detailGain = 1.15f,
            dehazeVeil = 0.06f,
            lutWeights = floatArrayOf(0.70f, 0.10f, 0.20f, 0.0f),
            batteryPercent = battery,
            thermalStatus = thermal
        )
    }

    /**
     * Runs scene-adaptive 3D-LUT weight & ICtCp tone prediction on the internal NPU.
     */
    @Synchronized
    fun evaluateSceneOnApu655(
        thumbnail256: Bitmap,
        iso: Int,
        settings: CameraManualSettings
    ): NpuExecutionTelemetry {
        val startMs = SystemClock.elapsedRealtime()
        ensureConfigured(settings)

        val battery = readBatteryPercent()
        val thermal = readThermalStatus()
        val fallbackActive = evaluateFallbackDecision(settings.aiBackend, battery, thermal)

        val interpreter = sceneInterpreter
        if (interpreter == null || fallbackActive) {
            val scaledThumb = if (thumbnail256.width != INPUT_DIM || thumbnail256.height != INPUT_DIM) {
                Bitmap.createScaledBitmap(thumbnail256, INPUT_DIM, INPUT_DIM, false)
            } else {
                thumbnail256
            }
            var lumaSum = 0L
            scaledThumb.getPixels(reusablePixels, 0, INPUT_DIM, 0, 0, INPUT_DIM, INPUT_DIM)
            var i = 0
            while (i < reusablePixels.size) {
                val px = reusablePixels[i]
                lumaSum += ((px shr 16) and 0xFF) * 3 + ((px shr 8) and 0xFF) * 6 + (px and 0xFF)
                i += 16
            }
            if (scaledThumb !== thumbnail256) scaledThumb.recycle()
            val meanLuma = (lumaSum.toFloat() / ((reusablePixels.size / 16) * 10f)) / 255f
            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val dynamicShadowLift = if (meanLuma < 0.28f) 0.22f else 0.10f
            return NpuExecutionTelemetry(
                activeAcceleratorName = if (fallbackActive) "Built-in Moto AI (Imagiq 950 ISP)" else activeAcceleratorLabel,
                inferenceLatencyMs = elapsedMs,
                isMotoAiFallbackActive = fallbackActive,
                shadowLift = dynamicShadowLift,
                detailGain = if (iso > 1600) 1.04f else 1.16f,
                dehazeVeil = if (meanLuma > 0.45f) 0.08f else 0.04f,
                lutWeights = floatArrayOf(0.70f, 0.10f, 0.20f, 0.0f),
                batteryPercent = battery,
                thermalStatus = thermal
            )
        }

        val scaled = if (thumbnail256.width != INPUT_DIM || thumbnail256.height != INPUT_DIM) {
            Bitmap.createScaledBitmap(thumbnail256, INPUT_DIM, INPUT_DIM, true)
        } else {
            thumbnail256
        }
        val argb = if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false)
        }
        try {
            reusableInputBuffer.clear()
            argb.getPixels(reusablePixels, 0, INPUT_DIM, 0, 0, INPUT_DIM, INPUT_DIM)
            for (px in reusablePixels) {
                reusableInputBuffer.put(((px shr 16) and 0xFF).toByte())
                reusableInputBuffer.put(((px shr 8) and 0xFF).toByte())
                reusableInputBuffer.put((px and 0xFF).toByte())
            }
            reusableInputBuffer.rewind()

            interpreter.run(reusableInputBuffer, reusableOutput)
            val res = reusableOutput[0]
            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)

            return NpuExecutionTelemetry(
                activeAcceleratorName = activeAcceleratorLabel,
                inferenceLatencyMs = elapsedMs,
                isMotoAiFallbackActive = false,
                shadowLift = res[4].coerceIn(0.10f, 0.38f),
                detailGain = if (iso > 1600) res[5].coerceIn(0.95f, 1.10f) else res[5].coerceIn(1.0f, 1.25f),
                dehazeVeil = res[6].coerceIn(0.0f, 0.30f),
                lutWeights = floatArrayOf(res[0], res[1], res[2], res[3]),
                batteryPercent = battery,
                thermalStatus = thermal
            )
        } finally {
            if (argb !== scaled && argb !== thumbnail256) argb.recycle()
            if (scaled !== thumbnail256) scaled.recycle()
        }
    }

    private fun loadMappedAsset(path: String): MappedByteBuffer {
        context.assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun closeDelegates() {
        runCatching { sceneInterpreter?.close() }
        sceneInterpreter = null
        runCatching { gpuDelegate?.close() }
        gpuDelegate = null
        runCatching { nnApiDelegate?.close() }
        nnApiDelegate = null
    }

    @Synchronized
    override fun close() {
        closeDelegates()
    }
}
