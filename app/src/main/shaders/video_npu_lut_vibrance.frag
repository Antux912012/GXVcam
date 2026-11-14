#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
out vec4 fragColor;

uniform samplerExternalOES uCameraTex;
uniform sampler3D uLut0; // Natural / Vivo Natural
uniform sampler3D uLut1; // Lumo Film
uniform sampler3D uLut2; // Moto Pantone Cinema
uniform sampler3D uLut3; // ACES Log Neutral

uniform vec4 uLutWeights;        // Normalized 4-way LUT blend weights
uniform vec3 uUnifiedRggbGains;  // Cross-lens AWB R, G, B multipliers (EMA smoothed)
uniform float uTransitionBlend;  // 0.0..1.0 12-frame lens handoff dissolve
uniform float uLutMix;           // 0.0..1.0 global LUT mix strength
uniform float uVibrance;         // -0.5..+0.6 skin-protected ICtCp-style vibrance
uniform float uLogCurveEnabled;  // 1.0 = GXV-Log (ACEScct-style), 0.0 = Rec.709 / HLG display

vec3 srgbToLinear(vec3 c) {
    vec3 cl = clamp(c, vec3(0.0), vec3(1.0));
    vec3 lo = cl / 12.92;
    vec3 hi = pow((cl + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), cl));
}

vec3 linearToSrgb(vec3 c) {
    vec3 cl = clamp(c, vec3(0.0), vec3(1.0));
    vec3 lo = 12.92 * cl;
    vec3 hi = 1.055 * pow(cl, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), cl));
}

// ACEScct-inspired logarithmic curve for GXV-Log grading headroom
vec3 linearToGxvLog(vec3 lin) {
    const float cut = 0.0078125;
    const float a = 10.5402377416545;
    const float b = 0.0729055341958355;
    vec3 x = max(lin, vec3(0.0));
    vec3 logPart = (log2(x) + 9.72) / 17.52;
    vec3 linPart = a * x + b;
    return clamp(mix(linPart, logPart, step(vec3(cut), x)), vec3(0.0), vec3(1.0));
}

// Opponent-chroma skin-hue protection + vibrance in perceptual luma-chroma space
vec3 applySkinProtectedVibrance(vec3 rgb, float vibrance) {
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 chroma = rgb - vec3(luma);

    // Detect warm skin-tone hue sector (red-orange dominance over blue)
    float rgDiff = rgb.r - rgb.g;
    float gbDiff = rgb.g - rgb.b;
    float skinMask = clamp(1.0 - abs((rgDiff - 0.14) * 3.5), 0.0, 1.0) *
                     clamp(gbDiff * 4.0, 0.0, 1.0);

    float sat = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);
    float boost = vibrance * (1.0 - sat) * (1.0 - 0.82 * skinMask);
    return clamp(vec3(luma) + chroma * (1.0 + boost), vec3(0.0), vec3(1.0));
}

void main() {
    vec3 src = texture(uCameraTex, vTexCoord).rgb;

    // 1. Apply cross-lens EMA-smoothed white balance lock
    vec3 lin = srgbToLinear(src) * max(uUnifiedRggbGains, vec3(0.25));
    // Soft roll-off during 12-frame lens handoff cross-dissolve
    float dissolveGain = mix(0.96, 1.0, clamp(uTransitionBlend, 0.0, 1.0));
    lin *= dissolveGain;

    vec3 gradedInput = linearToSrgb(lin);

    // 2. NPU 4-way 33^3 3D-LUT blending
    vec3 coord = clamp(gradedInput, vec3(0.0), vec3(1.0));
    vec3 lutColor =
        texture(uLut0, coord).rgb * uLutWeights.x +
        texture(uLut1, coord).rgb * uLutWeights.y +
        texture(uLut2, coord).rgb * uLutWeights.z +
        texture(uLut3, coord).rgb * uLutWeights.w;

    vec3 blended = mix(gradedInput, lutColor, clamp(uLutMix, 0.0, 1.0));

    // 3. Optional GXV-Log (ACEScct-style) curve vs display vibrance
    vec3 finalRgb;
    if (uLogCurveEnabled > 0.5) {
        finalRgb = linearToGxvLog(srgbToLinear(blended));
    } else {
        finalRgb = applySkinProtectedVibrance(blended, uVibrance);
    }

    fragColor = vec4(clamp(finalRgb, vec3(0.0), vec3(1.0)), 1.0);
}
