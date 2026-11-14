package org.gxvcam.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp

/**
 * Generates and caches a calibrated 33x33x33 Pantone SkinTone 3D-LUT binary
 * (`pantone_skintone_33.bin`) in app-private storage off the UI thread.
 *
 * Skin-tone chromaticity regions are gently steered toward the Pantone SkinTone
 * warmth locus while preserving neutral grays (R == G == B stays exact identity).
 */
object PantoneLutGenerator {
    private const val TAG = "PantoneLutGenerator"
    const val LUT_SIZE = 33
    const val LUT_FILENAME = "pantone_skintone_33.bin"
    const val EXPECTED_FLOAT_COUNT = LUT_SIZE * LUT_SIZE * LUT_SIZE * 3
    const val EXPECTED_BYTE_SIZE = EXPECTED_FLOAT_COUNT * 4

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inProgress = AtomicBoolean(false)

    fun ensureGeneratedAsync(context: Context) {
        val outFile = File(context.filesDir, LUT_FILENAME)
        if (outFile.exists() && outFile.length() == EXPECTED_BYTE_SIZE.toLong()) {
            return
        }
        if (!inProgress.compareAndSet(false, true)) return

        ioScope.launch {
            try {
                generateToFile(outFile)
                Log.i(TAG, "Generated ${outFile.name} (${outFile.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate Pantone 3D-LUT: ${e.message}")
            } finally {
                inProgress.set(false)
            }
        }
    }

    fun generateToFile(outFile: File): File {
        val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
        val lut = build33x33x33Lut()
        val byteBuffer = ByteBuffer.allocate(EXPECTED_BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (v in lut) {
            byteBuffer.putFloat(v)
        }
        tmpFile.writeBytes(byteBuffer.array())
        if (outFile.exists()) {
            outFile.delete()
        }
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.copyTo(outFile, overwrite = true)
            tmpFile.delete()
        }
        return outFile
    }

    /**
     * Builds the flat `33 * 33 * 33 * 3` RGB float array in [0f, 1f].
     * Index order: `((b * 33 + g) * 33 + r) * 3 + channel`.
     */
    fun build33x33x33Lut(): FloatArray {
        val lut = FloatArray(EXPECTED_FLOAT_COUNT)
        val inv = 1.0f / (LUT_SIZE - 1).toFloat()
        var idx = 0

        for (bi in 0 until LUT_SIZE) {
            val b = bi * inv
            for (gi in 0 until LUT_SIZE) {
                val g = gi * inv
                for (ri in 0 until LUT_SIZE) {
                    val r = ri * inv

                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val rgDiff = r - g
                    val gbDiff = g - b

                    // Smooth Gaussian weight around natural human skin-tone chromaticity (R > G >= B)
                    val isSkinHue = (rgDiff > 0.02f) && (gbDiff >= -0.02f)
                    val skinWeight = if (isSkinHue) {
                        val hueDistSq = (rgDiff - 0.16f) * (rgDiff - 0.16f) + (gbDiff - 0.09f) * (gbDiff - 0.09f)
                        val lumaWeight = exp(-((luma - 0.52f) * (luma - 0.52f)) / 0.18f)
                        exp(-hueDistSq / 0.035f) * lumaWeight
                    } else {
                        0f
                    }

                    // Gentle Pantone SkinTone calibration: preserve neutral axis, stabilize yellow-green cast
                    val maxCh = maxOf(r, g, b)
                    val minCh = minOf(r, g, b)
                    val chroma = maxCh - minCh
                    val skyWeight = if (b > r + 0.08f && b >= g) {
                        exp(-((luma - 0.55f) * (luma - 0.55f)) / 0.20f) * chroma.coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    val outR = (r + skinWeight * 0.014f - skyWeight * 0.008f).coerceIn(0f, 1f)
                    val outG = (g + skinWeight * 0.006f + skyWeight * 0.004f).coerceIn(0f, 1f)
                    val outB = (b - skinWeight * 0.009f + skyWeight * 0.015f).coerceIn(0f, 1f)

                    lut[idx++] = outR
                    lut[idx++] = outG
                    lut[idx++] = outB
                }
            }
        }
        return lut
    }
}
