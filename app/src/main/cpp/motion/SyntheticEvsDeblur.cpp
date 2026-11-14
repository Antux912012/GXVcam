#include "signature27_engine.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace gxv {

bool SyntheticEvsDeblur::integrateGyroPsf(const GyroSample* samples,
                                         size_t n,
                                         const float intrinsics[5],
                                         float* outKernel) {
    if (!outKernel || !intrinsics) return false;

    std::fill(outKernel, outKernel + kKernelElements, 0.0f);
    const int half = kKernelSize / 2;

    if (!samples || n == 0 || intrinsics[0] <= 0.0f || intrinsics[1] <= 0.0f) {
        // Identity Dirac delta PSF when no gyro motion is provided
        outKernel[half * kKernelSize + half] = 1.0f;
        return samples != nullptr && n == 0;
    }

    const float fx = intrinsics[0];
    const float fy = intrinsics[1];
    const float fallback_dt = std::clamp(intrinsics[4] / static_cast<float>(n), 1e-4f, 0.05f);

    // Integrate angular velocity trajectory into 2D pixel displacement curve
    std::vector<float> traj_x(n, 0.0f);
    std::vector<float> traj_y(n, 0.0f);
    float acc_x = 0.0f;
    float acc_y = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        float dt = fallback_dt;
        if (i > 0 && samples[i].timestamp_ns > samples[i - 1].timestamp_ns) {
            dt = static_cast<float>(samples[i].timestamp_ns - samples[i - 1].timestamp_ns) * 1e-9f;
            dt = std::clamp(dt, 1e-5f, 0.05f);
        }
        // Small-angle projection: dx_px = -wy * dt * fx, dy_px = wx * dt * fy
        acc_x += -samples[i].wy * dt * fx;
        acc_y += samples[i].wx * dt * fy;
        traj_x[i] = acc_x;
        traj_y[i] = acc_y;
    }

    // Center trajectory around its midpoint so PSF introduces zero global translation
    float mean_x = 0.0f, mean_y = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        mean_x += traj_x[i];
        mean_y += traj_y[i];
    }
    mean_x /= static_cast<float>(n);
    mean_y /= static_cast<float>(n);

    // Splat sub-pixel bilinear weights into the 9x9 PSF kernel
    float sum = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        const float px = std::clamp((traj_x[i] - mean_x) + static_cast<float>(half),
                                    0.0f, static_cast<float>(kKernelSize - 1));
        const float py = std::clamp((traj_y[i] - mean_y) + static_cast<float>(half),
                                    0.0f, static_cast<float>(kKernelSize - 1));
        const int x0 = static_cast<int>(std::floor(px));
        const int y0 = static_cast<int>(std::floor(py));
        const int x1 = std::min(x0 + 1, kKernelSize - 1);
        const int y1 = std::min(y0 + 1, kKernelSize - 1);
        const float wx = px - x0;
        const float wy = py - y0;

        const float w00 = (1.0f - wx) * (1.0f - wy);
        const float w10 = wx * (1.0f - wy);
        const float w01 = (1.0f - wx) * wy;
        const float w11 = wx * wy;

        outKernel[y0 * kKernelSize + x0] += w00;
        outKernel[y0 * kKernelSize + x1] += w10;
        outKernel[y1 * kKernelSize + x0] += w01;
        outKernel[y1 * kKernelSize + x1] += w11;
        sum += w00 + w10 + w01 + w11;
    }

    if (sum > 1e-6f) {
        for (int i = 0; i < kKernelElements; ++i) {
            outKernel[i] /= sum;
        }
    } else {
        outKernel[half * kKernelSize + half] = 1.0f;
    }
    return true;
}

bool SyntheticEvsDeblur::computeLogLumaEventMask(const float* ref_luma,
                                                 const float* action_luma,
                                                 int width,
                                                 int height,
                                                 float threshold,
                                                 std::vector<float>& out_mask) {
    if (!ref_luma || !action_luma || width <= 0 || height <= 0) return false;
    const size_t total = static_cast<size_t>(width) * height;
    out_mask.resize(total);
    const float eps = 1e-3f;
    const float thr = std::max(0.01f, threshold);

    for (size_t i = 0; i < total; ++i) {
        const float log_ref = std::log(std::max(0.0f, ref_luma[i]) + eps);
        const float log_act = std::log(std::max(0.0f, action_luma[i]) + eps);
        const float diff = std::fabs(log_ref - log_act);
        out_mask[i] = std::clamp((diff - thr) / (thr * 2.0f), 0.0f, 1.0f);
    }
    return true;
}

bool SyntheticEvsDeblur::deblurRgba8888WithPsf(uint8_t* rgba,
                                                size_t byte_size,
                                                int width,
                                                int height,
                                                const float* kernel,
                                                int kernel_size,
                                                float strength) {
    return deblurRgba8888WithPsfAndEvents(
        rgba, byte_size, width, height, kernel, kernel_size, nullptr, 0, 0, strength);
}

bool SyntheticEvsDeblur::deblurRgba8888WithPsfAndEvents(uint8_t* rgba,
                                                         size_t byte_size,
                                                         int width,
                                                         int height,
                                                         const float* kernel,
                                                         int kernel_size,
                                                         const int8_t* event_mask,
                                                         int mask_w,
                                                         int mask_h,
                                                         float strength) {
    if (!rgba || !kernel || width <= 0 || height <= 0 || kernel_size < 3 || (kernel_size % 2) == 0) {
        return false;
    }
    const size_t total_px = static_cast<size_t>(width) * height;
    if (byte_size < total_px * 4) return false;

    const int half = kernel_size / 2;
    const float s = std::clamp(strength, 0.0f, 1.0f);
    if (s <= 0.01f) return true;

    const bool use_event_mask = (event_mask != nullptr && mask_w > 0 && mask_h > 0);

    std::vector<float> luma(total_px, 0.0f);
    for (size_t i = 0; i < total_px; ++i) {
        luma[i] = (0.2126f * rgba[i * 4 + 0] +
                   0.7152f * rgba[i * 4 + 1] +
                   0.0722f * rgba[i * 4 + 2]) / 255.0f;
    }

    std::vector<float> estimate = luma;
    std::vector<float> blurred(total_px, 0.0f);

    // 2 damped Richardson-Lucy deconvolution iterations on luma
    for (int iter = 0; iter < 2; ++iter) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float sum = 0.0f;
                for (int ky = -half; ky <= half; ++ky) {
                    const int sy = std::clamp(y + ky, 0, height - 1);
                    for (int kx = -half; kx <= half; ++kx) {
                        const int sx = std::clamp(x + kx, 0, width - 1);
                        const float k_val = kernel[(ky + half) * kernel_size + (kx + half)];
                        sum += estimate[static_cast<size_t>(sy) * width + sx] * k_val;
                    }
                }
                blurred[static_cast<size_t>(y) * width + x] = std::max(1e-4f, sum);
            }
        }

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float corr = 0.0f;
                for (int ky = -half; ky <= half; ++ky) {
                    const int sy = std::clamp(y - ky, 0, height - 1);
                    for (int kx = -half; kx <= half; ++kx) {
                        const int sx = std::clamp(x - kx, 0, width - 1);
                        const float k_val = kernel[(ky + half) * kernel_size + (kx + half)];
                        const size_t s_idx = static_cast<size_t>(sy) * width + sx;
                        corr += (luma[s_idx] / blurred[s_idx]) * k_val;
                    }
                }
                const size_t idx = static_cast<size_t>(y) * width + x;
                // Ringing clamp around original luma
                const float updated = estimate[idx] * std::clamp(corr, 0.75f, 1.35f);
                estimate[idx] = std::clamp(updated, 0.0f, 1.0f);
            }
        }
    }

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t i = static_cast<size_t>(y) * width + x;
            float event_weight = 1.0f;
            if (use_event_mask) {
                const float fx = (static_cast<float>(x) + 0.5f) * static_cast<float>(mask_w) / static_cast<float>(width) - 0.5f;
                const float fy = (static_cast<float>(y) + 0.5f) * static_cast<float>(mask_h) / static_cast<float>(height) - 0.5f;
                const int x0 = std::clamp(static_cast<int>(std::floor(fx)), 0, mask_w - 1);
                const int y0 = std::clamp(static_cast<int>(std::floor(fy)), 0, mask_h - 1);
                const int x1 = std::min(x0 + 1, mask_w - 1);
                const int y1 = std::min(y0 + 1, mask_h - 1);
                const float wx = std::clamp(fx - static_cast<float>(x0), 0.0f, 1.0f);
                const float wy = std::clamp(fy - static_cast<float>(y0), 0.0f, 1.0f);

                const float m00 = static_cast<float>(std::abs(static_cast<int>(event_mask[static_cast<size_t>(y0) * mask_w + x0])));
                const float m10 = static_cast<float>(std::abs(static_cast<int>(event_mask[static_cast<size_t>(y0) * mask_w + x1])));
                const float m01 = static_cast<float>(std::abs(static_cast<int>(event_mask[static_cast<size_t>(y1) * mask_w + x0])));
                const float m11 = static_cast<float>(std::abs(static_cast<int>(event_mask[static_cast<size_t>(y1) * mask_w + x1])));
                const float mag = (m00 * (1.0f - wx) * (1.0f - wy)) +
                                  (m10 * wx * (1.0f - wy)) +
                                  (m01 * (1.0f - wx) * wy) +
                                  (m11 * wx * wy);
                event_weight = std::clamp((mag - 16.0f) / 64.0f, 0.0f, 1.0f);
                if (event_weight <= 0.001f) continue;
            }

            const float delta = (estimate[i] - luma[i]) * (s * event_weight) * 255.0f;
            const float clamped_delta = std::clamp(delta, -28.0f, 28.0f);
            for (int c = 0; c < 3; ++c) {
                const float v = static_cast<float>(rgba[i * 4 + c]) + clamped_delta;
                rgba[i * 4 + c] = static_cast<uint8_t>(std::clamp(std::round(v), 0.0f, 255.0f));
            }
        }
    }
    return true;
}

}  // namespace gxv
