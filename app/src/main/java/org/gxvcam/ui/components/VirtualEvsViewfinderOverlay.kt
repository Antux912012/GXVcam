package org.gxvcam.ui.components

import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gxvcam.ai.VirtualEvsOutput
import org.gxvcam.ai.VirtualEvsTelemetrySimulator
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live Virtual EVS (+ON Cyan / -OFF Coral) neuromorphic event overlay for the viewfinder.
 *
 * Uses a single pre-allocated 256x192 ARGB_8888 bitmap & pixel array to guarantee zero GC churn
 * during 15-30 FPS ImageAnalysis streaming.
 */
@Composable
fun VirtualEvsViewfinderOverlay(
    output: VirtualEvsOutput?,
    isHardwareEvsPresent: Boolean,
    modifier: Modifier = Modifier
) {
    val w = VirtualEvsTelemetrySimulator.MASK_WIDTH
    val h = VirtualEvsTelemetrySimulator.MASK_HEIGHT
    val eventPixels = remember { IntArray(w * h) }
    val overlayBitmap = remember { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }

    DisposableEffect(Unit) {
        onDispose {
            if (!overlayBitmap.isRecycled) {
                overlayBitmap.recycle()
            }
        }
    }

    val cyanOn = 0xCC00E5FF.toInt()
    val coralOff = 0xCCFF6B4A.toInt()

    val seq = output?.frameSequence ?: 0L
    remember(seq) {
        val buf = output?.eventPolarityMask256x192
        if (buf != null && !overlayBitmap.isRecycled) {
            val dup = buf.duplicate()
            dup.rewind()
            val total = minOf(w * h, dup.remaining())
            for (i in 0 until total) {
                val b = dup.get(i).toInt()
                eventPixels[i] = when {
                    b > 32 -> cyanOn
                    b < -32 -> coralOff
                    else -> 0
                }
            }
            overlayBitmap.setPixels(eventPixels, 0, w, 0, 0, w, h)
        }
        seq
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (output != null && !overlayBitmap.isRecycled) {
            val imageBitmap = remember(seq) { overlayBitmap.asImageBitmap() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = imageBitmap,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    alpha = 0.72f
                )

                // Corner neuromorphic reticle brackets
                val bracketLen = 28.dp.toPx()
                val margin = 20.dp.toPx()
                val stroke = 1.5.dp.toPx()
                val reticleColor = Color(0x9900E5FF)

                // Top-left
                drawLine(reticleColor, Offset(margin, margin), Offset(margin + bracketLen, margin), stroke)
                drawLine(reticleColor, Offset(margin, margin), Offset(margin, margin + bracketLen), stroke)
                // Top-right
                drawLine(reticleColor, Offset(size.width - margin, margin), Offset(size.width - margin - bracketLen, margin), stroke)
                drawLine(reticleColor, Offset(size.width - margin, margin), Offset(size.width - margin, margin + bracketLen), stroke)
                // Bottom-left
                drawLine(reticleColor, Offset(margin, size.height - margin), Offset(margin + bracketLen, size.height - margin), stroke)
                drawLine(reticleColor, Offset(margin, size.height - margin), Offset(margin, size.height - margin - bracketLen), stroke)
                // Bottom-right
                drawLine(reticleColor, Offset(size.width - margin, size.height - margin), Offset(size.width - margin - bracketLen, size.height - margin), stroke)
                drawLine(reticleColor, Offset(size.width - margin, size.height - margin), Offset(size.width - margin, size.height - margin - bracketLen), stroke)
            }
        }

        // Live Material 3 Expressive vEVS Telemetry Pill
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xB8101418),
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 104.dp, start = 14.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val badgeColor = if (isHardwareEvsPresent) Color(0xFF30D158) else Color(0xFF00E5FF)
                Canvas(modifier = Modifier.size(7.dp)) {
                    drawCircle(color = badgeColor)
                }

                if (output != null) {
                    val densityPct = output.eventDensityRatio * 100f
                    val shutterDenom = max(1, (1_000_000_000.0 / max(1L, output.recommendedShutterNs)).roundToInt())
                    val flickerLabel = when (output.sceneFlickerMode) {
                        CaptureResult.STATISTICS_SCENE_FLICKER_50HZ -> " • 50Hz"
                        CaptureResult.STATISTICS_SCENE_FLICKER_60HZ -> " • 60Hz"
                        else -> ""
                    }
                    val prefix = if (isHardwareEvsPresent) "EVS HW" else "vEVS"
                    Text(
                        text = "$prefix • Events ${"%.1f".format(densityPct)}% • 1/${shutterDenom}s • ${output.ambientLux.roundToInt()} lx$flickerLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = if (isHardwareEvsPresent) "EVS HW • Warming up" else "vEVS (NPU + Light) • Warming up",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
