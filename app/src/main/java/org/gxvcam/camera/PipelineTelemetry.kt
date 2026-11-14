package org.gxvcam.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.gxvcam.ai.NpuExecutionTelemetry

@Immutable
data class PipelineMetrics(
    val lastCaptureTotalMs: Long = 0L,
    val lastToneMapMs: Long = 0L,
    val lastDeblurMs: Long = 0L,
    val lastPortraitSlabMs: Long = 0L,
    val zslSlotsOccupied: Int = 0,
    val zslDroppedFrames: Int = 0,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val recommendedBurstFrames: Int = 8,
    val activePipelineName: String = "GXV Hybrid LUT",
    val npuTelemetry: NpuExecutionTelemetry? = null
)

/**
 * Observability & thermal throttling telemetry for GXVcam pipelines.
 * Automatically scales burst frame count down when Dimensity 7300 thermal status elevates.
 */
object PipelineTelemetry {

    private val _metrics = MutableStateFlow(PipelineMetrics())
    val metrics: StateFlow<PipelineMetrics> = _metrics.asStateFlow()

    fun updateThermalState(context: Context, requestedBurstFrames: Int): Int {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.currentThermalStatus
            }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val effectiveBurst = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> requestedBurstFrames.coerceAtMost(4)
            status >= PowerManager.THERMAL_STATUS_MODERATE -> requestedBurstFrames.coerceAtMost(6)
            else -> requestedBurstFrames.coerceIn(2, 14)
        }

        _metrics.update {
            it.copy(
                thermalStatus = status,
                recommendedBurstFrames = effectiveBurst
            )
        }
        return effectiveBurst
    }

    fun recordCaptureTimings(
        totalMs: Long,
        toneMapMs: Long,
        deblurMs: Long = 0L,
        portraitSlabMs: Long = 0L,
        pipelineName: String = "GXV Hybrid LUT"
    ) {
        _metrics.update {
            it.copy(
                lastCaptureTotalMs = totalMs,
                lastToneMapMs = toneMapMs,
                lastDeblurMs = deblurMs,
                lastPortraitSlabMs = portraitSlabMs,
                activePipelineName = pipelineName
            )
        }
    }

    fun recordZslStats(slotsOccupied: Int, droppedFrames: Int) {
        _metrics.update {
            it.copy(
                zslSlotsOccupied = slotsOccupied,
                zslDroppedFrames = droppedFrames
            )
        }
    }

    fun recordNpuTelemetry(telemetry: org.gxvcam.ai.NpuExecutionTelemetry) {
        _metrics.update {
            it.copy(
                npuTelemetry = telemetry,
                activePipelineName = if (telemetry.isMotoAiFallbackActive) {
                    "Built-in Moto AI (Imagiq 950 ISP)"
                } else {
                    "GXV Hybrid + ${telemetry.activeAcceleratorName}"
                }
            )
        }
    }
}
