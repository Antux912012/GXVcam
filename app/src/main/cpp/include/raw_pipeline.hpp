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
