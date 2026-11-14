package org.gxvcam.video

import android.hardware.camera2.params.RggbChannelVector
import androidx.compose.runtime.Immutable
import org.gxvcam.camera.ViennaLensRouter.LensRole
import java.util.Locale
import kotlin.math.abs

@Immutable
data class CrossLensColorState(
    val activeRole: LensRole = LensRole.MAIN,
    val standbyPrewarmRole: LensRole? = null,
    val unifiedRggbGains: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
    val transitionBlendFactor: Float = 1.0f,
    val isTransitioning: Boolean = false
) {
    /** Normalized 3-channel RGB white-balance multiplier for the GLES fragment shader. */
    fun toRgbShaderMultipliers(): FloatArray {
        val gAvg = ((unifiedRggbGains.getOrElse(1) { 1f } + unifiedRggbGains.getOrElse(2) { 1f }) * 0.5f)
            .coerceAtLeast(0.01f)
        return floatArrayOf(
            (unifiedRggbGains.getOrElse(0) { 1f } / gAvg).coerceIn(0.4f, 2.5f),
            1.0f,
            (unifiedRggbGains.getOrElse(3) { 1f } / gAvg).coerceIn(0.4f, 2.5f)
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CrossLensColorState) return false
        return activeRole == other.activeRole &&
            standbyPrewarmRole == other.standbyPrewarmRole &&
            unifiedRggbGains.contentEquals(other.unifiedRggbGains) &&
            transitionBlendFactor == other.transitionBlendFactor &&
            isTransitioning == other.isTransitioning
    }

    override fun hashCode(): Int {
        var result = activeRole.hashCode()
        result = 31 * result + (standbyPrewarmRole?.hashCode() ?: 0)
        result = 31 * result + unifiedRggbGains.contentHashCode()
        result = 31 * result + transitionBlendFactor.hashCode()
        result = 31 * result + isTransitioning.hashCode()
        return result
    }
}

/**
 * Orchestrates seamless optical lens handoff, standby sensor pre-warming,
 * cross-lens EMA AWB gain locking, 12-frame transition cross-dissolve, and
 * 180-degree cinema shutter locking on the Motorola Edge 50 Neo (`vienna`).
 */
class SeamlessLensZoomController(
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
    private val dissolveTotalFrames: Int = DEFAULT_DISSOLVE_FRAMES
) {
    companion object {
        const val DEFAULT_EMA_ALPHA = 0.18f
        const val DEFAULT_DISSOLVE_FRAMES = 12

        // Edge 50 Neo relative sensor WB calibration offsets anchored to Main Sony LYT-700C
        private val UW_CALIBRATION = floatArrayOf(0.97f, 1.0f, 1.0f, 1.05f)
        private val MAIN_CALIBRATION = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        private val TELE_CALIBRATION = floatArrayOf(1.04f, 1.0f, 1.0f, 0.96f)

        fun compute180ShutterExposureNs(fps: Int): Long {
            val safeFps = fps.coerceIn(24, 120)
            return 1_000_000_000L / (2L * safeFps.toLong())
        }

        fun format180ShutterLabel(fps: Int): String {
            val safeFps = fps.coerceIn(24, 120)
            return String.format(Locale.US, "1/%ds (180°)", safeFps * 2)
        }
    }

    private var activeRole: LensRole = LensRole.MAIN
    private var lastPrewarmRole: LensRole? = null
    private var mainAnchorGains: FloatArray = floatArrayOf(1.85f, 1.0f, 1.0f, 1.65f)
    private var smoothedGains: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    private var transitionFramesRemaining: Int = 0
    private var hasInitializedGains: Boolean = false

    @Synchronized
    fun reset(initialRole: LensRole = LensRole.MAIN) {
        activeRole = initialRole
        lastPrewarmRole = null
        mainAnchorGains = floatArrayOf(1.85f, 1.0f, 1.0f, 1.65f)
        smoothedGains = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        transitionFramesRemaining = 0
        hasInitializedGains = false
    }

    /**
     * Resolves which physical Edge 50 Neo lens should drive a given zoom ratio using
     * boundary hysteresis to avoid rapid lens ping-ponging around 1.0x and 3.0x.
     */
    @Synchronized
    fun updateZoom(
        zoomRatio: Float,
        onPrewarmSensor: ((LensRole) -> Unit)? = null
    ): CrossLensColorState {
        val nextRole = when (activeRole) {
            LensRole.ULTRA_WIDE -> when {
                zoomRatio >= 3.02f -> LensRole.TELE_3X
                zoomRatio >= 1.02f -> LensRole.MAIN
                else -> LensRole.ULTRA_WIDE
            }
            LensRole.MAIN -> when {
                zoomRatio <= 0.94f -> LensRole.ULTRA_WIDE
                zoomRatio >= 3.02f -> LensRole.TELE_3X
                else -> LensRole.MAIN
            }
            LensRole.TELE_3X -> when {
                zoomRatio <= 0.94f -> LensRole.ULTRA_WIDE
                zoomRatio <= 2.85f -> LensRole.MAIN
                else -> LensRole.TELE_3X
            }
            else -> when {
                zoomRatio < 1.0f -> LensRole.ULTRA_WIDE
                zoomRatio >= 3.0f -> LensRole.TELE_3X
                else -> LensRole.MAIN
            }
        }

        if (nextRole != activeRole) {
            activeRole = nextRole
            transitionFramesRemaining = dissolveTotalFrames
        }

        val prewarmTarget = when {
            activeRole == LensRole.ULTRA_WIDE && zoomRatio in 0.85f..1.05f -> LensRole.MAIN
            activeRole == LensRole.MAIN && zoomRatio in 0.90f..1.08f -> LensRole.ULTRA_WIDE
            activeRole == LensRole.MAIN && zoomRatio in 2.65f..3.05f -> LensRole.TELE_3X
            activeRole == LensRole.TELE_3X && zoomRatio in 2.80f..3.10f -> LensRole.MAIN
            else -> null
        }

        if (prewarmTarget != null && prewarmTarget != lastPrewarmRole) {
            lastPrewarmRole = prewarmTarget
            onPrewarmSensor?.invoke(prewarmTarget)
        } else if (prewarmTarget == null) {
            lastPrewarmRole = null
        }

        return currentState()
    }

    /**
     * Incorporates Camera2 [RggbChannelVector] gains for the current sensor and applies
     * Exponential Moving Average smoothing anchored to the Main LYT-700C color balance.
     */
    @Synchronized
    fun onColorCorrectionGains(role: LensRole, gains: RggbChannelVector): CrossLensColorState {
        return onColorCorrectionSample(
            role = role,
            r = gains.red,
            gEven = gains.greenEven,
            gOdd = gains.greenOdd,
            b = gains.blue
        )
    }

    @Synchronized
    fun onColorCorrectionSample(
        role: LensRole,
        r: Float,
        gEven: Float,
        gOdd: Float,
        b: Float
    ): CrossLensColorState {
        val gNorm = ((gEven + gOdd) * 0.5f).coerceAtLeast(0.01f)
        val normSample = floatArrayOf(
            (r / gNorm).coerceIn(0.4f, 4.0f),
            1.0f,
            1.0f,
            (b / gNorm).coerceIn(0.4f, 4.0f)
        )

        if (role == LensRole.MAIN) {
            mainAnchorGains = normSample
        }

        val calibration = when (role) {
            LensRole.ULTRA_WIDE -> UW_CALIBRATION
            LensRole.TELE_3X -> TELE_CALIBRATION
            else -> MAIN_CALIBRATION
        }

        // Compute relative multiplier so UW and Tele converge smoothly toward Main anchor WB
        val target = FloatArray(4) { i ->
            val ratio = (mainAnchorGains[i] / normSample[i].coerceAtLeast(0.05f)) * calibration[i]
            ratio.coerceIn(0.6f, 1.6f)
        }

        if (!hasInitializedGains) {
            smoothedGains = target
            hasInitializedGains = true
        } else {
            val alpha = emaAlpha.coerceIn(0.02f, 0.9f)
            for (i in 0 until 4) {
                smoothedGains[i] = smoothedGains[i] * (1f - alpha) + target[i] * alpha
            }
        }

        return currentState()
    }

    /** Advances the 12-frame (~200ms @ 60fps) lens handoff cross-dissolve envelope. */
    @Synchronized
    fun advanceFrame(): CrossLensColorState {
        if (transitionFramesRemaining > 0) {
            transitionFramesRemaining -= 1
        }
        return currentState()
    }

    @Synchronized
    fun currentState(): CrossLensColorState {
        val blend = if (dissolveTotalFrames <= 0 || transitionFramesRemaining <= 0) {
            1.0f
        } else {
            (1.0f - (transitionFramesRemaining.toFloat() / dissolveTotalFrames.toFloat())).coerceIn(0f, 1f)
        }
        return CrossLensColorState(
            activeRole = activeRole,
            standbyPrewarmRole = lastPrewarmRole,
            unifiedRggbGains = smoothedGains.copyOf(),
            transitionBlendFactor = blend,
            isTransitioning = transitionFramesRemaining > 0 && abs(1.0f - blend) > 0.001f
        )
    }
}
