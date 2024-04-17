#version 460 core

#include "../ColorUtils.glsl"

//#define TONEMAP_FUNC(x) reinhard(x)
//#define TONEMAP_FUNC(x) reinhard2(x)
//#define TONEMAP_FUNC(x) aces(x)
//#define TONEMAP_FUNC(x) uchimura(x)
//#define TONEMAP_FUNC(x) uchimura2(x)
#define TONEMAP_FUNC(x) uchimura3(x)
//#define TONEMAP_FUNC(x) lottes(x)
//#define TONEMAP_FUNC(x) unreal(x)

layout(binding = 0) uniform sampler2D inputTexture;

in vec2 fTexCoordI;

out vec4 fragColor;

vec3 unreal(vec3 x) {
    // Unreal 3, Documentation: "Color Grading"
    // Adapted to be close to Tonemap_ACES, with similar range
    // Gamma 2.2 correction is baked in, don't use with sRGB conversion!
    return pow(x / (x + 0.155) * 1.019, vec3(2.2));
}

vec3 reinhard(vec3 x) {
    return x / (1.0 + x);
}

vec3 reinhard2(vec3 x) {
    const float L_white = 10.0;
    return (x * (1.0 + x / (L_white * L_white))) / (1.0 + x);
}

vec3 aces(vec3 x) {
    // Narkowicz 2015, "ACES Filmic Tone Mapping Curve"
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return (x * (a * x + b)) / (x * (c * x + d) + e);
}

vec3 uchimura(vec3 x, float P, float a, float m, float l, float c, float b) {
    // Uchimura 2017, "HDR theory and practice"
    // Math: https://www.desmos.com/calculator/gslcdxvipg
    // Source: https://www.slideshare.net/nikuque/hdr-theory-and-practicce-jp
    float l0 = ((P - m) * l) / a;
    float L0 = m - m / a;
    float L1 = m + (1.0 - m) / a;
    float S0 = m + l0;
    float S1 = m + a * l0;
    float C2 = (a * P) / (P - S1);
    float CP = -C2 / P;

    vec3 w0 = 1.0 - smoothstep(0.0, m, x);
    vec3 w2 = step(m + l0, x);
    vec3 w1 = 1.0 - w0 - w2;

    vec3 T = m * pow(x / m, vec3(c)) + b;
    vec3 S = P - (P - S1) * exp(CP * (x - S0));
    vec3 L = m + a * (x - m);

    return T * w0 + L * w1 + S * w2;
}

vec3 uchimura(vec3 x) {
    const float P = 1.0;  // max display brightness
    const float a = 1.0;  // contrast
    const float m = 0.22; // linear section start
    const float l = 0.4;  // linear section length
    const float c = 1.33; // black
    const float b = 0.0;  // pedestal
    return uchimura(x, P, a, m, l, c, b);
}

vec3 uchimura2(vec3 x) {
    const float P = 1.0;  // max display brightness
    const float a = 1.7;  // contrast
    const float m = 0.1; // linear section start
    const float l = 0.0;  // linear section length
    const float c = 1.33; // black
    const float b = 0.0;  // pedestal
    return uchimura(x, P, a, m, l, c, b);
}

vec3 uchimura3(vec3 x) {
    const float P = 1.0;  // max display brightness
    const float a = 1.05;  // contrast
    const float m = 0.2; // linear section start
    const float l = 0.4;  // linear section length
    const float c = 1.35; // black
    const float b = 0.0;  // pedestal
    return uchimura(x, P, a, m, l, c, b);
}

vec3 lottes(vec3 x) {
    // Lottes 2016, "Advanced Techniques and Optimization of HDR Color Pipelines"
    const float a = 1.6;
    const float d = 0.977;
    const float hdrMax = 8.0;
    const float midIn = 0.18;
    const float midOut = 0.267;

    // Can be precomputed
    const float b =
    (-pow(midIn, a) + pow(hdrMax, a) * midOut) /
    ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);
    const float c =
    (pow(hdrMax, a * d) * pow(midIn, a) - pow(hdrMax, a) * pow(midIn, a * d) * midOut) /
    ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);

    return pow(x, vec3(a)) / (pow(x, vec3(a * d)) * b + c);
}

void main() {
    fragColor = texelFetch(inputTexture, ivec2(fTexCoordI), 0);
    fragColor.rgb = TONEMAP_FUNC(fragColor.rgb);
}