package org.gxvcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Log

/**
 * Registry of MediaTek and Motorola OEM Camera2 vendor tags extracted from
 * the Motorola Edge 50 Neo stock camera system (MotCamera5).
 *
 * Keys are loaded safely via reflection to ensure zero crashes on generic devices.
 */
object MtkMotoVendorTags {

    private const val TAG = "MtkMotoVendorTags"

    // ==========================================
    // MediaTek Dimensity Imagiq ISP Vendor Tags
    // ==========================================
    val MTK_ZSL_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.zsl.mode", Int::class.javaPrimitiveType)

    val MTK_ISP_FRAME_COUNT: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.hintForIspFrameCount", Int::class.javaPrimitiveType)

    val MTK_ISP_FRAME_INDEX: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.control.capture.hintForIspFrameIndex", Int::class.javaPrimitiveType)

    val MTK_MFNR_MFB_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.mfnrfeature.mfbmode", Int::class.javaPrimitiveType)

    val MTK_MFNR_BSS_COUNT: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.mfnrfeature.bsscount", Int::class.javaPrimitiveType)

    val MTK_AE_ISP_GAIN: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.3afeature.aeIspGain", Int::class.javaPrimitiveType)

    // ==========================================
    // Motorola Custom Vendor Tags (Edge 50 Neo)
    // ==========================================
    val MOTO_ZSL_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.capture_zsl_mode", Int::class.javaPrimitiveType)

    val MOTO_SUPERNIGHT: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.supernight", Int::class.javaPrimitiveType)

    val MOTO_RAW_NV_SE_CAPTURE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.raw_nv_se_capture", Int::class.javaPrimitiveType)

    val MOTO_RAW_NV_SE_FRAME_NUMBER: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.raw_nv_se_frame_number", Int::class.javaPrimitiveType)

    val MOTO_RAW_NV_SE_EV0_EXP_TIME: CaptureRequest.Key<Long>? =
        createRequestKey("com.lenovo.moto.control.raw_nv_se_ev0_exp_time", Long::class.javaPrimitiveType)

    val MOTO_ADRC_ENABLE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.adrc.enable", Int::class.javaPrimitiveType)

    val MOTO_ADRC_GAIN: CaptureRequest.Key<Float>? =
        createRequestKey("com.lenovo.moto.adrc.gain", Float::class.javaPrimitiveType)

    val MOTO_DO_REMOSAIC: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.quadra_cfa.do_remosaic", Int::class.javaPrimitiveType)

    val MOTO_TINTLESS_ENABLE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.tintless.enable", Int::class.javaPrimitiveType)

    val MOTO_IN_SENSOR_CROP_ENABLE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.in_sensor_crop.enable", Int::class.javaPrimitiveType)

    // Motorola Edge 50 Neo Video Vendor Tags
    val MOTO_VIDEO_NV: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.VideoNVwithoutVstab", Int::class.javaPrimitiveType)

    val MOTO_HDR10_TUNING: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.clientapp.hdr10_mode_tuning", Int::class.javaPrimitiveType)

    val MOTO_3D_HDR_MODE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.3d_hdr_mode", Int::class.javaPrimitiveType)

    val MOTO_VHDR_STATE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vhdr_state", Int::class.javaPrimitiveType)

    val MOTO_VSTAB_SMARTEIS: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vstab_smarteis_enable", Int::class.javaPrimitiveType)

    val MOTO_VSTAB_IHC: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vstab_ihc_enable", Int::class.javaPrimitiveType)

    val MOTO_VSTAB_PZS: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.vstab_pzs_enable", Int::class.javaPrimitiveType)

    val MOTO_VIDEO_SAT_4K30: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.video_sat_4k30fps_support", Int::class.javaPrimitiveType)

    val MOTO_VIDEO_SAT_4K60: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.control.video_sat_4k60fps_support", Int::class.javaPrimitiveType)

    val MOTO_IS_DUAL_CAPTURE: CaptureRequest.Key<Int>? =
        createRequestKey("com.lenovo.moto.clientapp.is_dual_capture", Int::class.javaPrimitiveType)

    val MOTO_PIP_ZOOM_RATIO: CaptureRequest.Key<Float>? =
        createRequestKey("com.lenovo.moto.control.pipzoomratio", Float::class.javaPrimitiveType)

    val MTK_PIP_DEVICES: CaptureRequest.Key<Int>? =
        createRequestKey("com.mediatek.streamingfeature.pipDevices", Int::class.javaPrimitiveType)

    // Static Characteristics Keys
    val MOTO_VENDOR_EXPOSURE_RANGE: CameraCharacteristics.Key<LongArray>? =
        createCharacteristicKey("com.lenovo.moto.sensor.info.exposureTimeRange", LongArray::class.java)

    val MOTO_IS_REMOSAIC_BY_HW: CameraCharacteristics.Key<Int>? =
        createCharacteristicKey("com.lenovo.moto.quadra_cfa.is_remosaic_by_hw", Int::class.javaPrimitiveType)

    // Helper functions
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
