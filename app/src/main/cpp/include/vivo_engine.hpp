#pragma once
// Burst-fusion + "look" stages. Names describe *what* the stage does;
// the target rendering style (vivo-like natural/vivid looks) is achieved by
// our own tuning, not by using vivo code or binaries (see docs/LEGAL_AND_SOURCES.md).

#include "raw_pipeline.hpp"

#include <vector>

namespace gxv {

enum class LookProfile { Natural, Vivid };

struct BurstFrame {
    FrameMetadata meta;
    std::vector<float> bayer;  // normalized output of unpack_*_normalized
};

struct FusionParams {
    int tile_size = 16;            // alignment tile (Bayer px), HDR+-style
    int pyramid_levels = 4;
    float noise_scale = 1.0f;      // scales SENSOR_NOISE_PROFILE-derived sigma
    float ghost_threshold = 3.0f;  // residual > k * sigma -> reject sample
};

struct SkinParams {
    bool enabled = true;
    float smoothing = 0.40f;       // low-frequency band smoothing strength [0..1]
    float detail_gain = 1.00f;     // high-frequency band gain; 1.0 = keep pores as-is
};

struct ToneParams {
    float highlight_knee = 0.72f;  // start of shoulder compression (linear domain)
};

class BurstFuser {
public:
    virtual ~BurstFuser() = default;
    // Align + merge in the Bayer domain. `ref_index` chosen by sharpness/gyro.
    virtual bool fuse(const std::vector<BurstFrame>& frames, size_t ref_index,
                      const FusionParams& p, std::vector<float>& out_bayer) = 0;
};

class Finisher {
public:
    virtual ~Finisher() = default;
    // Demosaic -> WB (as-shot neutral) -> camera->XYZ via HAL ForwardMatrix
    // -> Display P3/sRGB -> tone curve -> look LUT -> skin -> sharpen.
    virtual bool finish(const std::vector<float>& bayer, const FrameMetadata& meta,
                        LookProfile look, const ToneParams& tone,
                        const SkinParams& skin, std::vector<float>& out_rgb) = 0;
};

}  // namespace gxv
