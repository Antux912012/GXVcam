// Host-side unit test (no Android needed):
//   g++ -std=c++20 -Wall -Wextra -I../include ../src/raw_unpacker.cpp test_raw_unpacker.cpp -o /tmp/t && /tmp/t
#include "raw_pipeline.hpp"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace gxv;

static void pack_raw10(const std::vector<uint16_t>& px, int w, int h, int stride,
                       std::vector<uint8_t>& out) {
    out.assign(static_cast<size_t>(stride) * h, 0xEE);  // padding filled with junk
    for (int y = 0; y < h; ++y)
        for (int g = 0; g < w / 4; ++g) {
            uint8_t* p = &out[y * stride + g * 5];
            p[4] = 0;
            for (int i = 0; i < 4; ++i) {
                uint16_t v = px[y * w + g * 4 + i];
                p[i] = static_cast<uint8_t>(v >> 2);
                p[4] |= static_cast<uint8_t>((v & 3) << (2 * i));
            }
        }
}

int main() {
    const int w = 8, h = 4, stride = 16;  // 10 bytes of data + 6 bytes padding per row
    std::vector<uint16_t> px(w * h);
    for (int i = 0; i < w * h; ++i) px[i] = static_cast<uint16_t>((i * 37 + 1) % 1024);
    px[0] = 0; px[1] = 1023;  // extremes

    std::vector<uint8_t> packed;
    pack_raw10(px, w, h, stride, packed);

    FrameMetadata m;
    m.width = w; m.height = h; m.row_stride_bytes = stride;
    m.white_level = 1023.f;
    m.black_level = {64.f, 63.f, 65.f, 62.f};

    std::vector<float> out(w * h);
    assert(unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));

    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float bl = m.black_level[((y & 1) << 1) | (x & 1)];
            float exp = std::fmin(std::fmax((px[y * w + x] - bl) / (1023.f - bl), 0.f), 1.f);
            assert(std::fabs(out[y * w + x] - exp) < 1e-6f);
        }
    assert(out[0] == 0.f && out[1] == 1.f);

    // =========================================================================
    // BOUNDARY & STABILITY TESTS (ASD-STE100 Phase 2 Verification)
    // =========================================================================

    // 1. Zero-length buffer safety test
    assert(!unpack_raw10_normalized(packed.data(), 0, m, out.data()));
    assert(!unpack_raw16_normalized(packed.data(), 0, m, out.data()));

    // 2. Truncated buffer test (1 byte short of needed bytes)
    const size_t needed_bytes = static_cast<size_t>(stride) * (h - 1) + (w / 4 * 5);
    assert(!unpack_raw10_normalized(packed.data(), needed_bytes - 1, m, out.data()));
    assert(unpack_raw10_normalized(packed.data(), needed_bytes, m, out.data()));

    // 3. Null pointer safety test
    assert(!unpack_raw10_normalized(nullptr, packed.size(), m, out.data()));
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, nullptr));
    assert(!unpack_raw16_normalized(nullptr, packed.size(), m, out.data()));
    assert(!unpack_raw16_normalized(packed.data(), packed.size(), m, nullptr));

    // 4. Stride smaller than min_row_bytes test
    m.row_stride_bytes = 9;  // min row bytes is (8 / 4 * 5) = 10
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));

    // 5. Negative and zero stride test
    m.row_stride_bytes = 0;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.row_stride_bytes = -16;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.row_stride_bytes = stride;

    // 6. Non-multiple of 4 width test
    m.width = 6;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.width = w;

    // 7. Negative and zero dimension test
    m.width = 0;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.width = -8;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.width = w;
    m.height = 0;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.height = -4;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.height = h;

    // 8. MediaTek Dimensity Padded Buffer Test (width = 4096, stride = 5184, height = 4)
    // min_row_bytes = 4096 / 4 * 5 = 5120 bytes. Stride has 64 bytes padding.
    {
        const int mtk_w = 4096;
        const int mtk_h = 4;
        const int mtk_stride = 5184;
        const size_t mtk_needed = static_cast<size_t>(mtk_stride) * (mtk_h - 1) + (mtk_w / 4 * 5);
        std::vector<uint8_t> mtk_buf(mtk_needed, 0xAA);
        std::vector<float> mtk_out(mtk_w * mtk_h);

        FrameMetadata mtk_meta;
        mtk_meta.width = mtk_w;
        mtk_meta.height = mtk_h;
        mtk_meta.row_stride_bytes = mtk_stride;
        mtk_meta.white_level = 1023.f;
        mtk_meta.black_level = {64.f, 64.f, 64.f, 64.f};

        // Assert exact buffer capacity passes without fault
        assert(unpack_raw10_normalized(mtk_buf.data(), mtk_buf.size(), mtk_meta, mtk_out.data()));

        // Assert truncated buffer fails safely
        assert(!unpack_raw10_normalized(mtk_buf.data(), mtk_needed - 1, mtk_meta, mtk_out.data()));
    }

    // 9. RAW16 path tests
    m.width = w; m.height = h; m.row_stride_bytes = w * 2 + 4;
    std::vector<uint8_t> raw16(m.row_stride_bytes * h, 0);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            raw16[y * m.row_stride_bytes + 2 * x] = px[y * w + x] & 0xFF;
            raw16[y * m.row_stride_bytes + 2 * x + 1] = px[y * w + x] >> 8;
        }
    std::vector<float> out16(w * h);
    assert(unpack_raw16_normalized(raw16.data(), raw16.size(), m, out16.data()));
    for (int i = 0; i < w * h; ++i) assert(std::fabs(out16[i] - out[i]) < 1e-6f);

    // Truncated RAW16 test
    const size_t needed_raw16 = static_cast<size_t>(m.row_stride_bytes) * (h - 1) + (w * 2);
    assert(!unpack_raw16_normalized(raw16.data(), needed_raw16 - 1, m, out16.data()));
    assert(unpack_raw16_normalized(raw16.data(), needed_raw16, m, out16.data()));

    std::puts("test_raw_unpacker: ALL TESTS PASSED (0 SIGSEGV, 0 Leaks)");
    return 0;
}
