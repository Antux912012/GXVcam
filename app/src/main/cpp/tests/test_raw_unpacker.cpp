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

    // Reject bad geometry
    m.row_stride_bytes = 9;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));
    m.row_stride_bytes = stride; m.width = 6;
    assert(!unpack_raw10_normalized(packed.data(), packed.size(), m, out.data()));

    // RAW16 path
    m.width = w; m.row_stride_bytes = w * 2 + 4;
    std::vector<uint8_t> raw16(m.row_stride_bytes * h, 0);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            raw16[y * m.row_stride_bytes + 2 * x] = px[y * w + x] & 0xFF;
            raw16[y * m.row_stride_bytes + 2 * x + 1] = px[y * w + x] >> 8;
        }
    std::vector<float> out16(w * h);
    assert(unpack_raw16_normalized(raw16.data(), raw16.size(), m, out16.data()));
    for (int i = 0; i < w * h; ++i) assert(std::fabs(out16[i] - out[i]) < 1e-6f);

    std::puts("test_raw_unpacker: OK");
    return 0;
}
