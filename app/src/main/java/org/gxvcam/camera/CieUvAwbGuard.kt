package org.gxvcam.camera

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.hypot
import kotlin.math.pow

/**
 * CIE 1976 (u', v') Automatic White Balance Guard for Signature 27.
 *
 * Detects non-Planckian chromaticity drift (such as magenta/purple stage LEDs or
 * sodium-vapor casts) from Camera2 RGB gains and blends 40% toward a Gray-World +
 * Planckian/skin-locus prior whenever distance exceeds `DELTA_UV_THRESHOLD` (0.038).
 */
object CieUvAwbGuard {

    const val DELTA_UV_THRESHOLD = 0.038f
    const val BLEND_PRIOR_WEIGHT = 0.40f

    // D65 neutral illuminant in CIE 1976 (u', v')
    private const val D65_U_PRIME = 0.19783f
    private const val D65_V_PRIME = 0.46832f

    // Warm skin-locus anchor in CIE 1976 (u', v')
    private const val SKIN_LOCUS_U_PRIME = 0.2140f
    private const val SKIN_LOCUS_V_PRIME = 0.4820f

    data class GuardResult(
        val rGain: Float,
        val gEvenGain: Float,
        val gOddGain: Float,
        val bGain: Float,
        val uPrime: Float,
        val vPrime: Float,
        val deltaUv: Float,
        val wasClamped: Boolean
    ) {
        fun toRggbChannelVector(): RggbChannelVector =
            RggbChannelVector(rGain, gEvenGain, gOddGain, bGain)
    }

    /**
     * Converts linear RGB channel values to CIE 1976 (u', v') coordinates.
     */
    fun rgbToCie1976Uv(r: Float, g: Float, b: Float): Pair<Float, Float> {
        val rr = r.coerceAtLeast(1e-4f)
        val gg = g.coerceAtLeast(1e-4f)
        val bb = b.coerceAtLeast(1e-4f)

        val x = 0.4124564f * rr + 0.3575761f * gg + 0.1804375f * bb
        val y = 0.2126729f * rr + 0.7151522f * gg + 0.0721750f * bb
        val z = 0.0193339f * rr + 0.1191920f * gg + 0.9503041f * bb

        val denom = (x + 15f * y + 3f * z).coerceAtLeast(1e-5f)
        val uPrime = (4f * x) / denom
        val vPrime = (9f * y) / denom
        return uPrime to vPrime
    }

    /**
     * Approximates the closest point on the Planckian locus (2500K - 10000K) in CIE 1976 (u', v')
     * using McCamy/CIE 1960 UCS polynomial mapping converted to CIE 1976 (v' = 1.5 * v).
     */
    fun closestPlanckianUv(uPrime: Float, vPrime: Float): Pair<Float, Float> {
        var bestDist = Float.MAX_VALUE
        var bestU = D65_U_PRIME
        var bestV = D65_V_PRIME

        var tempK = 2500f
        while (tempK <= 10000f) {
            val invT = 1000f / tempK
            val x = if (tempK <= 4000f) {
                -0.2661239f * invT.pow(3) - 0.2343589f * invT.pow(2) + 0.8776956f * invT + 0.179910f
            } else {
                -3.0258469f * invT.pow(3) + 2.1070379f * invT.pow(2) + 0.2226347f * invT + 0.240390f
            }
            val y = if (tempK <= 2222f) {
                -1.1063814f * x.pow(3) - 1.3481102f * x.pow(2) + 2.18555832f * x - 0.20219683f
            } else if (tempK <= 4000f) {
                -0.9549476f * x.pow(3) - 1.37418593f * x.pow(2) + 2.09137015f * x - 0.16748867f
            } else {
                3.0817580f * x.pow(3) - 5.87338670f * x.pow(2) + 3.75112997f * x - 0.37001483f
            }

            val denom = (-2f * x + 12f * y + 3f).coerceAtLeast(1e-5f)
            val pu = (4f * x) / denom
            val pv = (9f * y) / denom
            val d = hypot(uPrime - pu, vPrime - pv)
            if (d < bestDist) {
                bestDist = d
                bestU = pu
                bestV = pv
            }
            tempK += 250f
        }
        return bestU to bestV
    }

    /**
     * Evaluates Camera2 RGB gains (`rGain`, `gGain`, `bGain`) and guards against non-Planckian drift.
     * When `deltaUv > 0.038`, blends 40% toward the Gray-World + Planckian/skin-locus prior.
     */
    fun guardGains(
        rGain: Float,
        gEvenGain: Float,
        gOddGain: Float,
        bGain: Float,
        hasFace: Boolean = false
    ): GuardResult {
        val gAvg = (0.5f * (gEvenGain + gOddGain)).coerceAtLeast(0.5f)
        val normR = (rGain / gAvg).coerceIn(0.4f, 5.0f)
        val normB = (bGain / gAvg).coerceIn(0.4f, 5.0f)

        // Illuminant color estimated as inverse of sensor WB compensation gains
        val illumR = 1.0f / normR
        val illumG = 1.0f
        val illumB = 1.0f / normB

        val (uPrime, vPrime) = rgbToCie1976Uv(illumR, illumG, illumB)
        val (planckU, planckV) = closestPlanckianUv(uPrime, vPrime)
        val deltaUv = hypot(uPrime - planckU, vPrime - planckV)

        if (deltaUv <= DELTA_UV_THRESHOLD) {
            return GuardResult(
                rGain = rGain,
                gEvenGain = gEvenGain,
                gOddGain = gOddGain,
                bGain = bGain,
                uPrime = uPrime,
                vPrime = vPrime,
                deltaUv = deltaUv,
                wasClamped = false
            )
        }

        // Blend 40% toward Gray-World + skin-locus prior when faces are present
        val targetU = if (hasFace) {
            0.70f * planckU + 0.30f * SKIN_LOCUS_U_PRIME
        } else {
            0.75f * planckU + 0.25f * D65_U_PRIME
        }
        val targetV = if (hasFace) {
            0.70f * planckV + 0.30f * SKIN_LOCUS_V_PRIME
        } else {
            0.75f * planckV + 0.25f * D65_V_PRIME
        }

        val blendedU = uPrime + BLEND_PRIOR_WEIGHT * (targetU - uPrime)
        val blendedV = vPrime + BLEND_PRIOR_WEIGHT * (targetV - vPrime)

        // Convert blended (u', v') back to xy -> XYZ -> linear sRGB illuminant -> WB gains
        val xyDenom = (6f * blendedU - 16f * blendedV + 12f).coerceAtLeast(1e-5f)
        val cx = (9f * blendedU) / xyDenom
        val cy = (4f * blendedV) / xyDenom
        val cz = (1f - cx - cy).coerceAtLeast(1e-4f)

        val yLum = 1.0f
        val xLum = (cx / cy.coerceAtLeast(1e-4f)) * yLum
        val zLum = (cz / cy.coerceAtLeast(1e-4f)) * yLum

        val outIllumR = (3.2404542f * xLum - 1.5371385f * yLum - 0.4985314f * zLum).coerceAtLeast(0.15f)
        val outIllumG = (-0.9692660f * xLum + 1.8760108f * yLum + 0.0415560f * zLum).coerceAtLeast(0.15f)
        val outIllumB = (0.0556434f * xLum - 0.2040259f * yLum + 1.0572252f * zLum).coerceAtLeast(0.15f)

        val clampedRGain = ((outIllumG / outIllumR) * gAvg).coerceIn(0.75f, 3.8f)
        val clampedBGain = ((outIllumG / outIllumB) * gAvg).coerceIn(0.75f, 3.8f)

        return GuardResult(
            rGain = clampedRGain,
            gEvenGain = gEvenGain,
            gOddGain = gOddGain,
            bGain = clampedBGain,
            uPrime = blendedU,
            vPrime = blendedV,
            deltaUv = deltaUv,
            wasClamped = true
        )
    }

    fun guardVector(vector: RggbChannelVector, hasFace: Boolean = false): GuardResult {
        return guardGains(
            rGain = vector.red,
            gEvenGain = vector.greenEven,
            gOddGain = vector.greenOdd,
            bGain = vector.blue,
            hasFace = hasFace
        )
    }
}
