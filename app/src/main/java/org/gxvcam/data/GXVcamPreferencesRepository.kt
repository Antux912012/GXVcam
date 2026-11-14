package org.gxvcam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.gxvcam.ui.state.AccentTheme
import org.gxvcam.ui.state.AgcColorProfile
import org.gxvcam.ui.state.AiProcessingBackend
import org.gxvcam.ui.state.AudioInputSource
import org.gxvcam.ui.state.CameraManualSettings
import org.gxvcam.ui.state.CaptureFormat
import org.gxvcam.ui.state.ColorSpaceOption
import org.gxvcam.ui.state.GridType
import org.gxvcam.ui.state.MotoVideoSettings
import org.gxvcam.ui.state.NpuPowerProfile
import org.gxvcam.ui.state.ProVideoLutStyle
import org.gxvcam.ui.state.VideoCodecProfile
import org.gxvcam.ui.state.VideoColorProfile
import org.gxvcam.ui.state.VolumeKeyAction
import org.gxvcam.ui.state.WatermarkStyle
import androidx.datastore.preferences.core.floatPreferencesKey

private val Context.gxvDataStore: DataStore<Preferences> by preferencesDataStore(name = "gxvcam_prefs")

/**
 * Jetpack DataStore persistence repository.
 *
 * Fixes Motorola Edge 50 Neo stock Camera5 bugs by persisting:
 * - [KEY_FRONT_RES_HIGH]: 32MP S5KJD1 front sensor high-res state across camera flips/restarts
 * - [KEY_AUTO_GROUP_SELFIE]: automatic 1.0x wide framing when multiple faces enter selfie frame
 * - Signature 27 pipeline toggles, color profile, capture format, and rotary zoom dial preference.
 */
class GXVcamPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.gxvDataStore)

    object Keys {
        val KEY_FRONT_RES_HIGH = booleanPreferencesKey("front_res_high_32mp")
        val KEY_AUTO_GROUP_SELFIE = booleanPreferencesKey("auto_group_selfie")
        val KEY_COLOR_PROFILE = stringPreferencesKey("agc_color_profile")
        val KEY_CAPTURE_FORMAT = stringPreferencesKey("capture_format")
        val KEY_COLOR_SPACE = stringPreferencesKey("color_space")
        val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val KEY_HDR_FRAME_COUNT = intPreferencesKey("hdr_frame_count")
        val KEY_ACCENT_THEME = stringPreferencesKey("accent_theme")
        val KEY_VOLUME_KEY_ACTION = stringPreferencesKey("volume_key_action")
        val KEY_GRID_TYPE = stringPreferencesKey("grid_type")
        val KEY_WATERMARK_STYLE = stringPreferencesKey("watermark_style")
        val KEY_WATERMARK_SIGNATURE = stringPreferencesKey("watermark_signature")
        val KEY_ROTARY_ZOOM_DIAL = booleanPreferencesKey("rotary_zoom_dial_enabled")
        val KEY_SIGNATURE27_PIPELINE = booleanPreferencesKey("signature27_pipeline_enabled")
        val KEY_SYNTHETIC_EVS_DEBLUR = booleanPreferencesKey("synthetic_evs_deblur_enabled")
        val KEY_DRIZZLE_MFSR = booleanPreferencesKey("drizzle_mfsr_enabled")
        val KEY_PORTRAIT_DEPTH_SLAB = booleanPreferencesKey("portrait_depth_slab_enabled")
        val KEY_ULTRA_HDR_GAINMAP = booleanPreferencesKey("ultra_hdr_gainmap_enabled")
        val KEY_ULTRA_RES_50M = booleanPreferencesKey("ultra_res_50m")
        val KEY_AI_BACKEND = stringPreferencesKey("pref_ai_processing_backend")
        val KEY_NPU_POWER_PROFILE = stringPreferencesKey("pref_npu_power_profile")
        val KEY_INTERNAL_NPU_ONLY = booleanPreferencesKey("pref_internal_npu_only")
        val KEY_VEVS_OVERLAY_VISIBLE = booleanPreferencesKey("pref_vevs_overlay_visible")
        val KEY_ZOOM_SCROLL_HAPTICS = booleanPreferencesKey("pref_zoom_scroll_haptics")
        // Sprint 8 Pro Video preferences
        val KEY_VIDEO_PRO_ENGINE = booleanPreferencesKey("pref_video_pro_engine")
        val KEY_VIDEO_CODEC = stringPreferencesKey("pref_video_codec")
        val KEY_VIDEO_COLOR_PROFILE = stringPreferencesKey("pref_video_color_profile")
        val KEY_VIDEO_SHUTTER_180 = booleanPreferencesKey("pref_video_shutter_180")
        val KEY_VIDEO_AUDIO_SOURCE = stringPreferencesKey("pref_video_audio_source")
        val KEY_VIDEO_WIND_CUT = booleanPreferencesKey("pref_video_wind_cut")
        val KEY_VIDEO_LUT_STYLE = stringPreferencesKey("pref_video_lut_style")
        val KEY_VIDEO_LUT_BLEND = floatPreferencesKey("pref_video_lut_blend")
        val KEY_VIDEO_VIBRANCE = floatPreferencesKey("pref_video_vibrance")
    }

    data class PersistedSnapshot(
        val manualSettings: CameraManualSettings = CameraManualSettings(),
        val colorProfile: AgcColorProfile = AgcColorProfile.ZEISS_NATURAL,
        val videoSettings: MotoVideoSettings = MotoVideoSettings()
    )

    val preferencesFlow: Flow<PersistedSnapshot> = dataStore.data.map { prefs ->
        val defaults = CameraManualSettings()
        val format = prefs[Keys.KEY_CAPTURE_FORMAT]?.let {
            runCatching { CaptureFormat.valueOf(it) }.getOrNull()
        } ?: defaults.captureFormat
        val colorSpace = prefs[Keys.KEY_COLOR_SPACE]?.let {
            runCatching { ColorSpaceOption.valueOf(it) }.getOrNull()
        } ?: defaults.colorSpace
        val accent = prefs[Keys.KEY_ACCENT_THEME]?.let {
            runCatching { AccentTheme.valueOf(it) }.getOrNull()
        } ?: defaults.accentTheme
        val volumeAction = prefs[Keys.KEY_VOLUME_KEY_ACTION]?.let {
            runCatching { VolumeKeyAction.valueOf(it) }.getOrNull()
        } ?: defaults.volumeKeyAction
        val grid = prefs[Keys.KEY_GRID_TYPE]?.let {
            runCatching { GridType.valueOf(it) }.getOrNull()
        } ?: defaults.gridType
        val watermark = prefs[Keys.KEY_WATERMARK_STYLE]?.let {
            runCatching { WatermarkStyle.valueOf(it) }.getOrNull()
        } ?: defaults.watermarkStyle
        val profile = prefs[Keys.KEY_COLOR_PROFILE]?.let {
            runCatching { AgcColorProfile.valueOf(it) }.getOrNull()
        } ?: AgcColorProfile.ZEISS_NATURAL
        val aiBackend = prefs[Keys.KEY_AI_BACKEND]?.let {
            runCatching { AiProcessingBackend.valueOf(it) }.getOrNull()
        } ?: defaults.aiBackend
        val npuProfile = prefs[Keys.KEY_NPU_POWER_PROFILE]?.let {
            runCatching { NpuPowerProfile.valueOf(it) }.getOrNull()
        } ?: defaults.npuPowerProfile

        val videoDefaults = MotoVideoSettings()
        val videoCodec = prefs[Keys.KEY_VIDEO_CODEC]?.let {
            runCatching { VideoCodecProfile.valueOf(it) }.getOrNull()
        } ?: videoDefaults.codecProfile
        val videoColor = prefs[Keys.KEY_VIDEO_COLOR_PROFILE]?.let {
            runCatching { VideoColorProfile.valueOf(it) }.getOrNull()
        } ?: videoDefaults.colorProfile
        val videoAudioSource = prefs[Keys.KEY_VIDEO_AUDIO_SOURCE]?.let {
            runCatching { AudioInputSource.valueOf(it) }.getOrNull()
        } ?: videoDefaults.audioInputSource
        val videoLutStyle = prefs[Keys.KEY_VIDEO_LUT_STYLE]?.let { saved ->
            if (saved == "MOTO_PANTONE_CINEMA") ProVideoLutStyle.SIGNATURE_LUT
            else runCatching { ProVideoLutStyle.valueOf(saved) }.getOrNull()
        } ?: videoDefaults.activeLutStyle

        PersistedSnapshot(
            manualSettings = defaults.copy(
                isUltraRes50M = prefs[Keys.KEY_ULTRA_RES_50M] ?: defaults.isUltraRes50M,
                captureFormat = format,
                isRawAndJpegEnabled = format.isRaw,
                colorSpace = colorSpace,
                jpegQuality = (prefs[Keys.KEY_JPEG_QUALITY] ?: defaults.jpegQuality).coerceIn(50, 100),
                hdrFrameCount = (prefs[Keys.KEY_HDR_FRAME_COUNT] ?: defaults.hdrFrameCount).coerceIn(2, 14),
                watermarkStyle = watermark,
                watermarkSignature = prefs[Keys.KEY_WATERMARK_SIGNATURE] ?: defaults.watermarkSignature,
                gridType = grid,
                accentTheme = accent,
                volumeKeyAction = volumeAction,
                isFrontHighRes32MpEnabled = prefs[Keys.KEY_FRONT_RES_HIGH] ?: defaults.isFrontHighRes32MpEnabled,
                isAutoGroupSelfieEnabled = prefs[Keys.KEY_AUTO_GROUP_SELFIE] ?: defaults.isAutoGroupSelfieEnabled,
                isRotaryZoomDialEnabled = prefs[Keys.KEY_ROTARY_ZOOM_DIAL] ?: defaults.isRotaryZoomDialEnabled,
                isSignature27PipelineEnabled = false,
                isSyntheticEvsDeblurEnabled = false,
                isDrizzleMfsrEnabled = prefs[Keys.KEY_DRIZZLE_MFSR] ?: defaults.isDrizzleMfsrEnabled,
                isPortraitDepthSlabEnabled = prefs[Keys.KEY_PORTRAIT_DEPTH_SLAB] ?: defaults.isPortraitDepthSlabEnabled,
                isUltraHdrGainmapEnabled = prefs[Keys.KEY_ULTRA_HDR_GAINMAP] ?: defaults.isUltraHdrGainmapEnabled,
                aiBackend = aiBackend,
                npuPowerProfile = npuProfile,
                useInternalNpuOnly = prefs[Keys.KEY_INTERNAL_NPU_ONLY] ?: defaults.useInternalNpuOnly,
                isVirtualEvsOverlayVisible = false,
                isZoomScrollHapticsEnabled = prefs[Keys.KEY_ZOOM_SCROLL_HAPTICS] ?: defaults.isZoomScrollHapticsEnabled
            ),
            colorProfile = profile,
            videoSettings = videoDefaults.copy(
                isProVideoEngineEnabled = prefs[Keys.KEY_VIDEO_PRO_ENGINE] ?: videoDefaults.isProVideoEngineEnabled,
                codecProfile = videoCodec,
                colorProfile = videoColor,
                isShutter180Locked = prefs[Keys.KEY_VIDEO_SHUTTER_180] ?: videoDefaults.isShutter180Locked,
                audioInputSource = videoAudioSource,
                isWindNoiseCutEnabled = prefs[Keys.KEY_VIDEO_WIND_CUT] ?: videoDefaults.isWindNoiseCutEnabled,
                activeLutStyle = videoLutStyle,
                lutBlend = (prefs[Keys.KEY_VIDEO_LUT_BLEND] ?: videoDefaults.lutBlend).coerceIn(0f, 1f),
                vibrance = (prefs[Keys.KEY_VIDEO_VIBRANCE] ?: videoDefaults.vibrance).coerceIn(-0.4f, 0.5f)
            )
        )
    }

    suspend fun saveManualSettings(settings: CameraManualSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.KEY_ULTRA_RES_50M] = settings.isUltraRes50M
            prefs[Keys.KEY_CAPTURE_FORMAT] = settings.captureFormat.name
            prefs[Keys.KEY_COLOR_SPACE] = settings.colorSpace.name
            prefs[Keys.KEY_JPEG_QUALITY] = settings.jpegQuality.coerceIn(50, 100)
            prefs[Keys.KEY_HDR_FRAME_COUNT] = settings.hdrFrameCount.coerceIn(2, 14)
            prefs[Keys.KEY_WATERMARK_STYLE] = settings.watermarkStyle.name
            prefs[Keys.KEY_WATERMARK_SIGNATURE] = settings.watermarkSignature
            prefs[Keys.KEY_GRID_TYPE] = settings.gridType.name
            prefs[Keys.KEY_ACCENT_THEME] = settings.accentTheme.name
            prefs[Keys.KEY_VOLUME_KEY_ACTION] = settings.volumeKeyAction.name
            prefs[Keys.KEY_FRONT_RES_HIGH] = settings.isFrontHighRes32MpEnabled
            prefs[Keys.KEY_AUTO_GROUP_SELFIE] = settings.isAutoGroupSelfieEnabled
            prefs[Keys.KEY_ROTARY_ZOOM_DIAL] = settings.isRotaryZoomDialEnabled
            prefs[Keys.KEY_SIGNATURE27_PIPELINE] = settings.isSignature27PipelineEnabled
            prefs[Keys.KEY_SYNTHETIC_EVS_DEBLUR] = settings.isSyntheticEvsDeblurEnabled
            prefs[Keys.KEY_DRIZZLE_MFSR] = settings.isDrizzleMfsrEnabled
            prefs[Keys.KEY_PORTRAIT_DEPTH_SLAB] = settings.isPortraitDepthSlabEnabled
            prefs[Keys.KEY_ULTRA_HDR_GAINMAP] = settings.isUltraHdrGainmapEnabled
            prefs[Keys.KEY_AI_BACKEND] = settings.aiBackend.name
            prefs[Keys.KEY_NPU_POWER_PROFILE] = settings.npuPowerProfile.name
            prefs[Keys.KEY_INTERNAL_NPU_ONLY] = settings.useInternalNpuOnly
            prefs[Keys.KEY_VEVS_OVERLAY_VISIBLE] = settings.isVirtualEvsOverlayVisible
            prefs[Keys.KEY_ZOOM_SCROLL_HAPTICS] = settings.isZoomScrollHapticsEnabled
        }
    }

    suspend fun saveColorProfile(profile: AgcColorProfile) {
        dataStore.edit { prefs ->
            prefs[Keys.KEY_COLOR_PROFILE] = profile.name
        }
    }

    suspend fun saveVideoSettings(settings: MotoVideoSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.KEY_VIDEO_PRO_ENGINE] = settings.isProVideoEngineEnabled
            prefs[Keys.KEY_VIDEO_CODEC] = settings.codecProfile.name
            prefs[Keys.KEY_VIDEO_COLOR_PROFILE] = settings.colorProfile.name
            prefs[Keys.KEY_VIDEO_SHUTTER_180] = settings.isShutter180Locked
            prefs[Keys.KEY_VIDEO_AUDIO_SOURCE] = settings.audioInputSource.name
            prefs[Keys.KEY_VIDEO_WIND_CUT] = settings.isWindNoiseCutEnabled
            prefs[Keys.KEY_VIDEO_LUT_STYLE] = settings.activeLutStyle.name
            prefs[Keys.KEY_VIDEO_LUT_BLEND] = settings.lutBlend.coerceIn(0f, 1f)
            prefs[Keys.KEY_VIDEO_VIBRANCE] = settings.vibrance.coerceIn(-0.4f, 0.5f)
        }
    }
}
