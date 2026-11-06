package org.gxvcam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.util.Log
import android.os.Build
import org.gxvcam.ui.state.AgcColorProfile
import org.gxvcam.ui.state.AspectRatio
import org.gxvcam.ui.state.ColorSpaceOption
import org.gxvcam.ui.state.OpticalLens
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tanh

/**
 * High-performance image processing engine implementing Google Camera (GCam) HDR+
 * and Vivo/Zeiss Natural & Vivid color science and acutance filters tailored for
 * Motorola Edge 50 Neo sensors (Sony LYTIA 700C, GC13A2, Samsung S5K3K1, S5KJD1).
 */
object GXVImageProcessor {

    private const val TAG = "GXVImageProcessor"

    fun processJpeg(
        inputJpeg: ByteArray,
        profile: AgcColorProfile = AgcColorProfile.ZEISS_NATURAL,
        activeLens: OpticalLens = OpticalLens.MAIN_1X,
        customSettings: org.gxvcam.ui.state.AgcLibPatcherSettings? = null,
        aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
        colorSpace: ColorSpaceOption = ColorSpaceOption.SRGB,
        jpegQuality: Int = 85,
        rotationDegrees: Int = 0
    ): ByteArray {
        val startTime = System.currentTimeMillis()
        try {
            // 1. Extract EXIF attributes
            val exifTags = readExifAttributes(inputJpeg)

            // 2. Decode JPEG to Bitmap
            val rawBitmap = BitmapFactory.decodeByteArray(inputJpeg, 0, inputJpeg.size, null)
                ?: return inputJpeg

            // Physically rotate bitmap based on gravity sensor / CameraX rotationDegrees
            val uprightBitmap = if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) {
                    rawBitmap.recycle()
                }
                rotated
            } else {
                rawBitmap
            }

            // Crop to target aspect ratio (1:1, 3:2, 21:9, etc.) on the upright bitmap
            val decodedBitmap = cropToAspectRatio(uprightBitmap, aspectRatio)

            val width = decodedBitmap.width
            val height = decodedBitmap.height

            // 3. Sensor-Calibrated Color Science with requested Color Space (sRGB / DCI-P3 / Adobe RGB)
            val processedBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val named = when (colorSpace) {
                    ColorSpaceOption.SRGB -> android.graphics.ColorSpace.Named.SRGB
                    ColorSpaceOption.DCI_P3 -> android.graphics.ColorSpace.Named.DISPLAY_P3
                    ColorSpaceOption.ADOBE_RGB -> android.graphics.ColorSpace.Named.ADOBE_RGB
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, android.graphics.ColorSpace.get(named))
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(processedBitmap)

            val colorFilter = buildSensorColorFilter(profile, activeLens, customSettings, forLiveViewfinder = false)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                this.colorFilter = colorFilter
            }
            canvas.drawBitmap(decodedBitmap, 0f, 0f, paint)
            decodedBitmap.recycle()

            // 4. S-Curve Tone Mapping (Highlight Knee & Sigmoid Shadow Lift) & LibPatcher Acutance
            applyToneMappingAndAcutance(processedBitmap, profile, customSettings)

            // 5. Optical Vignette (for Leica Authentic / Leica Vibrant / Custom)
            val vignette = customSettings?.vignetteStrength ?: profile.vignetteStrength
            if (vignette > 0.0f) {
                applyVignette(canvas, width, height, vignette)
            }

            // 6. Compress to JPEG with user-defined quality setting (default 85%, up to 100%)
            val quality = jpegQuality.coerceIn(50, 100)
            val outputStream = ByteArrayOutputStream(inputJpeg.size + 1024 * 512)
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            processedBitmap.recycle()
            val compressedBytes = outputStream.toByteArray()

            // 7. Re-apply preserved EXIF attributes with color space metadata
            val finalJpeg = writeExifAttributes(compressedBytes, exifTags, profile, colorSpace)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Processed ${width}x${height} JPEG with '${profile.label}' [${colorSpace.label}, Q=${quality}%] in ${elapsed}ms (${finalJpeg.size} bytes)")
            return finalJpeg
        } catch (e: Exception) {
            Log.e(TAG, "Error processing JPEG: ${e.message}", e)
            return inputJpeg
        }
    }

    private fun cropToAspectRatio(bitmap: Bitmap, aspectRatio: AspectRatio): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val isLandscape = w > h
        val targetRatio = when (aspectRatio) {
            AspectRatio.RATIO_4_3 -> 4f / 3f
            AspectRatio.RATIO_16_9 -> 16f / 9f
            AspectRatio.RATIO_1_1 -> 1.0f
            AspectRatio.RATIO_3_2 -> 3f / 2f
            AspectRatio.RATIO_21_9 -> 21f / 9f
        }
        val desiredRatio = if (isLandscape) targetRatio else (1f / targetRatio)
        val currentRatio = w.toFloat() / h.toFloat()

        val cropW: Int
        val cropH: Int
        if (currentRatio > desiredRatio + 0.015f) {
            cropH = h
            cropW = (h * desiredRatio).toInt().coerceAtMost(w)
        } else if (currentRatio < desiredRatio - 0.015f) {
            cropW = w
            cropH = (w / desiredRatio).toInt().coerceAtMost(h)
        } else {
            return bitmap
        }

        val startX = ((w - cropW) / 2).coerceAtLeast(0)
        val startY = ((h - cropH) / 2).coerceAtLeast(0)
        return try {
            val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
            if (cropped != bitmap) {
                bitmap.recycle()
            }
            cropped
        } catch (_: Exception) {
            bitmap
        }
    }

    fun buildSensorColorFilter(
        profile: AgcColorProfile,
        activeLens: OpticalLens,
        customSettings: org.gxvcam.ui.state.AgcLibPatcherSettings? = null,
        forLiveViewfinder: Boolean = false
    ): ColorMatrixColorFilter {
        // 1. Base Saturation
        val sat = customSettings?.saturation ?: profile.saturationBoost
        val satMatrix = ColorMatrix().apply {
            setSaturation(sat)
        }

        // 2. Sensor & Profile Specific Channel Scaling
        var rGain = profile.rGain * (customSettings?.rGainShift ?: 1.0f)
        var gGain = profile.gGain * (customSettings?.gGainShift ?: 1.0f)
        var bGain = profile.bGain * (customSettings?.bGainShift ?: 1.0f)

        // Secondary adjustment per physical optical sensor
        when (activeLens) {
            OpticalLens.UW -> {
                // GalaxyCore GC13A2: Compensate for wide-angle edge color shading
                rGain *= 0.98f
                gGain *= 1.01f
                bGain *= 1.02f
            }
            OpticalLens.TELE_3X -> {
                // Samsung S5K3K1: Compensate for 73mm telephoto warm absorption
                rGain *= 0.99f
                gGain *= 0.99f
                bGain *= 1.04f
            }
            OpticalLens.MAIN_1X, OpticalLens.CROP_2X -> {
                // Sony LYTIA 700C (IMX896) tuned
            }
        }

        val balanceMatrix = ColorMatrix(
            floatArrayOf(
                rGain, 0f, 0f, 0f, 0f,
                0f, gGain, 0f, 0f, 0f,
                0f, 0f, bGain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(balanceMatrix)

        // For live viewfinder grading, also apply the contrast matrix dynamically
        if (forLiveViewfinder) {
            val contrast = customSettings?.contrast ?: profile.contrastCurve
            val offset = 128f * (1f - contrast)
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            satMatrix.postConcat(contrastMatrix)
        }

        return ColorMatrixColorFilter(satMatrix)
    }

    private fun applyToneMappingAndAcutance(
        bitmap: Bitmap,
        profile: AgcColorProfile,
        customSettings: org.gxvcam.ui.state.AgcLibPatcherSettings? = null
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        // 1. Build S-Curve Tone Mapping LUT
        val lut = IntArray(256)
        val knee = customSettings?.highlightKnee ?: profile.highlightKnee
        val shadow = customSettings?.shadowLift ?: profile.shadowLift
        val contrast = customSettings?.contrast ?: profile.contrastCurve
        val acutanceVal = customSettings?.sharpnessLevel ?: profile.acutance
        val k = 1.5
        val tanhK = tanh(k).toFloat()

        for (i in 0..255) {
            var u = i / 255.0f

            // Shadow lift (smooth quadratic roll-off)
            u += shadow * (1.0f - u) * (1.0f - u) * u

            // S-Curve midtone contrast
            u += (u - 0.5f) * (contrast - 1.0f) * 4.0f * u * (1.0f - u)

            // Highlight knee roll-off (normalized hyperbolic tangent shoulder)
            if (u > knee) {
                val excess = (u - knee) / (1.0f - knee)
                u = knee + (1.0f - knee) * (tanh(k * excess).toFloat() / tanhK)
            }

            val res = (u.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            lut[i] = res.coerceIn(0, 255)
        }

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 2. Apply Tone Curve
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p and -0x1000000
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            pixels[i] = a or (r shl 16) or (g shl 8) or b
        }

        // 3. GCam LibPatcher Acutance & Micro-Contrast Enhancement
        // Uses 3 sliding row buffers to avoid duplicating full 50MB array
        if (acutanceVal > 1.0f) {
            val acutanceGain = (acutanceVal - 1.0f).coerceIn(0.1f, 1.2f)
            val threshold = 3
            val maxDelta = 36

            var prevLumaRow = IntArray(width)
            var currLumaRow = IntArray(width)
            var nextLumaRow = IntArray(width)

            // Pre-calculate luma for row 0 and row 1
            for (x in 0 until width) {
                val p0 = pixels[x]
                prevLumaRow[x] = (((p0 shr 16) and 0xFF) + (((p0 shr 8) and 0xFF) shl 1) + (p0 and 0xFF)) shr 2
                val p1 = pixels[width + x]
                currLumaRow[x] = (((p1 shr 16) and 0xFF) + (((p1 shr 8) and 0xFF) shl 1) + (p1 and 0xFF)) shr 2
            }

            for (y in 1 until height - 1) {
                val rowOffset = y * width
                val nextRowOffset = (y + 1) * width

                // Pre-calculate next row luma
                for (x in 0 until width) {
                    val pn = pixels[nextRowOffset + x]
                    nextLumaRow[x] = (((pn shr 16) and 0xFF) + (((pn shr 8) and 0xFF) shl 1) + (pn and 0xFF)) shr 2
                }

                for (x in 1 until width - 1) {
                    val luma = currLumaRow[x]
                    val lumaUp = prevLumaRow[x]
                    val lumaDown = nextLumaRow[x]
                    val lumaLeft = currLumaRow[x - 1]
                    val lumaRight = currLumaRow[x + 1]

                    val localAvg = (lumaUp + lumaDown + lumaLeft + lumaRight) shr 2
                    val delta = luma - localAvg
                    val absDelta = abs(delta)

                    if (absDelta in threshold..maxDelta) {
                        val boost = (delta * acutanceGain).toInt()
                        val idx = rowOffset + x
                        val p = pixels[idx]
                        val r = (((p shr 16) and 0xFF) + boost).coerceIn(0, 255)
                        val g = (((p shr 8) and 0xFF) + boost).coerceIn(0, 255)
                        val b = ((p and 0xFF) + boost).coerceIn(0, 255)
                        pixels[idx] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
                    }
                }

                // Shift row buffers
                val temp = prevLumaRow
                prevLumaRow = currLumaRow
                currLumaRow = nextLumaRow
                nextLumaRow = temp
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun applyVignette(canvas: Canvas, width: Int, height: Int, strength: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = hypot(cx, cy)
        val alpha = (strength * 110f).toInt().coerceIn(0, 255)

        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0x00000000, 0x00000000, (alpha shl 24)),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        val paint = Paint().apply {
            this.shader = shader
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun readExifAttributes(jpegBytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val exifInterface = ExifInterface(ByteArrayInputStream(jpegBytes))
            val tags = arrayOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL
            )
            for (tag in tags) {
                exifInterface.getAttribute(tag)?.let { value ->
                    map[tag] = value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF: ${e.message}")
        }
        return map
    }

    private fun writeExifAttributes(
        jpegBytes: ByteArray,
        exifTags: Map<String, String>,
        profile: AgcColorProfile,
        colorSpace: ColorSpaceOption = ColorSpaceOption.SRGB
    ): ByteArray {
        return try {
            val tempFile = File.createTempFile("gxv_exif_", ".jpg")
            FileOutputStream(tempFile).use { it.write(jpegBytes) }

            val exifInterface = ExifInterface(tempFile.absolutePath)
            for ((tag, value) in exifTags) {
                if (tag != ExifInterface.TAG_ORIENTATION) {
                    exifInterface.setAttribute(tag, value)
                }
            }
            // Normal (1) because pixels are already physically rotated upright based on device orientation
            exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            // Add custom software attribution and color space tag
            exifInterface.setAttribute(ExifInterface.TAG_SOFTWARE, "GXVcam (${profile.label} Engine)")
            exifInterface.setAttribute(ExifInterface.TAG_MAKE, "Motorola")
            exifInterface.setAttribute(ExifInterface.TAG_MODEL, "motorola edge 50 neo")
            val csVal = if (colorSpace == ColorSpaceOption.SRGB) "1" else "65535"
            exifInterface.setAttribute(ExifInterface.TAG_COLOR_SPACE, csVal)
            exifInterface.saveAttributes()

            val result = tempFile.readBytes()
            tempFile.delete()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-apply EXIF: ${e.message}")
            jpegBytes
        }
    }
}
