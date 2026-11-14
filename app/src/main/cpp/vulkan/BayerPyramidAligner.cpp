#include "signature27_engine.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace gxv {
namespace {

struct PyramidLevel {
    int width = 0;
    int height = 0;
    std::vector<float> luma;
};

void downsample2x2(const float* src, int w, int h, PyramidLevel& dst) {
    dst.width = std::max(1, w / 2);
    dst.height = std::max(1, h / 2);
    dst.luma.assign(static_cast<size_t>(dst.width) * dst.height, 0.0f);

    for (int y = 0; y < dst.height; ++y) {
        const int sy = std::min(y * 2, h - 1);
        const int sy1 = std::min(sy + 1, h - 1);
        for (int x = 0; x < dst.width; ++x) {
            const int sx = std::min(x * 2, w - 1);
            const int sx1 = std::min(sx + 1, w - 1);
            const float sum = src[static_cast<size_t>(sy) * w + sx] +
                              src[static_cast<size_t>(sy) * w + sx1] +
                              src[static_cast<size_t>(sy1) * w + sx] +
                              src[static_cast<size_t>(sy1) * w + sx1];
            dst.luma[static_cast<size_t>(y) * dst.width + x] = 0.25f * sum;
        }
    }
}

std::vector<PyramidLevel> buildPyramid(const float* bayer, int width, int height, int levels) {
    std::vector<PyramidLevel> pyr;
    if (!bayer || width < 2 || height < 2 || levels <= 0) return pyr;

    PyramidLevel l1;
    if (!BayerPyramidAligner::buildBayerL1Luma(bayer, width, height, l1.luma, l1.width, l1.height)) {
        return pyr;
    }
    pyr.push_back(std::move(l1));

    for (int lvl = 1; lvl < levels; ++lvl) {
        const PyramidLevel& prev = pyr.back();
        if (prev.width < 4 || prev.height < 4) break;
        PyramidLevel next;
        downsample2x2(prev.luma.data(), prev.width, prev.height, next);
        pyr.push_back(std::move(next));
    }
    return pyr;
}

struct TileOffset {
    int dx = 0;
    int dy = 0;
};

// Coarse-to-fine block matching on Bayer L1 pyramid.
// Offsets returned are in *Bayer pixel coordinates* (always even to preserve CFA phase).
void alignFrameTiles(const std::vector<PyramidLevel>& ref_pyr,
                     const std::vector<PyramidLevel>& mov_pyr,
                     int tile_size,
                     int& out_tiles_x,
                     int& out_tiles_y,
                     std::vector<TileOffset>& out_offsets) {
    const int l1_w = ref_pyr[0].width;
    const int l1_h = ref_pyr[0].height;
    const int l1_tile = std::max(4, tile_size / 2);

    out_tiles_x = std::max(1, (l1_w + l1_tile - 1) / l1_tile);
    out_tiles_y = std::max(1, (l1_h + l1_tile - 1) / l1_tile);
    out_offsets.assign(static_cast<size_t>(out_tiles_x) * out_tiles_y, TileOffset{0, 0});

    const int num_levels = static_cast<int>(std::min(ref_pyr.size(), mov_pyr.size()));
    if (num_levels == 0) return;

    // Track coarse-to-fine offsets at tile centers.
    for (int ty = 0; ty < out_tiles_y; ++ty) {
        for (int tx = 0; tx < out_tiles_x; ++tx) {
            int est_dx = 0;
            int est_dy = 0;

            for (int lvl = num_levels - 1; lvl >= 0; --lvl) {
                const PyramidLevel& r = ref_pyr[lvl];
                const PyramidLevel& m = mov_pyr[lvl];
                const int scale = 1 << lvl;
                const int lvl_tile = std::max(2, l1_tile / scale);
                const int start_x = std::min(tx * lvl_tile, std::max(0, r.width - lvl_tile));
                const int start_y = std::min(ty * lvl_tile, std::max(0, r.height - lvl_tile));
                const int end_x = std::min(r.width, start_x + lvl_tile);
                const int end_y = std::min(r.height, start_y + lvl_tile);

                const int search_radius = (lvl == num_levels - 1) ? 4 : 2;
                int best_dx = est_dx;
                int best_dy = est_dy;
                float best_cost = std::numeric_limits<float>::max();

                for (int dy = est_dy - search_radius; dy <= est_dy + search_radius; ++dy) {
                    for (int dx = est_dx - search_radius; dx <= est_dx + search_radius; ++dx) {
                        float cost = 0.0f;
                        int samples = 0;
                        for (int y = start_y; y < end_y; ++y) {
                            const int my = std::clamp(y + dy, 0, m.height - 1);
                            for (int x = start_x; x < end_x; ++x) {
                                const int mx = std::clamp(x + dx, 0, m.width - 1);
                                const float diff = r.luma[static_cast<size_t>(y) * r.width + x] -
                                                   m.luma[static_cast<size_t>(my) * m.width + mx];
                                // L2 on coarse levels, L1 on finest level
                                cost += (lvl == 0) ? std::fabs(diff) : (diff * diff);
                                ++samples;
                            }
                        }
                        if (samples > 0) {
                            cost /= static_cast<float>(samples);
                            if (cost < best_cost) {
                                best_cost = cost;
                                best_dx = dx;
                                best_dy = dy;
                            }
                        }
                    }
                }
                est_dx = (lvl > 0) ? best_dx * 2 : best_dx;
                est_dy = (lvl > 0) ? best_dy * 2 : best_dy;
            }

            // Convert L1 offset to full Bayer pixel offset (x2 keeps CFA parity identical!)
            out_offsets[static_cast<size_t>(ty) * out_tiles_x + tx] = TileOffset{est_dx * 2, est_dy * 2};
        }
    }
}

}  // namespace

bool BayerPyramidAligner::buildBayerL1Luma(const float* bayer, int width, int height,
                                          std::vector<float>& out_luma,
                                          int& out_w, int& out_h) {
    if (!bayer || width < 2 || height < 2) return false;
    out_w = width / 2;
    out_h = height / 2;
    out_luma.assign(static_cast<size_t>(out_w) * out_h, 0.0f);

    for (int y = 0; y < out_h; ++y) {
        const int by = y * 2;
        for (int x = 0; x < out_w; ++x) {
            const int bx = x * 2;
            const float p00 = bayer[static_cast<size_t>(by) * width + bx];
            const float p01 = bayer[static_cast<size_t>(by) * width + bx + 1];
            const float p10 = bayer[static_cast<size_t>(by + 1) * width + bx];
            const float p11 = bayer[static_cast<size_t>(by + 1) * width + bx + 1];
            out_luma[static_cast<size_t>(y) * out_w + x] = 0.25f * (p00 + p01 + p10 + p11);
        }
    }
    return true;
}

bool BayerPyramidAligner::fuse(const std::vector<BurstFrame>& frames,
                               size_t ref_index,
                               const FusionParams& p,
                               std::vector<float>& out_bayer) {
    if (frames.empty() || ref_index >= frames.size()) return false;

    // Reorder view array with reference frame at index 0 for dispatchGreenPyramidL1.
    std::vector<RawFrame> raw_views;
    raw_views.reserve(frames.size());

    auto make_view = [](const BurstFrame& bf) {
        RawFrame rf;
        rf.meta = bf.meta;
        rf.normalized_bayer = bf.bayer.data();
        rf.pixel_count = bf.bayer.size();
        return rf;
    };

    raw_views.push_back(make_view(frames[ref_index]));
    for (size_t i = 0; i < frames.size(); ++i) {
        if (i != ref_index) {
            raw_views.push_back(make_view(frames[i]));
        }
    }

    NoiseProfile np;
    np.scale.fill(1e-4f * std::max(0.1f, p.noise_scale));
    np.offset.fill(1e-6f * std::max(0.1f, p.noise_scale));

    return dispatchGreenPyramidL1(nullptr, raw_views.data(),
                                  static_cast<uint32_t>(raw_views.size()),
                                  np, out_bayer, 0.72f) == VK_SUCCESS;
}

VkResult BayerPyramidAligner::dispatchGreenPyramidL1(VkCommandBuffer /*cmd*/,
                                                     const RawFrame* frames,
                                                     uint32_t count,
                                                     const NoiseProfile& np,
                                                     std::vector<float>& out_bayer,
                                                     float highlight_knee) {
    if (!frames || count == 0) return VK_ERROR_INITIALIZATION_FAILED;

    const RawFrame& ref = frames[0];
    const int width = ref.meta.width;
    const int height = ref.meta.height;
    if (width < 2 || height < 2 || !ref.normalized_bayer) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    const size_t total_px = static_cast<size_t>(width) * height;
    if (ref.pixel_count < total_px) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    if (count == 1) {
        out_bayer.assign(ref.normalized_bayer, ref.normalized_bayer + total_px);
        return VK_SUCCESS;
    }

    const int tile_size = 16;
    const auto ref_pyr = buildPyramid(ref.normalized_bayer, width, height, 4);
    if (ref_pyr.empty()) return VK_ERROR_INITIALIZATION_FAILED;

    std::vector<float> accum_ev0(total_px, 0.0f);
    std::vector<float> weight_ev0(total_px, 0.0f);

    // Seed accumulator with reference frame (w_ref = 1.0)
    for (size_t i = 0; i < total_px; ++i) {
        accum_ev0[i] = ref.normalized_bayer[i];
        weight_ev0[i] = 1.0f;
    }

    // Synthetic LOFIC highlight accumulator for EV-underexposed frames (e.g. EV-2.5)
    std::vector<float> accum_lofic(total_px, 0.0f);
    std::vector<float> weight_lofic(total_px, 0.0f);

    const float c_reject = 2.0f;
    for (uint32_t k = 1; k < count; ++k) {
        const RawFrame& mov = frames[k];
        if (!mov.normalized_bayer || mov.meta.width != width || mov.meta.height != height ||
            mov.pixel_count < total_px) {
            continue;
        }

        const bool is_short_bracket = (mov.meta.ev_offset <= -0.5f);
        const float ev_gain = is_short_bracket
                                  ? std::pow(2.0f, ref.meta.ev_offset - mov.meta.ev_offset)
                                  : 1.0f;

        std::vector<float> scaled_bayer;
        const float* align_ptr = mov.normalized_bayer;
        if (is_short_bracket) {
            scaled_bayer.resize(total_px);
            for (size_t i = 0; i < total_px; ++i) {
                scaled_bayer[i] = std::min(1.0f, mov.normalized_bayer[i] * ev_gain);
            }
            align_ptr = scaled_bayer.data();
        }

        const auto mov_pyr = buildPyramid(align_ptr, width, height, 4);
        if (mov_pyr.empty()) continue;

        int tiles_x = 0, tiles_y = 0;
        std::vector<TileOffset> offsets;
        alignFrameTiles(ref_pyr, mov_pyr, tile_size, tiles_x, tiles_y, offsets);

        for (int y = 0; y < height; ++y) {
            const int ty = std::min(tiles_y - 1, y / tile_size);
            const int cfa_row = (y & 1) << 1;
            for (int x = 0; x < width; ++x) {
                const int tx = std::min(tiles_x - 1, x / tile_size);
                const TileOffset& off = offsets[static_cast<size_t>(ty) * tiles_x + tx];

                const int mx = std::clamp(x + off.dx, 0, width - 1);
                const int my = std::clamp(y + off.dy, 0, height - 1);
                const size_t r_idx = static_cast<size_t>(y) * width + x;
                const size_t m_idx = static_cast<size_t>(my) * width + mx;

                const float r_val = ref.normalized_bayer[r_idx];
                const int cfa_idx = cfa_row | (x & 1);
                const float sigma2 = std::max(1e-7f, np.scale[cfa_idx] * r_val + np.offset[cfa_idx]);
                const float sigma = std::sqrt(sigma2);

                if (is_short_bracket) {
                    // Synthetic LOFIC fusion: recover highlights where EV0 saturates near knee..1.0
                    const float m_scaled = mov.normalized_bayer[m_idx] * ev_gain;
                    if (r_val >= highlight_knee * 0.85f) {
                        const float diff = std::max(0.0f, std::fabs(m_scaled - r_val) - (c_reject * sigma * ev_gain));
                        const float w = (r_val >= 0.98f)
                                            ? 1.0f
                                            : std::exp(-(diff * diff) / (2.0f * sigma2 * ev_gain * ev_gain + 1e-6f));
                        accum_lofic[r_idx] += w * m_scaled;
                        weight_lofic[r_idx] += w;
                    }
                } else {
                    const float m_val = mov.normalized_bayer[m_idx];
                    const float diff = std::max(0.0f, std::fabs(m_val - r_val) - c_reject * sigma);
                    const float w = std::exp(-(diff * diff) / (2.0f * sigma2));
                    accum_ev0[r_idx] += w * m_val;
                    weight_ev0[r_idx] += w;
                }
            }
        }
    }

    out_bayer.resize(total_px);
    const float knee = std::clamp(highlight_knee, 0.35f, 0.95f);
    for (size_t i = 0; i < total_px; ++i) {
        const float merged_ev0 = accum_ev0[i] / std::max(1e-6f, weight_ev0[i]);
        if (weight_lofic[i] > 1e-4f && merged_ev0 >= knee) {
            const float lofic_hdr = accum_lofic[i] / weight_lofic[i];
            const float blend = std::clamp((merged_ev0 - knee) / (1.0f - knee + 1e-4f), 0.0f, 1.0f);
            out_bayer[i] = merged_ev0 * (1.0f - blend) + lofic_hdr * blend;
        } else {
            out_bayer[i] = merged_ev0;
        }
    }

    return VK_SUCCESS;
}

}  // namespace gxv
