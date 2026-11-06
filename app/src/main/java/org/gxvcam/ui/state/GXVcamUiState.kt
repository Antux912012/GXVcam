package org.gxvcam.ui.state

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class CameraMode {
    PHOTO,
    VIDEO,
    PRO,
    NIGHT,
    PORTRAIT
}

/**
 * Optical lens presets for the Motorola Edge 50 Neo (MT6878).
 *
 * @param zoomRatio Equivalent zoom relative to the main 1x lens (what the user sees).
 * @param focalLengthMm 35mm-equivalent focal length.
 * @param label UI label.
 * @param cameraId Physical Camera2 ID backing this lens (0 = main, 2 = UW, 3 = tele).
 * @param sensorBaseZoom Equivalent zoom of the physical sensor at its native 1.0 zoom ratio.
 *
 * UW FOV derived from probe data: main f=5.56mm on 8.19x6.14mm (~24mm eq.),
 * UW f=2.13mm on 4.71x3.49mm (~16mm eq.) => ~0.6x relative to main.
 */
enum class OpticalLens(
    val zoomRatio: Float,
    val focalLengthMm: Int,
    val label: String,
    val cameraId: String,
    val sensorBaseZoom: Float,
) {
    UW(0.6f, 16, ".6", "2", 0.6f),
    MAIN_1X(1.0f, 24, "1x", "0", 1.0f),
    CROP_2X(2.0f, 48, "2x", "0", 1.0f),
    TELE_3X(3.0f, 73, "3x", "3", 3.0f);

    companion object {
        val MIN_ZOOM: Float = UW.zoomRatio
        const val MAX_ZOOM: Float = 10.0f

        /** Picks the lens that should serve a given equivalent zoom ratio. */
        fun forZoom(zoom: Float): OpticalLens = when {
            zoom < MAIN_1X.zoomRatio -> UW
            zoom < CROP_2X.zoomRatio -> MAIN_1X
            zoom < TELE_3X.zoomRatio -> CROP_2X
            else -> TELE_3X
        }
    }
}

enum class FlashMode {
    OFF,
    ON,
    AUTO,
    TORCH
}

enum class AspectRatio(val ratioValue: Float, val label: String) {
    RATIO_4_3(0.75f, "4:3"),
    RATIO_16_9(0.5625f, "16:9"),
    RATIO_1_1(1.0f, "1:1"),
    RATIO_3_2(2f / 3f, "3:2"),
    RATIO_21_9(9f / 21f, "21:9");

    val ratio: Float get() = ratioValue
}

enum class MotoVideoStabilization(val label: String) {
    OFF("Off"),
    ADAPTIVE("Adaptive OIS+EIS"),
    HORIZON_LOCK("Horizon 360°")
}

enum class VideoFrameRate(val fps: Int, val label: String) {
    FPS_30(30, "30fps"),
    FPS_60(60, "60fps"),
    SLOW_MO_120(120, "120fps")
}

@Immutable
data class MotoVideoSettings(
    val stabilization: MotoVideoStabilization = MotoVideoStabilization.ADAPTIVE,
    val frameRate: VideoFrameRate = VideoFrameRate.FPS_60,
    val isHdr10BitEnabled: Boolean = false,
    val isAudioZoomEnabled: Boolean = true,
    val isDualCaptureEnabled: Boolean = false,
)

enum class AgcColorProfile(
    val label: String,
    val description: String,
    val saturationBoost: Float,
    val contrastCurve: Float,
    val highlightKnee: Float,
    val shadowLift: Float,
    val acutance: Float,
    val vignetteStrength: Float,
    val rGain: Float = 1.0f,
    val gGain: Float = 1.0f,
    val bGain: Float = 1.0f
) {
    ZEISS_NATURAL(
        label = "Zeiss Natural",
        description = "Vivo/Zeiss D65 natural colors, smooth highlights & restrained skin",
        saturationBoost = 1.02f,
        contrastCurve = 1.08f,
        highlightKnee = 0.72f,
        shadowLift = 0.12f,
        acutance = 1.25f,
        vignetteStrength = 0.0f,
        rGain = 1.02f,
        gGain = 0.98f,
        bGain = 1.06f
    ),
    VIVO_VIVID(
        label = "Vivo Vivid",
        description = "Lush memory colors, vibrant skies & protected skin tones",
        saturationBoost = 1.18f,
        contrastCurve = 1.12f,
        highlightKnee = 0.70f,
        shadowLift = 0.16f,
        acutance = 1.30f,
        vignetteStrength = 0.0f,
        rGain = 1.04f,
        gGain = 1.01f,
        bGain = 1.10f
    ),
    GCAM_HDR_PLUS(
        label = "GCam HDR+",
        description = "Google Pixel HDR+ tone mapping, deep contrast & crisp micro-textures",
        saturationBoost = 1.08f,
        contrastCurve = 1.18f,
        highlightKnee = 0.68f,
        shadowLift = 0.22f,
        acutance = 1.38f,
        vignetteStrength = 0.0f,
        rGain = 1.01f,
        gGain = 0.99f,
        bGain = 1.04f
    ),
    LEICA_AUTHENTIC(
        label = "Leica Authentic",
        description = "Classic filmic look, rich dark shadows & optical vignette",
        saturationBoost = 0.94f,
        contrastCurve = 1.25f,
        highlightKnee = 0.76f,
        shadowLift = 0.05f,
        acutance = 1.15f,
        vignetteStrength = 0.28f,
        rGain = 1.03f,
        gGain = 0.97f,
        bGain = 0.98f
    ),
    LEICA_VIBRANT(
        label = "Leica Vibrant",
        description = "Modern punchy Leica colors with expanded dynamic range",
        saturationBoost = 1.16f,
        contrastCurve = 1.14f,
        highlightKnee = 0.72f,
        shadowLift = 0.15f,
        acutance = 1.22f,
        vignetteStrength = 0.10f,
        rGain = 1.03f,
        gGain = 0.99f,
        bGain = 1.06f
    ),
    PANTONE_NATURAL(
        label = "Pantone Natural",
        description = "Motorola official Pantone-certified true color tone",
        saturationBoost = 1.00f,
        contrastCurve = 1.00f,
        highlightKnee = 0.78f,
        shadowLift = 0.08f,
        acutance = 1.00f,
        vignetteStrength = 0.0f,
        rGain = 1.0f,
        gGain = 1.0f,
        bGain = 1.0f
    ),
    CUSTOM(
        label = "Custom",
        description = "Manual GCam LibPatcher tuning overrides",
        saturationBoost = 1.05f,
        contrastCurve = 1.10f,
        highlightKnee = 0.72f,
        shadowLift = 0.12f,
        acutance = 1.25f,
        vignetteStrength = 0.0f,
        rGain = 1.0f,
        gGain = 1.0f,
        bGain = 1.0f
    );

    companion object {
        val AGC_LEICA_AUTHENTIC get() = LEICA_AUTHENTIC
        val AGC_LEICA_VIBRANT get() = LEICA_VIBRANT
        val MOTO_VIVID get() = VIVO_VIVID
    }
}

@Immutable
data class AgcLibPatcherSettings(
    val activeProfile: AgcColorProfile = AgcColorProfile.ZEISS_NATURAL,
    val isLiveViewfinderLutEnabled: Boolean = true,
    // LibPatcher Sharpness & Acutance
    val sharpnessLevel: Float = 1.25f,
    // Spatial & Chroma Denoise
    val lumaDenoiseLevel: Float = 1.0f,
    val chromaDenoiseLevel: Float = 1.0f,
    // Color & Tone Manual Tuning Overrides
    val saturation: Float = 1.02f,
    val contrast: Float = 1.08f,
    val highlightKnee: Float = 0.72f,
    val shadowLift: Float = 0.12f,
    val vignetteStrength: Float = 0.0f,
    val rGainShift: Float = 1.0f,
    val gGainShift: Float = 1.0f,
    val bGainShift: Float = 1.0f,
    // Multi-Frame & Capture Engine
    val hdrNetMultiFrameBoost: Boolean = true,
    val hdrFrameCount: Int = 8,
    val disableToneCurveClip: Boolean = true
)

@Immutable
data class ProHardwareCapabilities(
    val isoRange: IntRange = 50..6400,
    val exposureTimeRangeNs: LongRange = 125_000L..32_000_000_000L,
    val evRange: IntRange = -12..12,
    val evStep: Float = 0.33f,
    val minFocusDistanceDiopters: Float = 10f,
    val isRawSupported: Boolean = false
)

enum class WhiteBalanceMode(val label: String, val kelvin: Int, val awbMode: Int) {
    AUTO("Auto", 5500, 1),
    DAYLIGHT("Daylight", 5500, 5),
    CLOUDY("Cloudy", 6500, 6),
    FLUORESCENT("Fluorescent", 4000, 3),
    INCANDESCENT("Tungsten", 3000, 2),
    CUSTOM("Custom", 5500, 0),
}

enum class ColorSpaceOption(val label: String, val description: String) {
    SRGB("sRGB", "Standard gamut for web and general screen sharing"),
    DCI_P3("DCI-P3", "Wide color gamut (Display P3) with richer greens and reds"),
    ADOBE_RGB("Adobe RGB", "Expanded color gamut for professional print & editing");
}

enum class CaptureFormat(val label: String, val shortLabel: String, val description: String) {
    JPEG("JPEG only", "JPG", "High-efficiency compressed photo with ISP color science"),
    RAW("RAW only", "RAW", "16-bit uncompressed DNG digital negative direct from sensor"),
    RAW_AND_JPEG("RAW + JPEG", "RAW+JPG", "Captures both uncompressed DNG and processed JPEG");

    val isRaw: Boolean get() = this == RAW || this == RAW_AND_JPEG
    val isJpeg: Boolean get() = this == JPEG || this == RAW_AND_JPEG
}

@Immutable
data class ProSettings(
    val isManualIso: Boolean = false,
    val iso: Int = 100,
    val isManualShutter: Boolean = false,
    val shutterSpeedNs: Long = 8_000_000L,
    val evIndex: Int = 0,
    val isManualFocus: Boolean = false,
    val focusDistanceDiopters: Float = 0f,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val whiteBalanceKelvin: Int = 5500,
    val isRawAndJpegEnabled: Boolean = false,
    val captureFormat: CaptureFormat = CaptureFormat.JPEG,
) {
    val isManualExposure: Boolean get() = isManualIso || isManualShutter
    val isAnyManual: Boolean get() = isManualIso || isManualShutter || isManualFocus || (whiteBalanceMode != WhiteBalanceMode.AUTO) || (evIndex != 0)
}

enum class GridType(val label: String, val subtitle: String) {
    NONE("Off", "No grid lines"),
    RULE_OF_THIRDS("Rule of 3rds", "3x3 photography grid"),
    SQUARE("Square Grid", "4x4 architectural & square framing"),
    DIAG_SQUARE("Diag. + Square", "Sony Alpha signature grid with diagonal guides"),
    GOLDEN_RATIO("Golden Ratio (Phi)", "0.618 divine proportion grid"),
    CROSSHAIR("Center Crosshair", "Center spot & crosshairs"),
}

enum class ProLayoutPosition(val label: String, val description: String) {
    SIDEBAR_RIGHT("Right", "Vertical bar on right edge"),
    SIDEBAR_LEFT("Left", "Vertical bar on left edge"),
    TOP_BAR("Top", "Horizontal strip below top bar"),
    BOTTOM_DOCK("Bottom", "Dock above lens selector"),
}

enum class AccentTheme(
    val label: String,
    val subtitle: String,
    val colorHex: Long,
    val containerHex: Long,
) {
    DYNAMIC_SYSTEM("Material You", "System dynamic wallpaper", 0xFF80CBC4, 0xFF004D40),
    PANTONE_POINCIANA("Poinciana", "Edge 50 Neo fiery red", 0xFFFF453A, 0xFF5A1010),
    PANTONE_NAUTICAL_BLUE("Nautical Blue", "Edge 50 Neo ocean blue", 0xFF0A84FF, 0xFF052B59),
    PANTONE_LATTE("Pantone Lattè", "Edge 50 Neo mocha sand", 0xFFD7CCC8, 0xFF4E342E),
    PANTONE_GRISAILLE("Grisaille", "Edge 50 Neo slate grey", 0xFFB0BEC5, 0xFF263238),
    LEICA_AMBER("Leica Amber", "Classic camera gold/amber", 0xFFFFB300, 0xFF4D3800),
    MINT_EXPRESSIVE("Expressive Mint", "M3 Expressive teal mint", 0xFF30D158, 0xFF0D421D),
}

@Immutable
data class CameraManualSettings(
    val isUltraRes50M: Boolean = false,
    val isAdrcEnabled: Boolean = true,
    val isTintlessEnabled: Boolean = true,
    val isZslEnabled: Boolean = true,
    val isSuperNightEnabled: Boolean = true,
    val antiBandingMode: Int = 3, // 0=Off, 1=50Hz, 2=60Hz, 3=Auto
    val isRawAndJpegEnabled: Boolean = false,
    val captureFormat: CaptureFormat = CaptureFormat.JPEG,
    val colorSpace: ColorSpaceOption = ColorSpaceOption.SRGB,
    val jpegQuality: Int = 85,
    val hdrFrameCount: Int = 8,
    val isWatermarkEnabled: Boolean = false,
    val isHistogramOverlayVisible: Boolean = true,
    val isHorizonLevelVisible: Boolean = true,
    val gridType: GridType = GridType.RULE_OF_THIRDS,
    val proLayoutPosition: ProLayoutPosition = ProLayoutPosition.SIDEBAR_RIGHT,
    val accentTheme: AccentTheme = AccentTheme.PANTONE_POINCIANA,
)

@Immutable
data class HistogramData(
    val rBins: FloatArray = FloatArray(256),
    val gBins: FloatArray = FloatArray(256),
    val bBins: FloatArray = FloatArray(256),
    val lumaBins: FloatArray = FloatArray(256)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HistogramData
        return rBins.contentEquals(other.rBins) &&
                gBins.contentEquals(other.gBins) &&
                bBins.contentEquals(other.bBins) &&
                lumaBins.contentEquals(other.lumaBins)
    }

    override fun hashCode(): Int {
        var result = rBins.contentHashCode()
        result = (31 * result) + gBins.contentHashCode()
        result = (31 * result) + bBins.contentHashCode()
        result = (31 * result) + lumaBins.contentHashCode()
        return result
    }
}

enum class SonyAfMode(val label: String, val shortLabel: String, val description: String) {
    AF_C("AF-C (Continuous)", "AF-C", "Continuous autofocus tracks moving subjects"),
    AF_S("AF-S (Single-shot)", "AF-S", "Locks focus once acquired until shot"),
    AF_AUTO("AF-Auto (Automatic)", "AF-Auto", "Switches between single and continuous automatically"),
    AF_L("AF-L (Focus Lock)", "AF-L", "Focus distance is frozen at current point"),
    MF("AF-M (Manual Focus)", "AF-M", "Disengages autofocus for manual control");
}

enum class SonyAeMode(val label: String, val shortLabel: String, val description: String) {
    AE_AUTO("AE (Auto / Multi)", "AE", "Evaluative multi-zone scene exposure metering"),
    AE_CENTER("AE-Center", "AE-Ctr", "Center-weighted average metering"),
    AE_SPOT("AE-Spot", "AE-Spot", "Spot metering at current focus point"),
    AE_L("AE-L (Exposure Lock)", "AE-L", "Exposure values are frozen at current point"),
    AE_M("AE-M (Manual Exp)", "AE-M", "Manual ISO and shutter speed control");
}

@Immutable
data class GXVcamUiState(
    val currentMode: CameraMode = CameraMode.PHOTO,
    val isBackCamera: Boolean = true,
    val activeLens: OpticalLens = OpticalLens.MAIN_1X,
    val currentZoomRatio: Float = 1.0f,
    val isRecording: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val isCapturingPhoto: Boolean = false,
    val lastCapturedUri: Uri? = null,
    val flashMode: FlashMode = FlashMode.OFF,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val gridEnabled: Boolean = true,
    val levelHorizonDegrees: Float = 0f,
    val isProPanelExpanded: Boolean = false,
    val isAgcSheetOpen: Boolean = false,
    val isSettingsSheetOpen: Boolean = false,
    val activeProControl: String? = null,
    val proSettings: ProSettings = ProSettings(),
    val manualSettings: CameraManualSettings = CameraManualSettings(),
    val motoVideoSettings: MotoVideoSettings = MotoVideoSettings(),
    val agcSettings: AgcLibPatcherSettings = AgcLibPatcherSettings(),
    val hardwareCapabilities: ProHardwareCapabilities = ProHardwareCapabilities(),
    val histogram: HistogramData = HistogramData(),
    val isAeLocked: Boolean = false,
    val isAfLocked: Boolean = false,
    val sonyAfMode: SonyAfMode = SonyAfMode.AF_C,
    val sonyAeMode: SonyAeMode = SonyAeMode.AE_AUTO,
    val tapFocusEvOffset: Float = 0f
) {
    val isAeAfLocked: Boolean get() = isAeLocked && isAfLocked
}

sealed interface GXVcamEvent {
    // STE100 Specific Events
    data class SelectMode(val mode: CameraMode) : GXVcamEvent
    data class SelectLens(val lens: OpticalLens) : GXVcamEvent
    data class SetZoomRatio(val zoom: Float) : GXVcamEvent
    data object ToggleCameraFacing : GXVcamEvent
    data object CycleFlashMode : GXVcamEvent
    data object CycleAspectRatio : GXVcamEvent
    data object ToggleGrid : GXVcamEvent
    data class SetGridType(val gridType: GridType) : GXVcamEvent
    data class SetAeLock(val locked: Boolean, val x: Float = 0f, val y: Float = 0f) : GXVcamEvent
    data class SetAfLock(val locked: Boolean, val x: Float = 0f, val y: Float = 0f) : GXVcamEvent
    data class SetAeAfLock(val locked: Boolean, val x: Float = 0f, val y: Float = 0f) : GXVcamEvent
    data class SetSonyAfMode(val mode: SonyAfMode) : GXVcamEvent
    data class SetSonyAeMode(val mode: SonyAeMode) : GXVcamEvent
    data class SetTapFocusEv(val ev: Float) : GXVcamEvent
    data class UpdateProSettings(val settings: ProSettings) : GXVcamEvent
    data class UpdateHardwareCapabilities(val capabilities: ProHardwareCapabilities) : GXVcamEvent
    data class UpdateHistogram(val data: HistogramData) : GXVcamEvent
    data class UpdateHorizonLevel(val degrees: Float) : GXVcamEvent
    data class OnPhotoCaptured(val uri: Uri) : GXVcamEvent
    data class SetRecordingState(val isRecording: Boolean) : GXVcamEvent
    data object ToggleRawCapture : GXVcamEvent
    data object CycleCaptureFormat : GXVcamEvent
    data class SetCaptureFormat(val format: CaptureFormat) : GXVcamEvent
    data class SetColorSpace(val colorSpace: ColorSpaceOption) : GXVcamEvent
    data class SetJpegQuality(val quality: Int) : GXVcamEvent
    data object ToggleSettingsSheet : GXVcamEvent
    data class UpdateManualSettings(val settings: CameraManualSettings) : GXVcamEvent
    data object ResetProToAuto : GXVcamEvent

    // Moto Cam 5 & AGC BigKaka Events
    data class UpdateMotoVideoSettings(val settings: MotoVideoSettings) : GXVcamEvent
    data class UpdateAgcSettings(val settings: AgcLibPatcherSettings) : GXVcamEvent
    data class SelectAgcProfile(val profile: AgcColorProfile) : GXVcamEvent
    data object ToggleAgcSheet : GXVcamEvent

    // Interactive & Viewfinder UI Event Aliases
    data class OnModeSelected(val mode: CameraMode) : GXVcamEvent
    data class OnLensSelected(val lens: OpticalLens) : GXVcamEvent
    data class OnZoomChanged(val zoomRatio: Float) : GXVcamEvent
    data class OnTapToFocus(val x: Float, val y: Float, val width: Int = 0, val height: Int = 0) : GXVcamEvent
    data object OnShutterClicked : GXVcamEvent
    data object OnStartRecording : GXVcamEvent
    data object OnStopRecording : GXVcamEvent
    data object OnFlipCamera : GXVcamEvent
    data class OnProSettingChanged(val transform: (ProSettings) -> ProSettings) : GXVcamEvent
    data class OnActiveProControlSelected(val control: String?) : GXVcamEvent
    data object OnToggleProPanel : GXVcamEvent
    data class OnHistogramUpdated(val histogram: HistogramData) : GXVcamEvent
    data class OnLevelHorizonUpdated(val degrees: Float) : GXVcamEvent
    data object OnToggleGrid : GXVcamEvent
    data object OnCycleFlash : GXVcamEvent
    data object OnCycleAspectRatio : GXVcamEvent
    data class OnHardwareCapabilitiesUpdated(val capabilities: ProHardwareCapabilities) : GXVcamEvent
    data class OnRecordingDurationUpdated(val durationMs: Long) : GXVcamEvent
    data class ToggleLiveViewfinderLut(val enabled: Boolean) : GXVcamEvent
    data object ResetAgcToDefaults : GXVcamEvent
}
