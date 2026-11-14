#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace gxv {

// Black level per 2x2 CFA position, in *sensor readout order*
// (index = ((y & 1) << 1) | (x & 1)). This matches the layout of
// android.sensor.dynamicBlackLevel / android.sensor.blackLevelPattern,
// independent of whether the CFA is RGGB, BGGR, GRBG or GBRG.
using CfaBlackLevel = std::array<float, 4>;

// Values mirror android.sensor.info.colorFilterArrangement.
enum class CfaPattern : int { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3 };

// Per-frame metadata copied from CaptureResult / CameraCharacteristics.
// Colour calibration (ColorMatrix/ForwardMatrix/CalibrationTransform,
// two illuminants) is taken from the HAL, NOT hard-coded per device.
struct FrameMetadata {
    int width = 0;
    int height = 0;
    int row_stride_bytes = 0;      // Image.Plane.getRowStride(); may include padding
    int64_t timestamp_ns = 0;
    int64_t exposure_ns = 0;
    int iso = 0;
    float white_level = 1023.0f;   // SENSOR_DYNAMIC_WHITE_LEVEL, fallback SENSOR_INFO_WHITE_LEVEL
    CfaBlackLevel black_level{};   // SENSOR_DYNAMIC_BLACK_LEVEL, fallback SENSOR_BLACK_LEVEL_PATTERN
    CfaPattern cfa = CfaPattern::RGGB;
    std::array<float, 3> as_shot_neutral{1.f, 1.f, 1.f};  // SENSOR_NEUTRAL_COLOR_POINT
    float ev_offset = 0.0f;        // 0.0f for EV0 ZSL, -2.5f for LOFIC highlight bracket
};

// Per-CFA noise model: variance = scale[c] * intensity + offset[c]
// Derived from android.sensor.noiseProfile.
struct NoiseProfile {
    std::array<float, 4> scale{1e-4f, 1e-4f, 1e-4f, 1e-4f};
    std::array<float, 4> offset{1e-6f, 1e-6f, 1e-6f, 1e-6f};
};

// Lightweight view or owned container for multi-frame Bayer processing.
struct RawFrame {
    FrameMetadata meta;
    NoiseProfile noise;
    const float* normalized_bayer = nullptr;
    size_t pixel_count = 0;
};

// 400 Hz gyroscope sample for Synthetic EVS PSF deconvolution and alignment seeding.
struct GyroSample {
    int64_t timestamp_ns = 0;
    float wx = 0.0f;  // rad/s around X (pitch)
    float wy = 0.0f;  // rad/s around Y (yaw)
    float wz = 0.0f;  // rad/s around Z (roll)
};

// Telemetry timings (microseconds) captured across native pipeline stages.
struct SignaturePipelineTelemetry {
    double unpack_ms = 0.0;
    double align_merge_ms = 0.0;
    double tonemap_ms = 0.0;
    double deblur_ms = 0.0;
    double drizzle_ms = 0.0;
};

// ImageFormat.RAW10 (MIPI CSI-2 packed: 4 px in 5 bytes, LSBs in 5th byte,
// pixel 0 in bits [1:0]). Output: normalized, black-level-subtracted floats
// in [0, 1], tightly packed (width * height).
// Returns false on invalid geometry (width % 4 != 0, stride too small, ...).
bool unpack_raw10_normalized(const uint8_t* src, size_t src_size,
                             const FrameMetadata& meta, float* dst);

// ImageFormat.RAW_SENSOR (16 bits/pixel, unpacked; stride in bytes).
bool unpack_raw16_normalized(const uint8_t* src, size_t src_size,
                             const FrameMetadata& meta, float* dst);

}  // namespace gxv
