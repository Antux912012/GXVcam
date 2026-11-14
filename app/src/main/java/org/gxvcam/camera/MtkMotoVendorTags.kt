package org.gxvcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Log

/**
 * Registry of MediaTek Dimensity 7300 (MT6878) and Motorola Edge 50 Neo (`vienna`)
 * Camera2 vendor tags verified against `dumpsys media.camera` on device HAL.
 *
 * Every key uses the exact primitive Java class expected by the vendor HAL (`Byte` vs `Int`),
 * preventing silent HAL rejections or `IllegalArgumentException` during CaptureRequest marshaling.
 */
object MtkMotoVendorTags {

    private const val TAG = "MtkMotoVendorTags"

    // ==========================================
    // MediaTek Dimensity Imagiq 950 ISP Tags
    // ==========================================
    val MTK_ZSL_MODE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.mediatek.control.capture.zsl.mode", Byte::class.javaPrimitiveType)

    val MTK_ISP_META_ENABLE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.mediatek.control.capture.ispMetaEnable", Byte::class.javaPrimitiveType)

    val MTK_CROP_OUTER_LINES_ENABLE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.mediatek.streamingfeature.cropOuterLinesEnable", Byte::class.javaPrimitiveType)

    val MTK_FACE_ASD_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.facefeature.asdmode", Int::class.javaPrimitiveType)

    val MTK_FORCE_FACE_3A: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.facefeature.forceface3a", Int::class.javaPrimitiveType)

    val MTK_3DNR_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.nrfeature.3dnrmode", Int::class.javaPrimitiveType)

    val MTK_HDR_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.hdrfeature.hdrMode", Int::class.javaPrimitiveType)

    val MTK_SESSION_HDR_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.hdrfeature.SessionParamhdrMode", Int::class.javaPrimitiveType)

    val MTK_MFNR_MFB_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.mfnrfeature.mfbmode", Int::class.javaPrimitiveType)

    val MTK_MFNR_AIS_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.mfnrfeature.aismode", Int::class.javaPrimitiveType)

    val MTK_EIS_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.eisfeature.eismode", Int::class.javaPrimitiveType)

    val MTK_PREVIEW_EIS: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.eisfeature.previeweis", Int::class.javaPrimitiveType)

    val MTK_HFPS_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.streamingfeature.hfpsMode", Int::class.javaPrimitiveType)

    val MTK_HDR10P_CPU_CONTROL: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.streamingfeature.hdr10pCpuControl", Int::class.javaPrimitiveType)

    val MTK_PIP_DEVICES: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.streamingfeature.pipDevices", Int::class.javaPrimitiveType)

    val MTK_SINGLE_YUV_NR: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.singleYuvNr", Int::class.javaPrimitiveType)

    val MTK_HIGH_QUALITY_YUV: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.highQualityYuv", Int::class.javaPrimitiveType)

    val MTK_ISP_FRAME_COUNT: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.hintForIspFrameCount", Int::class.javaPrimitiveType)

    val MTK_ISP_FRAME_INDEX: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.hintForIspFrameIndex", Int::class.javaPrimitiveType)

    val MTK_VIDEO_AINR_MODES: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.videoainrfeature.videoAinrModes", Int::class.javaPrimitiveType)

    val MTK_ISP_EDGE_LEVEL: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.ispfeature.controlEdgeLevel", Int::class.javaPrimitiveType)

    val MTK_ISP_SATURATION_LEVEL: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.ispfeature.controlSaturationLevel", Int::class.javaPrimitiveType)

    val MTK_ISP_CONTRAST_LEVEL: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.ispfeature.controlContrastLevel", Int::class.javaPrimitiveType)

    val MTK_AE_ISP_GAIN: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.3afeature.aeIspGain", Int::class.javaPrimitiveType)

    // ==========================================
    // Motorola Custom Vendor Tags (Edge 50 Neo)
    // ==========================================
    val MOTO_IN_SENSOR_CROP_ENABLE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.in-sensor-crop.enable", Byte::class.javaPrimitiveType)

    val MOTO_DO_REMOSAIC: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.quadra_cfa.do_remosaic", Byte::class.javaPrimitiveType)

    val MOTO_ZSL_CROP: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.control.zsl_crop", Byte::class.javaPrimitiveType)

    val MOTO_IS_MOTCAMERA2: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.clientapp.is_motcamera2", Byte::class.javaPrimitiveType)

    val MOTO_IS_PRO_MODE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.clientapp.is_pro_mode", Byte::class.javaPrimitiveType)

    val MOTO_CURRENT_MODE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.clientapp.current_mode", Byte::class.javaPrimitiveType)

    val MOTO_SCENE_MODE: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.control.scene_mode", Byte::class.javaPrimitiveType)

    val MOTO_NIGHT_REQUEST: CaptureRequest.Key<Byte>? =
        createRequestKey("com.lenovo.moto.extension.night_request", Byte::class.javaPrimitiveType)

    val MOTO_RAW_NV_SE_EV0_EXP_TIME: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.raw_nv_se_ev0_exp_time", Int::class.javaPrimitiveType)

    val MOTO_VHDR_STATE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vhdr_state", Int::class.javaPrimitiveType)

    val MOTO_VSTAB_IHC: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vstab_ihc_enable", Int::class.javaPrimitiveType)

    val MOTO_VSTAB_SMARTEIS: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vstab_smarteis_enable", Int::class.javaPrimitiveType)

    val MOTO_VSTAB_PZS: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vstab_pzs_enable", Int::class.javaPrimitiveType)

    val MOTO_MWB_VALUE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.mwb.value", Int::class.javaPrimitiveType)

    val MOTO_VIDEO_MIRROR_ENABLE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.video_mirror_enable", Int::class.javaPrimitiveType)

    // CaptureResult Keys
    val MOTO_ADRC_GAIN: CaptureResult.Key<Float>? =
        createResultKey("com.lenovo.moto.adrc.gain", Float::class.javaPrimitiveType)

    // Static Characteristics Keys
    val MTK_SINGLE_FRAME_HDR_AVAILABLE: CameraCharacteristics.Key<Byte>? =
        createCharacteristicKey("com.mediatek.hdrfeature.availableSingleFrameHdr", Byte::class.javaPrimitiveType)

    val MOTO_VENDOR_EXPOSURE_RANGE: CameraCharacteristics.Key<LongArray>? =
        createCharacteristicKey("com.lenovo.moto.sensor.info.exposureTimeRange", LongArray::class.java)

    val MOTO_IS_REMOSAIC_BY_HW: CameraCharacteristics.Key<Byte>? =
        createCharacteristicKey("com.lenovo.moto.quadra_cfa.is_remosaic_by_hw", Byte::class.javaPrimitiveType)

    @Suppress("UNCHECKED_CAST")
    private fun <T> createRequestKey(name: String, type: Class<T>?): CaptureRequest.Key<T>? {
        return try {
            val constructor = CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            constructor.isAccessible = true
            constructor.newInstance(name, type) as? CaptureRequest.Key<T>
        } catch (e: Exception) {
            Log.d(TAG, "Key $name not available: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createResultKey(name: String, type: Class<T>?): CaptureResult.Key<T>? {
        return try {
            val constructor = CaptureResult.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            constructor.isAccessible = true
            constructor.newInstance(name, type) as? CaptureResult.Key<T>
        } catch (e: Exception) {
            Log.d(TAG, "Result Key $name not available: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createCharacteristicKey(name: String, type: Class<T>?): CameraCharacteristics.Key<T>? {
        return try {
            val constructor = CameraCharacteristics.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            constructor.isAccessible = true
            constructor.newInstance(name, type) as? CameraCharacteristics.Key<T>
        } catch (e: Exception) {
            Log.d(TAG, "Characteristic Key $name not available: ${e.message}")
            null
        }
    }
}
