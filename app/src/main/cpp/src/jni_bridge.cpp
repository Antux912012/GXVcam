#include <jni.h>
#include <android/log.h>
#include "raw_pipeline.hpp"
#include "signature27_engine.hpp"

#include <algorithm>
#include <cstring>
#include <vector>

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

    if (!black_level_arr || width <= 0 || height <= 0) {
        LOGE("unpackRaw16Native: invalid arguments");
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

    std::vector<float> normalized_bayer(static_cast<size_t>(width) * height);
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

    if (!black_level_arr || width <= 0 || height <= 0) {
        LOGE("unpackRaw10Native: invalid arguments");
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

    std::vector<float> normalized_bayer(static_cast<size_t>(width) * height);
    bool ok = gxv::unpack_raw10_normalized(src, static_cast<size_t>(capacity), meta, normalized_bayer.data());
    if (ok) {
        LOGI("unpackRaw10Native: successfully unpacked %dx%d RAW10 frame", width, height);
        return JNI_TRUE;
    } else {
        LOGE("unpackRaw10Native: unpacking failed for geometry %dx%d stride %d", width, height, row_stride_bytes);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_processSignature27RgbaNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject direct_rgba_buffer,
    jint width,
    jint height,
    jint look_profile_id,
    jfloat shadow_lift,
    jfloat detail_gain,
    jfloat highlight_knee,
    jfloat dehaze_strength,
    jfloat skin_smoothing
) {
    if (!direct_rgba_buffer || width <= 0 || height <= 0) {
        LOGE("processSignature27RgbaNative: invalid buffer or dimensions");
        return JNI_FALSE;
    }

    const size_t required_bytes = static_cast<size_t>(width) * height * 4;
    jlong capacity = env->GetDirectBufferCapacity(direct_rgba_buffer);
    if (capacity < static_cast<jlong>(required_bytes)) {
        LOGE("processSignature27RgbaNative: capacity %lld < required %zu",
             static_cast<long long>(capacity), required_bytes);
        return JNI_FALSE;
    }

    uint8_t* rgba = static_cast<uint8_t*>(env->GetDirectBufferAddress(direct_rgba_buffer));
    if (!rgba) {
        LOGE("processSignature27RgbaNative: GetDirectBufferAddress returned null");
        return JNI_FALSE;
    }

    gxv::IctcpToneConfig cfg;
    cfg.look = (look_profile_id == 1) ? gxv::LookProfile::Vivid : gxv::LookProfile::Natural;
    cfg.shadow_lift = shadow_lift;
    cfg.detail_gain = detail_gain;
    cfg.highlight_knee = highlight_knee;
    cfg.dehaze_strength = dehaze_strength;
    cfg.skin_smoothing = skin_smoothing;

    gxv::IctcpToneMapper mapper;
    bool ok = mapper.processRgba8888InPlace(rgba, static_cast<size_t>(capacity), width, height, cfg);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_integrateGyroPsfNative(
    JNIEnv* env,
    jobject /* thiz */,
    jlongArray timestamps_ns,
    jfloatArray wx_arr,
    jfloatArray wy_arr,
    jfloatArray wz_arr,
    jfloatArray intrinsics_arr,
    jfloatArray out_kernel_arr
) {
    if (!timestamps_ns || !wx_arr || !wy_arr || !wz_arr || !intrinsics_arr || !out_kernel_arr) {
        return JNI_FALSE;
    }

    jsize n = env->GetArrayLength(timestamps_ns);
    if (env->GetArrayLength(wx_arr) < n || env->GetArrayLength(wy_arr) < n ||
        env->GetArrayLength(wz_arr) < n || env->GetArrayLength(intrinsics_arr) < 5 ||
        env->GetArrayLength(out_kernel_arr) < gxv::SyntheticEvsDeblur::kKernelElements) {
        return JNI_FALSE;
    }

    std::vector<jlong> ts(static_cast<size_t>(n));
    std::vector<jfloat> wx(static_cast<size_t>(n));
    std::vector<jfloat> wy(static_cast<size_t>(n));
    std::vector<jfloat> wz(static_cast<size_t>(n));
    jfloat intr[5]{};
    jfloat kernel[gxv::SyntheticEvsDeblur::kKernelElements]{};

    if (n > 0) {
        env->GetLongArrayRegion(timestamps_ns, 0, n, ts.data());
        env->GetFloatArrayRegion(wx_arr, 0, n, wx.data());
        env->GetFloatArrayRegion(wy_arr, 0, n, wy.data());
        env->GetFloatArrayRegion(wz_arr, 0, n, wz.data());
    }
    env->GetFloatArrayRegion(intrinsics_arr, 0, 5, intr);

    std::vector<gxv::GyroSample> samples(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        samples[i].timestamp_ns = ts[i];
        samples[i].wx = wx[i];
        samples[i].wy = wy[i];
        samples[i].wz = wz[i];
    }

    bool ok = gxv::SyntheticEvsDeblur::integrateGyroPsf(samples.data(), samples.size(), intr, kernel);
    env->SetFloatArrayRegion(out_kernel_arr, 0, gxv::SyntheticEvsDeblur::kKernelElements, kernel);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_deblurRgbaWithGyroNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject direct_rgba_buffer,
    jint width,
    jint height,
    jfloatArray kernel_arr,
    jint kernel_size,
    jfloat strength
) {
    if (!direct_rgba_buffer || !kernel_arr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    const size_t required_bytes = static_cast<size_t>(width) * height * 4;
    jlong capacity = env->GetDirectBufferCapacity(direct_rgba_buffer);
    if (capacity < static_cast<jlong>(required_bytes)) {
        return JNI_FALSE;
    }
    if (kernel_size < 3 || env->GetArrayLength(kernel_arr) < kernel_size * kernel_size) {
        return JNI_FALSE;
    }

    uint8_t* rgba = static_cast<uint8_t*>(env->GetDirectBufferAddress(direct_rgba_buffer));
    if (!rgba) return JNI_FALSE;

    std::vector<jfloat> kernel(static_cast<size_t>(kernel_size) * kernel_size);
    env->GetFloatArrayRegion(kernel_arr, 0, kernel_size * kernel_size, kernel.data());

    bool ok = gxv::SyntheticEvsDeblur::deblurRgba8888WithPsf(
        rgba, static_cast<size_t>(capacity), width, height, kernel.data(), kernel_size, strength);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_deblurRgbaWithGyroAndEventsNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject direct_rgba_buffer,
    jint width,
    jint height,
    jfloatArray kernel_arr,
    jint kernel_size,
    jbyteArray event_mask_arr,
    jint mask_w,
    jint mask_h,
    jfloat strength
) {
    if (!direct_rgba_buffer || !kernel_arr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }

    const size_t required_bytes = static_cast<size_t>(width) * height * 4;
    jlong capacity = env->GetDirectBufferCapacity(direct_rgba_buffer);
    if (capacity < static_cast<jlong>(required_bytes)) {
        return JNI_FALSE;
    }
    if (kernel_size < 3 || env->GetArrayLength(kernel_arr) < kernel_size * kernel_size) {
        return JNI_FALSE;
    }

    uint8_t* rgba = static_cast<uint8_t*>(env->GetDirectBufferAddress(direct_rgba_buffer));
    if (!rgba) return JNI_FALSE;

    std::vector<jfloat> kernel(static_cast<size_t>(kernel_size) * kernel_size);
    env->GetFloatArrayRegion(kernel_arr, 0, kernel_size * kernel_size, kernel.data());

    std::vector<int8_t> mask_copy;
    const int8_t* mask_ptr = nullptr;
    if (event_mask_arr != nullptr && mask_w > 0 && mask_h > 0) {
        const size_t mask_len = static_cast<size_t>(mask_w) * mask_h;
        if (env->GetArrayLength(event_mask_arr) >= static_cast<jsize>(mask_len)) {
            mask_copy.resize(mask_len);
            env->GetByteArrayRegion(event_mask_arr, 0, static_cast<jsize>(mask_len),
                                    reinterpret_cast<jbyte*>(mask_copy.data()));
            mask_ptr = mask_copy.data();
        }
    }

    bool ok = gxv::SyntheticEvsDeblur::deblurRgba8888WithPsfAndEvents(
        rgba, static_cast<size_t>(capacity), width, height,
        kernel.data(), kernel_size, mask_ptr, mask_w, mask_h, strength);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_gxvcam_NativeEngineBridge_fuseRaw16BurstToRgbaNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray direct_raw_buffers,
    jint width,
    jint height,
    jint row_stride_bytes,
    jfloat white_level,
    jfloatArray black_level_arr,
    jfloatArray ev_offsets_arr,
    jint cfa_pattern,
    jint look_profile_id,
    jfloat highlight_knee,
    jobject out_rgba_buffer
) {
    if (!direct_raw_buffers || !black_level_arr || !ev_offsets_arr || !out_rgba_buffer) {
        return JNI_FALSE;
    }
    if (width < 2 || height < 2 || row_stride_bytes < width * 2) {
        return JNI_FALSE;
    }

    const jsize count = env->GetArrayLength(direct_raw_buffers);
    if (count <= 0 || env->GetArrayLength(ev_offsets_arr) < count ||
        env->GetArrayLength(black_level_arr) < 4) {
        return JNI_FALSE;
    }

    const size_t out_required = static_cast<size_t>(width) * height * 4;
    jlong out_cap = env->GetDirectBufferCapacity(out_rgba_buffer);
    uint8_t* out_rgba = static_cast<uint8_t*>(env->GetDirectBufferAddress(out_rgba_buffer));
    if (out_cap < static_cast<jlong>(out_required) || !out_rgba) {
        return JNI_FALSE;
    }

    jfloat bl_elements[4]{};
    env->GetFloatArrayRegion(black_level_arr, 0, 4, bl_elements);
    std::vector<jfloat> evs(static_cast<size_t>(count));
    env->GetFloatArrayRegion(ev_offsets_arr, 0, count, evs.data());

    std::vector<std::vector<float>> unpacked_storage;
    unpacked_storage.reserve(static_cast<size_t>(count));
    std::vector<gxv::RawFrame> raw_views;
    raw_views.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        jobject buf_obj = env->GetObjectArrayElement(direct_raw_buffers, i);
        if (!buf_obj) continue;

        jlong cap = env->GetDirectBufferCapacity(buf_obj);
        const uint8_t* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf_obj));
        env->DeleteLocalRef(buf_obj);
        if (!src || cap <= 0) continue;

        gxv::FrameMetadata meta;
        meta.width = width;
        meta.height = height;
        meta.row_stride_bytes = row_stride_bytes;
        meta.white_level = white_level;
        meta.black_level = {bl_elements[0], bl_elements[1], bl_elements[2], bl_elements[3]};
        meta.cfa = static_cast<gxv::CfaPattern>(std::clamp(cfa_pattern, 0, 3));
        meta.ev_offset = evs[i];

        std::vector<float> bayer(static_cast<size_t>(width) * height);
        if (gxv::unpack_raw16_normalized(src, static_cast<size_t>(cap), meta, bayer.data())) {
            unpacked_storage.push_back(std::move(bayer));
            gxv::RawFrame view;
            view.meta = meta;
            view.normalized_bayer = unpacked_storage.back().data();
            view.pixel_count = unpacked_storage.back().size();
            raw_views.push_back(view);
        }
    }

    if (raw_views.empty()) {
        return JNI_FALSE;
    }

    gxv::BayerPyramidAligner aligner;
    std::vector<float> fused_bayer;
    gxv::NoiseProfile np;
    if (aligner.dispatchGreenPyramidL1(nullptr, raw_views.data(),
                                       static_cast<uint32_t>(raw_views.size()),
                                       np, fused_bayer, highlight_knee) != VK_SUCCESS) {
        return JNI_FALSE;
    }

    gxv::IctcpToneMapper mapper;
    gxv::ToneParams tp;
    tp.highlight_knee = highlight_knee;
    gxv::SkinParams sp;
    std::vector<float> rgb_out;
    gxv::LookProfile look = (look_profile_id == 1) ? gxv::LookProfile::Vivid : gxv::LookProfile::Natural;
    if (!mapper.finish(fused_bayer, raw_views[0].meta, look, tp, sp, rgb_out)) {
        return JNI_FALSE;
    }

    const size_t px_count = static_cast<size_t>(width) * height;
    for (size_t i = 0; i < px_count; ++i) {
        out_rgba[i * 4 + 0] = static_cast<uint8_t>(std::clamp(std::round(rgb_out[i * 3 + 0] * 255.0f), 0.0f, 255.0f));
        out_rgba[i * 4 + 1] = static_cast<uint8_t>(std::clamp(std::round(rgb_out[i * 3 + 1] * 255.0f), 0.0f, 255.0f));
        out_rgba[i * 4 + 2] = static_cast<uint8_t>(std::clamp(std::round(rgb_out[i * 3 + 2] * 255.0f), 0.0f, 255.0f));
        out_rgba[i * 4 + 3] = 255;
    }

    LOGI("fuseRaw16BurstToRgbaNative: fused %zu frames (%dx%d)", raw_views.size(), width, height);
    return JNI_TRUE;
}

}  // extern "C"
