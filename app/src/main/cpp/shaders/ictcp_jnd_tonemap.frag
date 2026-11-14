#version 450
// Vulkan GLSL fragment shader for Signature 27 ICtCp JND Tone Mapping + Pantone 3D-LUT.
// Matches gxv::IctcpToneMapper CPU reference implementation.

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uLinearRGB;
layout(binding = 3) uniform sampler3D uPantoneLUT;

layout(push_constant) uniform TonePC {
    float uShadowLift;
    float uDetailGain;
    float uHighlightKnee;
    float uLutMix;
} pc;

const float PQ_M1 = 2610.0 / 16384.0;
const float PQ_M2 = (2523.0 / 4096.0) * 128.0;
const float PQ_C1 = 3424.0 / 4096.0;
const float PQ_C2 = (2413.0 / 4096.0) * 32.0;
const float PQ_C3 = (2392.0 / 4096.0) * 32.0;

vec3 linearToPQ(vec3 L) {
    vec3 y = max(L, vec3(0.0));
    vec3 ym1 = pow(y, vec3(PQ_M1));
    return pow((vec3(PQ_C1) + PQ_C2 * ym1) / (vec3(1.0) + PQ_C3 * ym1), vec3(PQ_M2));
}

vec3 pqToLinear(vec3 P) {
    vec3 p = clamp(P, vec3(0.0), vec3(1.0));
    vec3 pm2 = pow(p, vec3(1.0 / PQ_M2));
    vec3 num = max(vec3(0.0), pm2 - vec3(PQ_C1));
    vec3 den = max(vec3(1e-6), vec3(PQ_C2) - PQ_C3 * pm2);
    return pow(num / den, vec3(1.0 / PQ_M1));
}

vec3 linearToSrgb(vec3 c) {
    vec3 cl = clamp(c, vec3(0.0), vec3(1.0));
    vec3 lo = 12.92 * cl;
    vec3 hi = 1.055 * pow(cl, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), cl));
}

void main() {
    vec3 lin = max(texture(uLinearRGB, vTexCoord).rgb, vec3(0.0));

    // Rec.2100 Linear RGB -> LMS -> PQ -> ICtCp
    float L = 0.412109 * lin.r + 0.523925 * lin.g + 0.063965 * lin.b;
    float M = 0.166748 * lin.r + 0.720459 * lin.g + 0.112793 * lin.b;
    float S = 0.024170 * lin.r + 0.075440 * lin.g + 0.900390 * lin.b;

    vec3 pq = linearToPQ(vec3(L, M, S) * 0.01);
    float I = 0.5 * (pq.x + pq.y);
    float Ct = 1.613769 * pq.x - 3.323486 * pq.y + 1.709717 * pq.z;
    float Cp = 4.378174 * pq.x - 4.245605 * pq.y - 0.132568 * pq.z;

    // JND Shadow lift in PQ intensity space
    float iNorm = clamp(I * 4.0, 0.0, 1.0);
    float lift = pc.uShadowLift * (1.0 - iNorm) * (1.0 - iNorm);
    I = clamp(I + lift * 0.08, 0.0, 1.0);

    // Invert ICtCp -> PQ LMS -> Linear RGB
    float lPq = clamp(I + 0.008609 * Ct + 0.111030 * Cp, 0.0, 1.0);
    float mPq = clamp(I - 0.008609 * Ct - 0.111030 * Cp, 0.0, 1.0);
    float sPq = clamp(I + 0.560031 * Ct - 0.320627 * Cp, 0.0, 1.0);
    vec3 lmsLin = pqToLinear(vec3(lPq, mPq, sPq)) * 100.0;

    vec3 toneMapped;
    toneMapped.r = 3.43661 * lmsLin.x - 2.50645 * lmsLin.y + 0.06984 * lmsLin.z;
    toneMapped.g = -0.79133 * lmsLin.x + 1.98360 * lmsLin.y - 0.19227 * lmsLin.z;
    toneMapped.b = -0.01968 * lmsLin.x - 0.09912 * lmsLin.y + 1.11880 * lmsLin.z;

    // Smooth highlight knee roll-off
    float knee = clamp(pc.uHighlightKnee, 0.35, 0.95);
    vec3 over = max(toneMapped - vec3(knee), vec3(0.0));
    vec3 shouldered = mix(
        toneMapped,
        vec3(knee) + (1.0 - knee) * tanh(over / max(1e-5, 1.0 - knee)),
        step(vec3(knee), toneMapped)
    );

    vec3 srgb = linearToSrgb(shouldered);
    vec3 lutGraded = texture(uPantoneLUT, clamp(srgb, vec3(0.0), vec3(1.0))).rgb;
    vec3 finalColor = mix(srgb, lutGraded, clamp(pc.uLutMix, 0.0, 1.0));

    // Simple unsharp detail gain via neighboring texel average
    vec2 texel = 1.0 / vec2(textureSize(uLinearRGB, 0));
    vec3 nAvg = 0.25 * (
        texture(uLinearRGB, vTexCoord + vec2(texel.x, 0.0)).rgb +
        texture(uLinearRGB, vTexCoord - vec2(texel.x, 0.0)).rgb +
        texture(uLinearRGB, vTexCoord + vec2(0.0, texel.y)).rgb +
        texture(uLinearRGB, vTexCoord - vec2(0.0, texel.y)).rgb
    );
    float lumaDiff = dot(lin - nAvg, vec3(0.2126, 0.7152, 0.0722));
    finalColor = clamp(finalColor + vec3(lumaDiff * (pc.uDetailGain - 1.0)), vec3(0.0), vec3(1.0));

    fragColor = vec4(finalColor, 1.0);
}
