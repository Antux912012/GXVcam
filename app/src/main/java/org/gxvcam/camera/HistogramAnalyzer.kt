package org.gxvcam.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.gxvcam.ui.state.HistogramData
import java.nio.ByteBuffer
import kotlin.math.max

class HistogramAnalyzer(
    private val onHistogramComputed: (HistogramData) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastUpdateNs: Long = 0L
    private val frameIntervalNs: Long = 50_000_000L // Throttled to 20 FPS (50ms) to conserve CPU

    // Pre-allocated bin count and normalized arrays to eliminate per-frame GC churn
    private val rCounts = IntArray(256)
    private val gCounts = IntArray(256)
    private val bCounts = IntArray(256)
    private val lumaCounts = IntArray(256)

    private val rNorm = FloatArray(256)
    private val gNorm = FloatArray(256)
    private val bNorm = FloatArray(256)
    private val yNorm = FloatArray(256)

    override fun analyze(image: ImageProxy) {
        val now = System.nanoTime()
        if (now - lastUpdateNs < frameIntervalNs) {
            image.close()
            return
        }
        lastUpdateNs = now

        try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer: ByteBuffer = yPlane.buffer
            val uBuffer: ByteBuffer = uPlane.buffer
            val vBuffer: ByteBuffer = vPlane.buffer

            val width = image.width
            val height = image.height

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            rCounts.fill(0)
            gCounts.fill(0)
            bCounts.fill(0)
            lumaCounts.fill(0)

            val step = 8 // Subsample every 8th pixel in X and Y

            for (row in 0 until height step step) {
                val yRowOffset = row * yRowStride
                val uvRowOffset = (row / 2) * uvRowStride

                for (col in 0 until width step step) {
                    val yIndex = yRowOffset + col * yPixelStride
                    val uvIndex = uvRowOffset + (col / 2) * uvPixelStride

                    val y = (yBuffer.get(yIndex).toInt() and 0xFF)
                    val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                    // BT.601 YUV -> RGB conversion
                    val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                    val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                    val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                    val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)

                    rCounts[r]++
                    gCounts[g]++
                    bCounts[b]++
                    lumaCounts[luma]++
                }
            }

            // Normalize each bin array by dividing by the maximum value in that array (0f..1f)
            val maxR = max(1, rCounts.maxOrNull() ?: 1)
            val maxG = max(1, gCounts.maxOrNull() ?: 1)
            val maxB = max(1, bCounts.maxOrNull() ?: 1)
            val maxY = max(1, lumaCounts.maxOrNull() ?: 1)

            for (i in 0 until 256) {
                rNorm[i] = rCounts[i].toFloat() / maxR
                gNorm[i] = gCounts[i].toFloat() / maxG
                bNorm[i] = bCounts[i].toFloat() / maxB
                yNorm[i] = lumaCounts[i].toFloat() / maxY
            }

            onHistogramComputed(HistogramData(rNorm.clone(), gNorm.clone(), bNorm.clone(), yNorm.clone()))
        } catch (_: Exception) {
            // Ignore frame decode exceptions on closed image
        } finally {
            image.close()
        }
    }
}
