#version 460 core
#pragma optionNV (unroll all)

#include "../ColorUtils.glsl"

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

// Uniforms:
uniform vec4 uParams = vec4(1.0, 0.1, 0.1, 921600.0);
#define uInvRangePow uParams.x
#define logLumRange uParams.y
#define timeCoeff uParams.z
#define numPixels uParams.w
uniform vec2 uRange = vec2(0.01, 1.0);

#define localIndex gl_LocalInvocationIndex

// We'll be writing our average to s_target
layout(r32f) uniform image2D uimg_avgLum;

layout(std430) buffer ssbo_histogramBuffer {
    uint histogram[];
};

void main() {
    float top5Percent = numPixels;
    float topBin = float(max(histogram[255], 1));

    float expLast = imageLoad(uimg_avgLum, ivec2(0, 0)).x;
    float expNew = (top5Percent / topBin) * expLast;
    expNew = clamp(expNew, uRange.x, uRange.y);
    expNew = mix(expLast, expNew, timeCoeff);

    imageStore(uimg_avgLum, ivec2(0, 0), vec4(expNew, 0.0, 0.0, 0.0));
}