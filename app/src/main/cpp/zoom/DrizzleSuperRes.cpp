#include "signature27_engine.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace gxv {
namespace {

// Estimate global sub-pixel (dx, dy) between reference frame and moving frame via
// integer search + parabolic sub-pixel refinement around the center ROI.
void estimateSubPixelShift(const float* ref, const float* mov, int w, int h,
                           float& out_dx, float& out_dy) {
    const int roi_x0 = w / 4;
    const int roi_x1 = std::max(roi_x0 + 4, (w * 3) / 4);
    const int roi_y0 = h / 4;
    const int roi_y1 = std::max(roi_y0 + 4, (h * 3) / 4);
    constexpr int kRadius = 3;

    float costs[2 * kRadius + 1][2 * kRadius + 1]{};
    int best_ix = 0, best_iy = 0;
    float best_cost = std::numeric_limits<float>::max();

    for (int dy = -kRadius; dy <= kRadius; ++dy) {
        for (int dx = -kRadius; dx <= kRadius; ++dx) {
            float err = 0.0f;
            int cnt = 0;
            for (int y = roi_y0; y < roi_y1; y += 2) {
                const int my = std::clamp(y + dy, 0, h - 1);
                for (int x = roi_x0; x < roi_x1; x += 2) {
                    const int mx = std::clamp(x + dx, 0, w - 1);
                    const float d = ref[static_cast<size_t>(y) * w + x] -
                                    mov[static_cast<size_t>(my) * w + mx];
                    err += d * d;
                    ++cnt;
                }
            }
            const float avg = cnt > 0 ? (err / static_cast<float>(cnt)) : 1e6f;
            costs[dy + kRadius][dx + kRadius] = avg;
            if (avg < best_cost) {
                best_cost = avg;
                best_ix = dx;
                best_iy = dy;
            }
        }
    }

    auto parabolic = [](float left, float center, float right) -> float {
        const float denom = 2.0f * (left - 2.0f * center + right);
        if (std::fabs(denom) < 1e-7f) return 0.0f;
        return std::clamp((left - right) / denom, -0.5f, 0.5f);
    };

    float sub_x = 0.0f;
    if (best_ix > -kRadius && best_ix < kRadius) {
        sub_x = parabolic(costs[best_iy + kRadius][best_ix + kRadius - 1],
                          costs[best_iy + kRadius][best_ix + kRadius],
                          costs[best_iy + kRadius][best_ix + kRadius + 1]);
    }
    float sub_y = 0.0f;
    if (best_iy > -kRadius && best_iy < kRadius) {
        sub_y = parabolic(costs[best_iy + kRadius - 1][best_ix + kRadius],
                          costs[best_iy + kRadius][best_ix + kRadius],
                          costs[best_iy + kRadius + 1][best_ix + kRadius]);
    }

    out_dx = static_cast<float>(best_ix) + sub_x;
    out_dy = static_cast<float>(best_iy) + sub_y;
}

}  // namespace

bool DrizzleSuperRes::executeSubPixelDrizzle(const RawFrame* s5k3k1Burst,
                                            size_t count,
                                            float scaleFactor,
                                            std::vector<float>& out_bayer,
                                            int& out_width,
                                            int& out_height) {
    if (!s5k3k1Burst || count == 0 || scaleFactor < 1.0f || scaleFactor > 4.0f) {
        return false;
    }
    const RawFrame& ref = s5k3k1Burst[0];
    const int in_w = ref.meta.width;
    const int in_h = ref.meta.height;
    if (in_w < 4 || in_h < 4 || !ref.normalized_bayer) return false;
    if (ref.pixel_count < static_cast<size_t>(in_w) * in_h) return false;

    out_width = std::max(in_w, static_cast<int>(std::round(in_w * scaleFactor)));
    out_height = std::max(in_h, static_cast<int>(std::round(in_h * scaleFactor)));
    // Keep even dimensions for valid Bayer CFA topology
    out_width &= ~1;
    out_height &= ~1;

    const size_t out_px = static_cast<size_t>(out_width) * out_height;
    out_bayer.assign(out_px, 0.0f);
    std::vector<float> weights(out_px, 0.0f);

    const float inv_scale_x = static_cast<float>(in_w) / static_cast<float>(out_width);
    const float inv_scale_y = static_cast<float>(in_h) / static_cast<float>(out_height);
    const float sigma_drop = 0.75f;

    for (size_t k = 0; k < count; ++k) {
        const RawFrame& frm = s5k3k1Burst[k];
        if (!frm.normalized_bayer || frm.meta.width != in_w || frm.meta.height != in_h ||
            frm.pixel_count < static_cast<size_t>(in_w) * in_h) {
            continue;
        }

        float dx = 0.0f, dy = 0.0f;
        if (k > 0) {
            estimateSubPixelShift(ref.normalized_bayer, frm.normalized_bayer, in_w, in_h, dx, dy);
            // Snap to even CFA phase offset plus sub-pixel fractional tremor
            dx = std::round(dx * 0.5f) * 2.0f + (dx - std::round(dx));
            dy = std::round(dy * 0.5f) * 2.0f + (dy - std::round(dy));
        }

        for (int oy = 0; oy < out_height; ++oy) {
            const float sy = oy * inv_scale_y + dy;
            const int iy = static_cast<int>(std::round(sy));
            if (iy < 0 || iy >= in_h) continue;
            // Match CFA parity from nearest same-phase input coordinate
            const int cfa_y = ((iy & ~1) | (oy & 1));
            const int safe_iy = std::clamp(cfa_y, 0, in_h - 1);

            for (int ox = 0; ox < out_width; ++ox) {
                const float sx = ox * inv_scale_x + dx;
                const int ix = static_cast<int>(std::round(sx));
                if (ix < 0 || ix >= in_w) continue;
                const int cfa_x = ((ix & ~1) | (ox & 1));
                const int safe_ix = std::clamp(cfa_x, 0, in_w - 1);

                const float dist_x = sx - static_cast<float>(safe_ix);
                const float dist_y = sy - static_cast<float>(safe_iy);
                const float w = std::exp(-(dist_x * dist_x + dist_y * dist_y) / (2.0f * sigma_drop * sigma_drop));

                const size_t o_idx = static_cast<size_t>(oy) * out_width + ox;
                out_bayer[o_idx] += w * frm.normalized_bayer[static_cast<size_t>(safe_iy) * in_w + safe_ix];
                weights[o_idx] += w;
            }
        }
    }

    for (size_t i = 0; i < out_px; ++i) {
        if (weights[i] > 1e-6f) {
            out_bayer[i] /= weights[i];
        } else {
            // Fallback bilinear lookup from reference frame
            const int oy = static_cast<int>(i / out_width);
            const int ox = static_cast<int>(i % out_width);
            const int iy = std::clamp(static_cast<int>(oy * inv_scale_y), 0, in_h - 1);
            const int ix = std::clamp(static_cast<int>(ox * inv_scale_x), 0, in_w - 1);
            out_bayer[i] = ref.normalized_bayer[static_cast<size_t>(iy) * in_w + ix];
        }
    }

    return true;
}

bool DrizzleSuperRes::executeRgbaDrizzle(const std::vector<const uint8_t*>& frames,
                                         int in_width,
                                         int in_height,
                                         float scaleFactor,
                                         std::vector<uint8_t>& out_rgba,
                                         int& out_width,
                                         int& out_height) {
    if (frames.empty() || !frames[0] || in_width < 4 || in_height < 4 ||
        scaleFactor < 1.0f || scaleFactor > 4.0f) {
        return false;
    }

    out_width = std::max(in_width, static_cast<int>(std::round(in_width * scaleFactor)));
    out_height = std::max(in_height, static_cast<int>(std::round(in_height * scaleFactor)));

    const size_t in_px = static_cast<size_t>(in_width) * in_height;
    const size_t out_px = static_cast<size_t>(out_width) * out_height;

    std::vector<float> ref_luma(in_px, 0.0f);
    for (size_t i = 0; i < in_px; ++i) {
        ref_luma[i] = (0.299f * frames[0][i * 4 + 0] +
                       0.587f * frames[0][i * 4 + 1] +
                       0.114f * frames[0][i * 4 + 2]) / 255.0f;
    }

    std::vector<float> accum(out_px * 3, 0.0f);
    std::vector<float> weights(out_px, 0.0f);
    const float inv_sx = static_cast<float>(in_width - 1) / std::max(1, out_width - 1);
    const float inv_sy = static_cast<float>(in_height - 1) / std::max(1, out_height - 1);

    std::vector<float> mov_luma(in_px, 0.0f);
    for (size_t k = 0; k < frames.size(); ++k) {
        const uint8_t* frm = frames[k];
        if (!frm) continue;

        float dx = 0.0f, dy = 0.0f;
        if (k > 0) {
            for (size_t i = 0; i < in_px; ++i) {
                mov_luma[i] = (0.299f * frm[i * 4 + 0] +
                               0.587f * frm[i * 4 + 1] +
                               0.114f * frm[i * 4 + 2]) / 255.0f;
            }
            estimateSubPixelShift(ref_luma.data(), mov_luma.data(), in_width, in_height, dx, dy);
        }

        for (int oy = 0; oy < out_height; ++oy) {
            const float sy = std::clamp(oy * inv_sy + dy, 0.0f, static_cast<float>(in_height - 1));
            const int y0 = static_cast<int>(std::floor(sy));
            const int y1 = std::min(y0 + 1, in_height - 1);
            const float fy = sy - y0;

            for (int ox = 0; ox < out_width; ++ox) {
                const float sx = std::clamp(ox * inv_sx + dx, 0.0f, static_cast<float>(in_width - 1));
                const int x0 = static_cast<int>(std::floor(sx));
                const int x1 = std::min(x0 + 1, in_width - 1);
                const float fx = sx - x0;

                const size_t i00 = (static_cast<size_t>(y0) * in_width + x0) * 4;
                const size_t i10 = (static_cast<size_t>(y0) * in_width + x1) * 4;
                const size_t i01 = (static_cast<size_t>(y1) * in_width + x0) * 4;
                const size_t i11 = (static_cast<size_t>(y1) * in_width + x1) * 4;

                const size_t o_idx = static_cast<size_t>(oy) * out_width + ox;
                for (int c = 0; c < 3; ++c) {
                    const float val = (frm[i00 + c] * (1.0f - fx) + frm[i10 + c] * fx) * (1.0f - fy) +
                                      (frm[i01 + c] * (1.0f - fx) + frm[i11 + c] * fx) * fy;
                    accum[o_idx * 3 + c] += val;
                }
                weights[o_idx] += 1.0f;
            }
        }
    }

    out_rgba.resize(out_px * 4);
    for (size_t i = 0; i < out_px; ++i) {
        const float w = std::max(1.0f, weights[i]);
        out_rgba[i * 4 + 0] = static_cast<uint8_t>(std::clamp(std::round(accum[i * 3 + 0] / w), 0.0f, 255.0f));
        out_rgba[i * 4 + 1] = static_cast<uint8_t>(std::clamp(std::round(accum[i * 3 + 1] / w), 0.0f, 255.0f));
        out_rgba[i * 4 + 2] = static_cast<uint8_t>(std::clamp(std::round(accum[i * 3 + 2] / w), 0.0f, 255.0f));
        out_rgba[i * 4 + 3] = 255;
    }

    return true;
}

bool DrizzleSuperRes::cropCenter50MpWindow(const uint16_t* full8192x6144,
                                           size_t full_pixel_count,
                                           uint16_t* out4096x3072,
                                           size_t out_pixel_count) {
    constexpr int kFullW = 8192;
    constexpr int kFullH = 6144;
    constexpr int kCropW = 4096;
    constexpr int kCropH = 3072;
    constexpr size_t kFullPixels = static_cast<size_t>(kFullW) * kFullH;
    constexpr size_t kCropPixels = static_cast<size_t>(kCropW) * kCropH;
    constexpr int kStartX = (kFullW - kCropW) / 2; // 2048 (even Bayer boundary)
    constexpr int kStartY = (kFullH - kCropH) / 2; // 1536 (even Bayer boundary)

    if (!full8192x6144 || !out4096x3072 ||
        full_pixel_count < kFullPixels || out_pixel_count < kCropPixels) {
        return false;
    }

    for (int y = 0; y < kCropH; ++y) {
        const uint16_t* src_row = full8192x6144 + static_cast<size_t>(kStartY + y) * kFullW + kStartX;
        uint16_t* dst_row = out4096x3072 + static_cast<size_t>(y) * kCropW;
        std::copy(src_row, src_row + kCropW, dst_row);
    }
    return true;
}

}  // namespace gxv
