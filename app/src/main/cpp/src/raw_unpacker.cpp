// CPU reference implementation of RAW unpacking + black-level normalization.
// The Vulkan shader (shaders/unpack_raw10.comp) must produce bit-identical
// (within float tolerance) output; tests/test_raw_unpacker.cpp checks this file.

#include "raw_pipeline.hpp"

#include <algorithm>
#include <cstring>

namespace gxv {
namespace {

inline float normalize(uint32_t v, float bl, float wl) {
    const float range = wl - bl;
    if (range <= 0.f) return 0.f;
    return std::clamp((static_cast<float>(v) - bl) / range, 0.f, 1.f);
}

bool geometry_ok(const FrameMetadata& m, size_t src_size, size_t min_row_bytes) {
    if (m.width <= 0 || m.height <= 0 || m.row_stride_bytes <= 0) return false;
    if (static_cast<size_t>(m.row_stride_bytes) < min_row_bytes) return false;
    // Last row only needs min_row_bytes, not the full stride.
    const size_t needed =
        static_cast<size_t>(m.row_stride_bytes) * (m.height - 1) + min_row_bytes;
    return src_size >= needed;
}

}  // namespace

bool unpack_raw10_normalized(const uint8_t* src, size_t src_size,
                             const FrameMetadata& m, float* dst) {
    if (!src || !dst || m.width % 4 != 0) return false;
    const size_t row_bytes = static_cast<size_t>(m.width) / 4 * 5;
    if (!geometry_ok(m, src_size, row_bytes)) return false;

    for (int y = 0; y < m.height; ++y) {
        const uint8_t* row = src + static_cast<size_t>(y) * m.row_stride_bytes;
        float* out = dst + static_cast<size_t>(y) * m.width;
        const int cfa_row = (y & 1) << 1;
        for (int g = 0; g < m.width / 4; ++g) {
            const uint8_t* p = row + g * 5;
            const uint8_t lsb = p[4];
            for (int i = 0; i < 4; ++i) {
                const uint32_t v = (static_cast<uint32_t>(p[i]) << 2) | ((lsb >> (2 * i)) & 0x3u);
                const int x = g * 4 + i;
                out[x] = normalize(v, m.black_level[cfa_row | (x & 1)], m.white_level);
            }
        }
    }
    return true;
}

bool unpack_raw16_normalized(const uint8_t* src, size_t src_size,
                             const FrameMetadata& m, float* dst) {
    if (!src || !dst) return false;
    const size_t row_bytes = static_cast<size_t>(m.width) * 2;
    if (!geometry_ok(m, src_size, row_bytes)) return false;

    for (int y = 0; y < m.height; ++y) {
        const uint8_t* row = src + static_cast<size_t>(y) * m.row_stride_bytes;
        float* out = dst + static_cast<size_t>(y) * m.width;
        const int cfa_row = (y & 1) << 1;
        for (int x = 0; x < m.width; ++x) {
            uint16_t v;
            std::memcpy(&v, row + 2 * x, 2);  // little-endian, alignment-safe
            out[x] = normalize(v, m.black_level[cfa_row | (x & 1)], m.white_level);
        }
    }
    return true;
}

}  // namespace gxv
