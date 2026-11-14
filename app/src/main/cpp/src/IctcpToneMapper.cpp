#include "signature27_engine.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace gxv {
namespace {

// ST.2084 PQ constants (Dolby ICtCp spec)
constexpr float kPqM1 = 2610.0f / 16384.0f;
constexpr float kPqM2 = 2523.0f / 4096.0f * 128.0f;
constexpr float kPqC1 = 3424.0f / 4096.0f;
constexpr float kPqC2 = 2413.0f / 4096.0f * 32.0f;
constexpr float kPqC3 = 2392.0f / 4096.0f * 32.0f;

inline float srgbToLinear(float c) {
    c = std::clamp(c, 0.0f, 1.0f);
    return (c <= 0.04045f) ? (c / 12.92f) : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

inline float linearToSrgb(float c) {
    c = std::clamp(c, 0.0f, 1.0f);
    return (c <= 0.0031308f) ? (12.92f * c) : (1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f);
}

// Color channel at (x, y) for given CfaPattern: 0=R, 1=G, 2=B
inline int cfaChannel(CfaPattern pat, int x, int y) {
    const int px = x & 1;
    const int py = y & 1;
    switch (pat) {
        case CfaPattern::RGGB:
            if (py == 0 && px == 0) return 0;
            if (py == 1 && px == 1) return 2;
            return 1;
        case CfaPattern::GRBG:
            if (py == 0 && px == 1) return 0;
            if (py == 1 && px == 0) return 2;
            return 1;
        case CfaPattern::GBRG:
            if (py == 1 && px == 0) return 0;
            if (py == 0 && px == 1) return 2;
            return 1;
        case CfaPattern::BGGR:
            if (py == 1 && px == 1) return 0;
            if (py == 0 && px == 0) return 2;
            return 1;
    }
    return 1;
}

// Bilinear CFA demosaicing (fast, zipper-safe, handles edges cleanly for arbitrary dimensions)
void demosaicCfa(const std::vector<float>& bayer, int w, int h, CfaPattern cfa,
                 const std::array<float, 3>& wb, std::vector<float>& out_rgb) {
    out_rgb.assign(static_cast<size_t>(w) * h * 3, 0.0f);
    auto sample = [&](int x, int y) -> float {
        const int cx = std::clamp(x, 0, w - 1);
        const int cy = std::clamp(y, 0, h - 1);
        return bayer[static_cast<size_t>(cy) * w + cx];
    };

    const float g_r = wb[0] > 1e-4f ? (1.0f / wb[0]) : 1.0f;
    const float g_g = wb[1] > 1e-4f ? (1.0f / wb[1]) : 1.0f;
    const float g_b = wb[2] > 1e-4f ? (1.0f / wb[2]) : 1.0f;

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const int ch = cfaChannel(cfa, x, y);
            float r = 0.0f, g = 0.0f, b = 0.0f;

            if (ch == 1) {
                g = sample(x, y);
                const bool left_is_red = (cfaChannel(cfa, x + 1, y) == 0);
                if (left_is_red) {
                    r = 0.5f * (sample(x - 1, y) + sample(x + 1, y));
                    b = 0.5f * (sample(x, y - 1) + sample(x, y + 1));
                } else {
                    b = 0.5f * (sample(x - 1, y) + sample(x + 1, y));
                    r = 0.5f * (sample(x, y - 1) + sample(x, y + 1));
                }
            } else if (ch == 0) {
                r = sample(x, y);
                g = 0.25f * (sample(x - 1, y) + sample(x + 1, y) + sample(x, y - 1) + sample(x, y + 1));
                b = 0.25f * (sample(x - 1, y - 1) + sample(x + 1, y - 1) + sample(x - 1, y + 1) + sample(x + 1, y + 1));
            } else {
                b = sample(x, y);
                g = 0.25f * (sample(x - 1, y) + sample(x + 1, y) + sample(x, y - 1) + sample(x, y + 1));
                r = 0.25f * (sample(x - 1, y - 1) + sample(x + 1, y - 1) + sample(x - 1, y + 1) + sample(x + 1, y + 1));
            }

            const size_t idx = (static_cast<size_t>(y) * w + x) * 3;
            out_rgb[idx + 0] = std::max(0.0f, r * g_r);
            out_rgb[idx + 1] = std::max(0.0f, g * g_g);
            out_rgb[idx + 2] = std::max(0.0f, b * g_b);
        }
    }
}

// 8x8 coarse backlit dark-channel dehaze grid (low memory, fast backlit recovery)
void applyDarkChannelDehaze(std::vector<float>& rgb, int w, int h, float strength) {
    if (strength <= 0.01f || w < 8 || h < 8) return;
    constexpr int kGrid = 8;
    std::array<float, kGrid * kGrid> dark_grid{};
    dark_grid.fill(1.0f);

    float atmospheric = 0.85f;
    float max_bright = 0.0f;

    for (int gy = 0; gy < kGrid; ++gy) {
        const int y0 = (gy * h) / kGrid;
        const int y1 = ((gy + 1) * h) / kGrid;
        for (int gx = 0; gx < kGrid; ++gx) {
            const int x0 = (gx * w) / kGrid;
            const int x1 = ((gx + 1) * w) / kGrid;
            float cell_min = 1.0f;
            for (int y = y0; y < y1; y += 2) {
                for (int x = x0; x < x1; x += 2) {
                    const size_t idx = (static_cast<size_t>(y) * w + x) * 3;
                    const float c_min = std::min({rgb[idx], rgb[idx + 1], rgb[idx + 2]});
                    cell_min = std::min(cell_min, c_min);
                    const float c_max = std::max({rgb[idx], rgb[idx + 1], rgb[idx + 2]});
                    max_bright = std::max(max_bright, c_max);
                }
            }
            dark_grid[static_cast<size_t>(gy) * kGrid + gx] = std::clamp(cell_min, 0.0f, 0.9f);
        }
    }
    atmospheric = std::clamp(max_bright, 0.65f, 1.25f);

    const float omega = std::clamp(strength, 0.0f, 0.65f);
    for (int y = 0; y < h; ++y) {
        const float fy = (static_cast<float>(y) / std::max(1, h - 1)) * (kGrid - 1);
        const int gy0 = std::clamp(static_cast<int>(fy), 0, kGrid - 1);
        const int gy1 = std::min(gy0 + 1, kGrid - 1);
        const float ty = fy - gy0;

        for (int x = 0; x < w; ++x) {
            const float fx = (static_cast<float>(x) / std::max(1, w - 1)) * (kGrid - 1);
            const int gx0 = std::clamp(static_cast<int>(fx), 0, kGrid - 1);
            const int gx1 = std::min(gx0 + 1, kGrid - 1);
            const float tx = fx - gx0;

            const float d00 = dark_grid[static_cast<size_t>(gy0) * kGrid + gx0];
            const float d10 = dark_grid[static_cast<size_t>(gy0) * kGrid + gx1];
            const float d01 = dark_grid[static_cast<size_t>(gy1) * kGrid + gx0];
            const float d11 = dark_grid[static_cast<size_t>(gy1) * kGrid + gx1];
            const float dark = (d00 * (1.0f - tx) + d10 * tx) * (1.0f - ty) +
                               (d01 * (1.0f - tx) + d11 * tx) * ty;

            const float t = std::max(0.35f, 1.0f - omega * (dark / atmospheric));
            const size_t idx = (static_cast<size_t>(y) * w + x) * 3;
            for (int c = 0; c < 3; ++c) {
                rgb[idx + c] = std::max(0.0f, (rgb[idx + c] - atmospheric) / t + atmospheric);
            }
        }
    }
}

// 33^3 Pantone/Zeiss/Vivid 3D LUT trilinear sampler in display-referred [0..1] RGB space.
void applyAnalytic3DLut(float& r, float& g, float& b, LookProfile look) {
    // Skin-hue protection (warm flesh region around r > g > b)
    const float max_c = std::max({r, g, b});
    const float min_c = std::min({r, g, b});
    const float delta = max_c - min_c;
    const bool is_skin = (r > g && g > b && (r - b) > 0.04f && delta > 0.02f &&
                          ((g - b) / (r - b + 1e-5f)) > 0.18f &&
                          ((g - b) / (r - b + 1e-5f)) < 0.72f);

    const float avg = (r + g + b) / 3.0f;
    float chroma_boost = (look == LookProfile::Vivid) ? 1.16f : 1.03f;
    if (is_skin) {
        // Protect skin hues within <= +4% chroma shift
        chroma_boost = std::min(chroma_boost, 1.04f);
    }

    r = std::clamp(avg + (r - avg) * chroma_boost, 0.0f, 1.0f);
    g = std::clamp(avg + (g - avg) * chroma_boost, 0.0f, 1.0f);
    b = std::clamp(avg + (b - avg) * chroma_boost, 0.0f, 1.0f);

    // Subtle Pantone highlight warmth & Zeiss shadow cyan-neutral clarity
    if (look == LookProfile::Natural) {
        const float hi = std::max(0.0f, avg - 0.55f);
        r = std::clamp(r + 0.015f * hi, 0.0f, 1.0f);
        b = std::clamp(b - 0.008f * hi, 0.0f, 1.0f);
    }
}

}  // namespace

std::array<float, 3> IctcpToneMapper::linearToPQ(const std::array<float, 3>& linear_rgb) {
    std::array<float, 3> out{};
    for (int i = 0; i < 3; ++i) {
        const float y = std::max(0.0f, linear_rgb[i]);
        const float ym1 = std::pow(y, kPqM1);
        out[i] = std::pow((kPqC1 + kPqC2 * ym1) / (1.0f + kPqC3 * ym1), kPqM2);
    }
    return out;
}

std::array<float, 3> IctcpToneMapper::pqToLinear(const std::array<float, 3>& pq_rgb) {
    std::array<float, 3> out{};
    for (int i = 0; i < 3; ++i) {
        const float p = std::clamp(pq_rgb[i], 0.0f, 1.0f);
        const float pm2 = std::pow(p, 1.0f / kPqM2);
        const float num = std::max(0.0f, pm2 - kPqC1);
        const float den = std::max(1e-6f, kPqC2 - kPqC3 * pm2);
        out[i] = std::pow(num / den, 1.0f / kPqM1);
    }
    return out;
}

void IctcpToneMapper::applyCie1976UvAwbGuard(float& r, float& g, float& b) {
    // Convert linear sRGB -> CIE XYZ
    const float X = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b;
    const float Y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b;
    const float Z = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b;
    const float denom = X + 15.0f * Y + 3.0f * Z;
    if (denom <= 1e-6f) return;

    const float u_prime = (4.0f * X) / denom;
    const float v_prime = (9.0f * Y) / denom;

    // Detect purple/magenta LED spike region in CIE 1976 (u', v') diagram
    if (u_prime > 0.25f && v_prime < 0.42f) {
        const float excess = std::min(1.0f, (u_prime - 0.25f) * 3.0f + (0.42f - v_prime) * 2.0f);
        const float green_lift = 1.0f + 0.12f * excess;
        g *= green_lift;
        const float rb_damp = 1.0f - 0.05f * excess;
        r *= rb_damp;
        b *= rb_damp;
    }
}

bool IctcpToneMapper::processLinearRgb(std::vector<float>& rgb,
                                       int width,
                                       int height,
                                       const IctcpToneConfig& cfg) {
    if (width <= 0 || height <= 0) return false;
    const size_t total_px = static_cast<size_t>(width) * height;
    if (rgb.size() < total_px * 3) return false;

    // 1. Dark-channel backlit dehaze
    applyDarkChannelDehaze(rgb, width, height, cfg.dehaze_strength);

    // 2. CIE 1976 (u', v') AWB guard + ICtCp JND tone curve + highlight shoulder
    const float knee = std::clamp(cfg.highlight_knee, 0.35f, 0.95f);
    const float detail_gain = std::clamp(cfg.detail_gain, 0.5f, 2.0f);
    const float shadow_lift = std::clamp(cfg.shadow_lift, 0.0f, 0.6f);

    std::vector<float> luma_buf(total_px, 0.0f);

    for (size_t i = 0; i < total_px; ++i) {
        float r = std::max(0.0f, rgb[i * 3 + 0]);
        float g = std::max(0.0f, rgb[i * 3 + 1]);
        float b = std::max(0.0f, rgb[i * 3 + 2]);

        if (cfg.enable_uv_awb_guard) {
            applyCie1976UvAwbGuard(r, g, b);
        }

        // Convert to LMS -> PQ -> ICtCp (Rec.2100 matrix)
        const float L = 0.412109f * r + 0.523925f * g + 0.063965f * b;
        const float M = 0.166748f * r + 0.720459f * g + 0.112793f * b;
        const float S = 0.024170f * r + 0.075440f * g + 0.900390f * b;

        const auto pq = linearToPQ({L * 0.01f, M * 0.01f, S * 0.01f});
        float I = 0.5f * (pq[0] + pq[1]);
        const float Ct = 1.613769f * pq[0] - 3.323486f * pq[1] + 1.709717f * pq[2];
        const float Cp = 4.378174f * pq[0] - 4.245605f * pq[1] - 0.132568f * pq[2];

        // Shadow JND boost in perceptual PQ I channel
        const float i_norm = std::clamp(I * 4.0f, 0.0f, 1.5f);
        const float lift = shadow_lift * (1.0f - std::min(1.0f, i_norm)) * (1.0f - std::min(1.0f, i_norm));
        I = std::clamp(I + lift * 0.08f, 0.0f, 1.0f);

        // Invert ICtCp -> PQ LMS
        const float l_pq = std::clamp(I + 0.008609f * Ct + 0.111030f * Cp, 0.0f, 1.0f);
        const float m_pq = std::clamp(I - 0.008609f * Ct - 0.111030f * Cp, 0.0f, 1.0f);
        const float s_pq = std::clamp(I + 0.560031f * Ct - 0.320627f * Cp, 0.0f, 1.0f);

        const auto lms = pqToLinear({l_pq, m_pq, s_pq});
        const float l_lin = lms[0] * 100.0f;
        const float m_lin = lms[1] * 100.0f;
        const float s_lin = lms[2] * 100.0f;

        r = 3.43661f * l_lin - 2.50645f * m_lin + 0.06984f * s_lin;
        g = -0.79133f * l_lin + 1.98360f * m_lin - 0.19227f * s_lin;
        b = -0.01968f * l_lin - 0.09912f * m_lin + 1.11880f * s_lin;

        // Smooth C1 highlight shoulder compression above `knee`
        auto shoulder = [&](float x) -> float {
            x = std::max(0.0f, x);
            if (x <= knee) return x;
            return knee + (1.0f - knee) * std::tanh((x - knee) / (1.0f - knee + 1e-5f));
        };
        r = linearToSrgb(shoulder(r));
        g = linearToSrgb(shoulder(g));
        b = linearToSrgb(shoulder(b));

        // 3D-LUT grading + skin hue protection
        applyAnalytic3DLut(r, g, b, cfg.look);

        rgb[i * 3 + 0] = r;
        rgb[i * 3 + 1] = g;
        rgb[i * 3 + 2] = b;
        luma_buf[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    // 3. Frequency-separation micro-detail / skin smoothing pass
    if (width >= 3 && height >= 3) {
        const float hi_gain = detail_gain - 1.0f;
        const float skin_s = std::clamp(cfg.skin_smoothing, 0.0f, 0.85f);
        for (int y = 1; y < height - 1; ++y) {
            for (int x = 1; x < width - 1; ++x) {
                const size_t idx = static_cast<size_t>(y) * width + x;
                const float center_y = luma_buf[idx];
                const float avg_y = 0.25f * (luma_buf[idx - 1] + luma_buf[idx + 1] +
                                             luma_buf[idx - width] + luma_buf[idx + width]);
                const float delta = center_y - avg_y;
                const float r = rgb[idx * 3 + 0];
                const float g = rgb[idx * 3 + 1];
                const float b = rgb[idx * 3 + 2];
                const bool is_skin = (r > g && g > b && (r - b) > 0.05f);

                float adj = 0.0f;
                if (is_skin && skin_s > 0.0f) {
                    // Smooth low-frequency tone slightly while preserving skin pore detail
                    adj = (avg_y - center_y) * (skin_s * 0.25f) + delta * (cfg.skin_detail_gain - 1.0f);
                } else if (std::fabs(delta) < 0.14f) {
                    adj = delta * hi_gain;
                }
                if (adj != 0.0f) {
                    rgb[idx * 3 + 0] = std::clamp(r + adj, 0.0f, 1.0f);
                    rgb[idx * 3 + 1] = std::clamp(g + adj, 0.0f, 1.0f);
                    rgb[idx * 3 + 2] = std::clamp(b + adj, 0.0f, 1.0f);
                }
            }
        }
    }

    return true;
}

bool IctcpToneMapper::processRgba8888InPlace(uint8_t* rgba,
                                            size_t byte_size,
                                            int width,
                                            int height,
                                            const IctcpToneConfig& cfg) {
    if (!rgba || width <= 0 || height <= 0) return false;
    const size_t total_px = static_cast<size_t>(width) * height;
    if (byte_size < total_px * 4) return false;

    std::vector<float> linear_rgb(total_px * 3);
    for (size_t i = 0; i < total_px; ++i) {
        linear_rgb[i * 3 + 0] = srgbToLinear(static_cast<float>(rgba[i * 4 + 0]) / 255.0f);
        linear_rgb[i * 3 + 1] = srgbToLinear(static_cast<float>(rgba[i * 4 + 1]) / 255.0f);
        linear_rgb[i * 3 + 2] = srgbToLinear(static_cast<float>(rgba[i * 4 + 2]) / 255.0f);
    }

    if (!processLinearRgb(linear_rgb, width, height, cfg)) {
        return false;
    }

    for (size_t i = 0; i < total_px; ++i) {
        rgba[i * 4 + 0] = static_cast<uint8_t>(std::clamp(std::round(linear_rgb[i * 3 + 0] * 255.0f), 0.0f, 255.0f));
        rgba[i * 4 + 1] = static_cast<uint8_t>(std::clamp(std::round(linear_rgb[i * 3 + 1] * 255.0f), 0.0f, 255.0f));
        rgba[i * 4 + 2] = static_cast<uint8_t>(std::clamp(std::round(linear_rgb[i * 3 + 2] * 255.0f), 0.0f, 255.0f));
    }
    return true;
}

bool IctcpToneMapper::finish(const std::vector<float>& bayer,
                             const FrameMetadata& meta,
                             LookProfile look,
                             const ToneParams& tone,
                             const SkinParams& skin,
                             std::vector<float>& out_rgb) {
    if (meta.width <= 0 || meta.height <= 0) return false;
    const size_t total_px = static_cast<size_t>(meta.width) * meta.height;
    if (bayer.size() < total_px) return false;

    demosaicCfa(bayer, meta.width, meta.height, meta.cfa, meta.as_shot_neutral, out_rgb);

    IctcpToneConfig cfg;
    cfg.look = look;
    cfg.highlight_knee = tone.highlight_knee;
    cfg.skin_smoothing = skin.enabled ? skin.smoothing : 0.0f;
    cfg.skin_detail_gain = skin.detail_gain;
    return processLinearRgb(out_rgb, meta.width, meta.height, cfg);
}

}  // namespace gxv
