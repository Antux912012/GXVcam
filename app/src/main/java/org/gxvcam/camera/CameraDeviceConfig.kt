package org.gxvcam.camera

import android.util.Size

enum class LensRole(val displayName: String, val baseZoom: Float) {
    ULTRAWIDE(".6", 0.6f),
    MAIN("1x", 1.0f),
    MAIN_2X("2x", 2.0f),
    TELEPHOTO("3x", 3.0f),
    TELEPHOTO_6X("6x", 6.0f),
    FRONT("1x", 1.0f)
}

data class CameraSensorSpec(
    val cameraId: String,
    val role: LensRole,
    val sensorName: String,
    val rawSize: Size,
    val previewSize: Size,
    val cfaPattern: String,
    val whiteLevel: Float,
    val defaultBlackLevel: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraSensorSpec
        return cameraId == other.cameraId && role == other.role
    }

    override fun hashCode(): Int = 31 * cameraId.hashCode() + role.hashCode()
}

data class AuxLensButton(
    val label: String,
    val zoomRatio: Float,
    val sensorSpec: CameraSensorSpec
)

object DeviceCameraProfiles {
    // Motorola Edge 50 Neo verified configurations
    val EDGE_50_NEO_MAIN = CameraSensorSpec(
        cameraId = "0",
        role = LensRole.MAIN,
        sensorName = "Sony IMX896 (LYT-700C)",
        rawSize = Size(4096, 3072),
        previewSize = Size(1920, 1080),
        cfaPattern = "BGGR",
        whiteLevel = 1023f,
        defaultBlackLevel = floatArrayOf(64f, 64f, 64f, 64f)
    )

    val EDGE_50_NEO_ULTRAWIDE = CameraSensorSpec(
        cameraId = "2",
        role = LensRole.ULTRAWIDE,
        sensorName = "GalaxyCore GC13A2",
        rawSize = Size(4160, 3120),
        previewSize = Size(1920, 1080),
        cfaPattern = "GBRG",
        whiteLevel = 1023f,
        defaultBlackLevel = floatArrayOf(64f, 64f, 64f, 64f)
    )

    val EDGE_50_NEO_TELE = CameraSensorSpec(
        cameraId = "3",
        role = LensRole.TELEPHOTO,
        sensorName = "Samsung S5K3K1",
        rawSize = Size(3648, 2736),
        previewSize = Size(1920, 1080),
        cfaPattern = "GRBG",
        whiteLevel = 1023f,
        defaultBlackLevel = floatArrayOf(64f, 64f, 64f, 64f)
    )

    val EDGE_50_NEO_FRONT = CameraSensorSpec(
        cameraId = "1",
        role = LensRole.FRONT,
        sensorName = "Samsung S5KJD1",
        rawSize = Size(3280, 2460),
        previewSize = Size(1920, 1080),
        cfaPattern = "GRBG",
        whiteLevel = 1023f,
        defaultBlackLevel = floatArrayOf(64f, 64f, 64f, 64f)
    )

    // SGCam 8.5 AUX buttons list (.6, 1x, 2x, 3x, 6x)
    val SGCAM_AUX_BUTTONS = listOf(
        AuxLensButton(".6", 1.0f, EDGE_50_NEO_ULTRAWIDE),
        AuxLensButton("1x", 1.0f, EDGE_50_NEO_MAIN),
        AuxLensButton("2x", 2.0f, EDGE_50_NEO_MAIN),
        AuxLensButton("3x", 1.0f, EDGE_50_NEO_TELE),
        AuxLensButton("6x", 2.0f, EDGE_50_NEO_TELE)
    )
}
