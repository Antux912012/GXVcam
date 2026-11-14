package org.gxvcam.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.FaceDetector
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Multi-face Portrait Depth Slab processor.
 *
 * Detects up to 5 faces in the portrait frame and constructs an unblurred foreground
 * depth slab union covering every subject's head + torso plane, applying progressive
 * aperture-driven bokeh only outside the multi-subject depth slab.
 */
object PortraitDepthSlabProcessor {

    data class SubjectRegion(
        val cx: Float,
        val cy: Float,
        val radius: Float
    )

    fun detectSubjectRegions(bitmap: Bitmap, maxFaces: Int = 5): List<SubjectRegion> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 32 || h < 32) return emptyList()

        // Downscale to <= 480px wide for fast <12ms FaceDetector inference (requires even width + RGB_565)
        val scale = (480f / w).coerceAtMost(1.0f)
        val detW = ((w * scale).roundToInt().coerceAtLeast(32)) and 1.inv()
        val detH = ((h * scale).roundToInt().coerceAtLeast(32)) and 1.inv()

        val scaled = Bitmap.createScaledBitmap(bitmap, detW, detH, true)
        val rgb565 = scaled.copy(Bitmap.Config.RGB_565, false)
        if (scaled != bitmap && scaled != rgb565) {
            scaled.recycle()
        }

        val regions = mutableListOf<SubjectRegion>()
        try {
            if (rgb565 != null) {
                val detector = FaceDetector(detW, detH, maxFaces)
                val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
                val found = detector.findFaces(rgb565, faces)
                val invScaleX = w.toFloat() / detW.toFloat()
                val invScaleY = h.toFloat() / detH.toFloat()

                for (i in 0 until found) {
                    val face = faces[i] ?: continue
                    val mid = PointF()
                    face.getMidPoint(mid)
                    val eyeDist = face.eyesDistance().coerceAtLeast(12f)
                    val cx = mid.x * invScaleX
                    // Shift slab slightly downward from eyes to cover chin + shoulders
                    val cy = (mid.y + eyeDist * 0.45f) * invScaleY
                    val radius = (eyeDist * 2.6f) * maxOf(invScaleX, invScaleY)
                    regions.add(SubjectRegion(cx, cy, radius))
                }
            }
        } catch (_: Exception) {
            // Fallback handled below
        } finally {
            if (rgb565 != null && !rgb565.isRecycled) {
                rgb565.recycle()
            }
        }
        return regions
    }

    /**
     * Composites [blurredBitmap] over [sharpBitmap] while carving out crisp depth-slab
     * zones around all detected subjects (or a fallback portrait center slab if 0 faces).
     */
    fun compositeDepthSlabBokeh(
        sharpBitmap: Bitmap,
        blurredBitmap: Bitmap,
        aperture: Float,
        enableMultiFaceSlab: Boolean
    ) {
        val w = sharpBitmap.width
        val h = sharpBitmap.height
        if (w <= 0 || h <= 0) return

        val bokehStrength = ((16.0f - aperture.coerceIn(1.2f, 16.0f)) / (16.0f - 1.2f)).coerceIn(0f, 1f)
        if (bokehStrength <= 0.05f) return

        val subjects = if (enableMultiFaceSlab) {
            detectSubjectRegions(sharpBitmap)
        } else {
            emptyList()
        }

        val regions = if (subjects.isNotEmpty()) {
            subjects
        } else {
            listOf(SubjectRegion(w * 0.5f, h * 0.42f, hypot(w * 0.36f, h * 0.42f)))
        }

        // Build an offscreen alpha mask where opaque = background bokeh, transparent = sharp subject slab
        val maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val bgAlpha = (bokehStrength * 245f).roundToInt().coerceIn(0, 255)
        maskCanvas.drawColor(Color.argb(bgAlpha, 255, 255, 255))

        val carvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        for (reg in regions) {
            val r = reg.radius.coerceAtLeast(minOf(w, h) * 0.16f)
            carvePaint.shader = RadialGradient(
                reg.cx,
                reg.cy,
                r,
                intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(230, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0.0f, 0.52f, 1.0f),
                Shader.TileMode.CLAMP
            )
            maskCanvas.drawCircle(reg.cx, reg.cy, r, carvePaint)
        }

        // Apply mask onto a mutable blurred layer and draw onto sharpBitmap
        val layerCanvas = Canvas(blurredBitmap)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        layerCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)
        maskBitmap.recycle()

        val finalCanvas = Canvas(sharpBitmap)
        finalCanvas.drawBitmap(blurredBitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
