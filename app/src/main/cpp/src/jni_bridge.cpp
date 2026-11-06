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

    jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
    if (capacity <= 0 || (buffer_size > 0 && capacity < static_cast<jlong>(buffer_size))) {
        LOGE("unpackRaw16Native: invalid direct buffer capacity %lld vs buffer_size %d",
             static_cast<long long>(capacity), buffer_size);
        return JNI_FALSE;
    }

    const uint8_t* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    if (!src) {
        LOGE("unpackRaw16Native: failed to get direct buffer address");
        return JNI_FALSE;
    }

    if (!black_level_arr) {
        LOGE("unpackRaw16Native: black_level_arr is null");
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
    bool ok = gxv::unpack_raw16_normalized(src, static_cast<size_t>(capacity), meta, normalized_bayer.data());
    if (ok) {
        LOGI("unpackRaw16Native: successfully unpacked %dx%d RAW16 frame", width, height);
        return JNI_TRUE;
    } else {
        LOGE("unpackRaw16Native: unpacking failed for geometry %dx%d stride %d", width, height, row_stride_bytes);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_unpackRaw10Native(
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
        LOGE("unpackRaw10Native: direct_buffer is null");
        return JNI_FALSE;
    }

    jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
    if (capacity <= 0 || (buffer_size > 0 && capacity < static_cast<jlong>(buffer_size))) {
        LOGE("unpackRaw10Native: invalid direct buffer capacity %lld vs buffer_size %d",
             static_cast<long long>(capacity), buffer_size);
        return JNI_FALSE;
    }

    const uint8_t* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    if (!src) {
        LOGE("unpackRaw10Native: failed to get direct buffer address");
        return JNI_FALSE;
    }

    if (!black_level_arr) {
        LOGE("unpackRaw10Native: black_level_arr is null");
        return JNI_FALSE;
    }

    jsize bl_len = env->GetArrayLength(black_level_arr);
    if (bl_len < 4) {
        LOGE("unpackRaw10Native: black_level array length < 4");
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

    std::vector<float> normalized_bayer(width * height);
    bool ok = gxv::unpack_raw10_normalized(src, static_cast<size_t>(capacity), meta, normalized_bayer.data());
    if (ok) {
        LOGI("unpackRaw10Native: successfully unpacked %dx%d RAW10 frame", width, height);
        return JNI_TRUE;
    } else {
        LOGE("unpackRaw10Native: unpacking failed for geometry %dx%d stride %d", width, height, row_stride_bytes);
        return JNI_FALSE;
    }
}

} // extern "C"
