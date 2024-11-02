#version 460 core
#pragma optionNV (unroll all)

#include "../ColorUtils.glsl"

layout(local_size_x = 1) in;

// Uniforms:
uniform vec4 uParams;
#define timeCoeff uParams.x
#define numPixels uParams.y

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
    expNew = clamp(expNew, uParams.z, uParams.w);
    expNew = mix(expLast, expNew, timeCoeff);

    imageStore(uimg_avgLum, ivec2(0, 0), vec4(expNew, 0.0, 0.0, 0.0));
}