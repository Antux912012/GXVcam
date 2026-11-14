#pragma once

#include "raw_pipeline.hpp"
#include "vivo_engine.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

#ifdef __ANDROID__
#include <vulkan/vulkan.h>
#else
// Host unit-test forward declarations when building without Android Vulkan headers.
struct VkCommandBuffer_T;
using VkCommandBuffer = VkCommandBuffer_T*;
using VkResult = int;
#ifndef VK_SUCCESS
#define VK_SUCCESS 0
#endif
#ifndef VK_ERROR_INITIALIZATION_FAILED
#define VK_ERROR_INITIALIZATION_FAILED (-3)
#endif
#endif

namespace gxv {

// -----------------------------------------------------------------------------
// 1. Bayer Pyramid Alignment & Synthetic LOFIC Multi-Frame Fusion
// -----------------------------------------------------------------------------
class BayerPyramidAligner : public BurstFuser {
public:
    bool fuse(const std::vector<BurstFrame>& frames,
              size_t ref_index,
              const FusionParams& p,
              std::vector<float>& out_bayer) override;

    // Vulkan-compatible L1 Green-channel pyramid dispatch & alignment/fusion entrypoint.
    // When `cmd` is nullptr, executes the deterministic CPU SIMD-friendly path.
    VkResult dispatchGreenPyramidL1(VkCommandBuffer cmd,
                                    const RawFrame* frames,
                                    uint32_t count,
                                    const NoiseProfile& np,
                                    std::vector<float>& out_bayer,
                                    float highlight_knee = 0.72f);

    // Builds 2x2-averaged grayscale (Bayer L1) for coarse-to-fine alignment.
    static bool buildBayerL1Luma(const float* bayer, int width, int height,
                                 std::vector<float>& out_luma,
                                 int& out_w, int& out_h);
};

// -----------------------------------------------------------------------------
// 2. ICtCp JND Tone Mapping, Dark-Channel Backlit Dehaze & Pantone 3D-LUT
// -----------------------------------------------------------------------------
struct IctcpToneConfig {
    LookProfile look = LookProfile::Natural;
    float shadow_lift = 0.14f;
    float detail_gain = 1.18f;
    float highlight_knee = 0.72f;
    float dehaze_strength = 0.25f;
    float skin_smoothing = 0.40f;
    float skin_detail_gain = 1.00f;
    bool enable_uv_awb_guard = true;
};

class IctcpToneMapper : public Finisher {
public:
    bool finish(const std::vector<float>& bayer,
                const FrameMetadata& meta,
                LookProfile look,
                const ToneParams& tone,
                const SkinParams& skin,
                std::vector<float>& out_rgb) override;

    // Full linear RGB finishing pass (RGB interleaved float [0..1+]).
    bool processLinearRgb(std::vector<float>& rgb,
                          int width,
                          int height,
                          const IctcpToneConfig& cfg);

    // Fast in-place RGBA_8888 finishing pass for GXVImageProcessor JNI bridge.
    bool processRgba8888InPlace(uint8_t* rgba,
                                size_t byte_size,
                                int width,
                                int height,
                                const IctcpToneConfig& cfg);

    // ST.2084 PQ transfer function matching ictcp_jnd_tonemap.frag `linearToPQ`.
    static std::array<float, 3> linearToPQ(const std::array<float, 3>& linear_rgb);
    static std::array<float, 3> pqToLinear(const std::array<float, 3>& pq_rgb);

    // CIE 1976 (u', v') gamut guard for purple/magenta lighting spikes.
    static void applyCie1976UvAwbGuard(float& r, float& g, float& b);
};

// -----------------------------------------------------------------------------
// 3. 400 Hz Gyro Synthetic EVS Action Deblur
// -----------------------------------------------------------------------------
class SyntheticEvsDeblur {
public:
    static constexpr int kKernelSize = 9;
    static constexpr int kKernelElements = kKernelSize * kKernelSize;

    // Integrates 400 Hz gyro angular rates into a normalized 9x9 PSF blur kernel.
    // `intrinsics` = [fx_px, fy_px, cx_px, cy_px, exposure_sec].
    static bool integrateGyroPsf(const GyroSample* samples,
                                 size_t n,
                                 const float intrinsics[5],
                                 float* outKernel);

    // Computes normalized log-luminance event mask between reference and short action frame.
    static bool computeLogLumaEventMask(const float* ref_luma,
                                        const float* action_luma,
                                        int width,
                                        int height,
                                        float threshold,
                                        std::vector<float>& out_mask);

    // Applies PSF Richardson-Lucy deconvolution gated by the synthetic EVS event mask.
    static bool deblurRgba8888WithPsf(uint8_t* rgba,
                                      size_t byte_size,
                                      int width,
                                      int height,
                                      const float* kernel,
                                      int kernel_size,
                                      float strength = 0.65f);

    // Event-gated Richardson-Lucy deconvolution: moving contours (event_mask != 0) receive
    // full gyro PSF sharpening while static background/shadows stay noise-free.
    static bool deblurRgba8888WithPsfAndEvents(uint8_t* rgba,
                                               size_t byte_size,
                                               int width,
                                               int height,
                                               const float* kernel,
                                               int kernel_size,
                                               const int8_t* event_mask,
                                               int mask_w,
                                               int mask_h,
                                               float strength = 0.65f);
};

// -----------------------------------------------------------------------------
// 4. 2x 50MP Remosaic & 3x-10x S5K3K1 Sub-Pixel Drizzle MFSR
// -----------------------------------------------------------------------------
class DrizzleSuperRes {
public:
    // Executes sub-pixel alignment + Gaussian/Lanczos Drizzle super-resolution
    // on Bayer or linear RGB burst frames.
    static bool executeSubPixelDrizzle(const RawFrame* s5k3k1Burst,
                                       size_t count,
                                       float scaleFactor,
                                       std::vector<float>& out_bayer,
                                       int& out_width,
                                       int& out_height);

    // RGBA_8888 burst Drizzle MFSR for CameraX/Camera2 JPEG/YUV zoom bursts.
    static bool executeRgbaDrizzle(const std::vector<const uint8_t*>& frames,
                                   int in_width,
                                   int in_height,
                                   float scaleFactor,
                                   std::vector<uint8_t>& out_rgba,
                                   int& out_width,
                                   int& out_height);

    // Crops the central 4096x3072 window from a 50MP (8192x6144) Bayer frame
    // starting at even coordinates (2048, 1536) to preserve RGGB/BGGR CFA phase.
    static bool cropCenter50MpWindow(const uint16_t* full8192x6144,
                                     size_t full_pixel_count,
                                     uint16_t* out4096x3072,
                                     size_t out_pixel_count);
};

}  // namespace gxv
