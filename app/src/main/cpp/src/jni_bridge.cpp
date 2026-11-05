#include <jni.h>
#include <android/log.h>
#include "raw_pipeline.hpp"
#include <vector>
#include <cstring>

#define LOG_TAG "GXVcamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_unpackRaw16Native(
    JNIEnv* env,
    jobject /* thiz */,
    jobject direct_buffer,
    jint buffer_size,
    jint width,
    jint height,
    jint row_stride_bytes,
    jfloat white_level,
    jfloatArray black_level_arr
) {
    if (!direct_buffer) {
        LOGE("unpackRaw16Native: direct_buffer is null");
        return JNI_FALSE;
    }

    const uint8_t* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    if (!src) {
        LOGE("unpackRaw16Native: failed to get direct buffer address");
        return JNI_FALSE;
    }

    jsize bl_len = env->GetArrayLength(black_level_arr);
    if (bl_len < 4) {
        LOGE("unpackRaw16Native: black_level array length < 4");
        return JNI_FALSE;
    }

    jfloat bl_elements[4];
    env->GetFloatArrayRegion(black_level_arr, 0, 4, bl_elements);

    gxv::FrameMetadata meta;
    meta.width = width;
    meta.height = height;
    meta.row_stride_bytes = row_stride_bytes;
    meta.white_level = white_level;
    meta.black_level = {bl_elements[0], bl_elements[1], bl_elements[2], bl_elements[3]};

    // Allocate normalized float buffer
    std::vector<float> normalized_bayer(width * height);
    bool ok = gxv::unpack_raw16_normalized(src, static_cast<size_t>(buffer_size), meta, normalized_bayer.data());
    if (ok) {
        LOGI("unpackRaw16Native: successfully unpacked %dx%d RAW16 frame", width, height);
        return JNI_TRUE;
    } else {
        LOGE("unpackRaw16Native: unpacking failed for geometry %dx%d stride %d", width, height, row_stride_bytes);
        return JNI_FALSE;
    }
}

} // extern "C"
