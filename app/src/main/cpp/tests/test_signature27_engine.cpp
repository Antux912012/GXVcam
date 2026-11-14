// Host-side unit test for Signature 27 native pipelines:
//   g++ -std=c++20 -Wall -Wextra -I../include \
//       ../src/raw_unpacker.cpp ../src/IctcpToneMapper.cpp \
//       ../vulkan/BayerPyramidAligner.cpp ../motion/SyntheticEvsDeblur.cpp \
//       ../zoom/DrizzleSuperRes.cpp test_signature27_engine.cpp -o /tmp/s27_test && /tmp/s27_test

#include "signature27_engine.hpp"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace gxv;

int main() {
    // 1. Test BayerPyramidAligner + Synthetic LOFIC (EV0 + EV-2.5) fusion
    {
        const int w = 32, h = 32;
        std::vector<float> bayer_ev0(w * h, 0.30f);
        std::vector<float> bayer_ev_short(w * h, 0.30f / 5.65685f);  // 2^-2.5

        // Clip highlights in EV0 center patch, keep unclipped in EV-2.5
        for (int y = 8; y < 16; ++y) {
            for (int x = 8; x < 16; ++x) {
                bayer_ev0[y * w + x] = 1.0f;
                bayer_ev_short[y * w + x] = 0.40f;  // -> 0.40 * 5.65685 = ~2.26 LOFIC HDR!
            }
        }

        RawFrame f0, f1;
        f0.meta.width = w;
        f0.meta.height = h;
        f0.meta.ev_offset = 0.0f;
        f0.normalized_bayer = bayer_ev0.data();
        f0.pixel_count = bayer_ev0.size();

        f1.meta.width = w;
        f1.meta.height = h;
        f1.meta.ev_offset = -2.5f;
        f1.normalized_bayer = bayer_ev_short.data();
        f1.pixel_count = bayer_ev_short.size();

        std::vector<RawFrame> burst = {f0, f1};
        std::vector<float> fused;
        NoiseProfile np;
        BayerPyramidAligner aligner;
        assert(aligner.dispatchGreenPyramidL1(nullptr, burst.data(), 2, np, fused, 0.72f) == VK_SUCCESS);
        assert(fused.size() == static_cast<size_t>(w * h));
        assert(fused[10 * w + 10] > 1.5f);  // Highlight recovered above 1.0!
        assert(std::fabs(fused[2 * w + 2] - 0.30f) < 0.02f);
    }

    // 2. Test IctcpToneMapper PQ round-trip, CIE 1976 AWB guard, and RGBA_8888 finish
    {
        std::array<float, 3> lin{0.18f, 0.42f, 0.09f};
        auto pq = IctcpToneMapper::linearToPQ(lin);
        auto back = IctcpToneMapper::pqToLinear(pq);
        for (int i = 0; i < 3; ++i) {
            assert(std::fabs(back[i] - lin[i]) < 1e-4f);
        }

        float r = 0.8f, g = 0.2f, b = 0.85f;  // Purple spike
        IctcpToneMapper::applyCie1976UvAwbGuard(r, g, b);
        assert(g > 0.2f && r < 0.8f);

        const int w = 16, h = 16;
        std::vector<uint8_t> rgba(w * h * 4, 0);
        for (int i = 0; i < w * h; ++i) {
            rgba[i * 4 + 0] = 60;
            rgba[i * 4 + 1] = 120;
            rgba[i * 4 + 2] = 180;
            rgba[i * 4 + 3] = 255;
        }
        IctcpToneMapper mapper;
        IctcpToneConfig cfg;
        cfg.look = LookProfile::Vivid;
        assert(mapper.processRgba8888InPlace(rgba.data(), rgba.size(), w, h, cfg));
        assert(!mapper.processRgba8888InPlace(nullptr, rgba.size(), w, h, cfg));
        assert(!mapper.processRgba8888InPlace(rgba.data(), 10, w, h, cfg));
    }

    // 3. Test 400 Hz Gyro PSF integration & Synthetic EVS Deblur
    {
        std::vector<GyroSample> samples(8);
        for (size_t i = 0; i < samples.size(); ++i) {
            samples[i].timestamp_ns = static_cast<int64_t>(i) * 2'500'000LL;  // 400 Hz = 2.5 ms
            samples[i].wx = 0.4f;
            samples[i].wy = -0.3f;
            samples[i].wz = 0.0f;
        }
        float intrinsics[5] = {3000.0f, 3000.0f, 2048.0f, 1536.0f, 0.020f};
        float kernel[SyntheticEvsDeblur::kKernelElements]{};
        assert(SyntheticEvsDeblur::integrateGyroPsf(samples.data(), samples.size(), intrinsics, kernel));

        float ksum = 0.0f;
        for (float v : kernel) ksum += v;
        assert(std::fabs(ksum - 1.0f) < 1e-4f);

        const int w = 16, h = 16;
        std::vector<float> ref_luma(w * h, 0.2f);
        std::vector<float> act_luma(w * h, 0.2f);
        act_luma[8 * w + 8] = 0.9f;
        std::vector<float> mask;
        assert(SyntheticEvsDeblur::computeLogLumaEventMask(ref_luma.data(), act_luma.data(), w, h, 0.1f, mask));
        assert(mask[8 * w + 8] > 0.5f && mask[0] < 1e-4f);

        std::vector<uint8_t> rgba(w * h * 4, 128);
        assert(SyntheticEvsDeblur::deblurRgba8888WithPsf(rgba.data(), rgba.size(), w, h, kernel, 9, 0.65f));
    }

    // 4. Test Drizzle Super-Resolution (Bayer & RGBA)
    {
        const int w = 16, h = 16;
        std::vector<float> f0(w * h, 0.25f), f1(w * h, 0.25f);
        for (int y = 4; y < 12; ++y) {
            for (int x = 4; x < 12; ++x) {
                f0[y * w + x] = 0.8f;
                f1[y * w + x] = 0.8f;
            }
        }
        RawFrame r0, r1;
        r0.meta.width = w; r0.meta.height = h; r0.normalized_bayer = f0.data(); r0.pixel_count = f0.size();
        r1.meta.width = w; r1.meta.height = h; r1.normalized_bayer = f1.data(); r1.pixel_count = f1.size();
        std::vector<RawFrame> burst = {r0, r1};
        std::vector<float> out_bayer;
        int ow = 0, oh = 0;
        assert(DrizzleSuperRes::executeSubPixelDrizzle(burst.data(), 2, 1.5f, out_bayer, ow, oh));
        assert(ow == 24 && oh == 24);
        assert(out_bayer.size() == static_cast<size_t>(ow * oh));

        std::vector<uint8_t> rgba0(w * h * 4, 100), rgba1(w * h * 4, 102);
        std::vector<const uint8_t*> rgba_burst = {rgba0.data(), rgba1.data()};
        std::vector<uint8_t> out_rgba;
        assert(DrizzleSuperRes::executeRgbaDrizzle(rgba_burst, w, h, 1.5f, out_rgba, ow, oh));
        assert(ow == 24 && oh == 24);
    }

    // 5. Test 50MP -> 12.5MP center Bayer window crop (even boundary CFA preservation)
    {
        constexpr size_t fullW = 8192;
        constexpr size_t fullH = 6144;
        constexpr size_t cropW = 4096;
        constexpr size_t cropH = 3072;
        std::vector<uint16_t> full_bayer(fullW * fullH, 0);
        // Stamp CFA checker in center window starting at (2048, 1536)
        for (size_t y = 1536; y < 1536 + cropH; ++y) {
            for (size_t x = 2048; x < 2048 + cropW; ++x) {
                full_bayer[y * fullW + x] = static_cast<uint16_t>(((y & 1) << 1) | (x & 1));
            }
        }
        std::vector<uint16_t> cropped(cropW * cropH, 999);
        assert(!DrizzleSuperRes::cropCenter50MpWindow(nullptr, full_bayer.size(), cropped.data(), cropped.size()));
        assert(!DrizzleSuperRes::cropCenter50MpWindow(full_bayer.data(), 100, cropped.data(), cropped.size()));
        assert(DrizzleSuperRes::cropCenter50MpWindow(full_bayer.data(), full_bayer.size(), cropped.data(), cropped.size()));
        assert(cropped[0] == 0);
        assert(cropped[1] == 1);
        assert(cropped[cropW] == 2);
        assert(cropped[cropW + 1] == 3);
    }

    std::puts("test_signature27_engine: ALL TESTS PASSED");
    return 0;
}
