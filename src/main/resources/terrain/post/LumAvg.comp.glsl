#version 460 core
#pragma optionNV (unroll all)

#include "../ColorUtils.glsl"

#define THREADS_X 256
#define THREADS_Y 1
#define GROUP_SIZE THREADS_X * THREADS_Y

layout(local_size_x = THREADS_X, local_size_y = THREADS_Y, local_size_z = 1) in;

// Uniforms:
uniform vec4 uParams = vec4(1.0, 0.1, 0.1, 921600.0);
#define uInvRangePow uParams.x
#define logLumRange uParams.y
#define timeCoeff uParams.z
#define numPixels uParams.w

#define localIndex gl_LocalInvocationIndex

// We'll be writing our average to s_target
layout(r32f) uniform image2D uimg_avgLum;

layout(std430) buffer ssbo_histogramBuffer {
    uint histogram[];
};

// Shared
shared uint histogramShared[GROUP_SIZE];

void main() {
    // Get the count from the histogram buffer
    uint countForThisBin = histogram[localIndex];
    histogramShared[localIndex] = countForThisBin * localIndex;

    barrier();

    // Reset the count stored in the buffer in anticipation of the next pass
    histogram[localIndex] = 0;

    // This loop will perform a weighted count of the luminance range
    for (uint cutoff = (GROUP_SIZE >> 1); cutoff > 0; cutoff >>= 1) {
        if (uint(localIndex) < cutoff) {
            histogramShared[localIndex] += histogramShared[localIndex + cutoff];
        }

        barrier();
    }

    // We only need to calculate this once, so only a single thread is needed.
    if (gl_LocalInvocationIndex == 0) {
        // Here we take our weighted sum and divide it by the number of pixels
        // that had luminance greater than zero (since the index == 0, we can
        // use countForThisBin to find the number of black pixels)
        float weightedLogAverage = (histogramShared[0] / max(numPixels - float(countForThisBin), 1.0)) - 1.0;

        // Map from our histogram space to actual luminance
        float a = weightedLogAverage / 255.0;
        float b = 1.0 - pow(1.0 - a, uInvRangePow);
        float weightedAvgLum = b * logLumRange;

        // The new stored value will be interpolated using the last frames value
        // to prevent sudden shifts in the exposure.
        float lumLastFrame = imageLoad(uimg_avgLum, ivec2(0, 0)).x;
        float adaptedLum = mix(lumLastFrame, weightedAvgLum, timeCoeff);
        imageStore(uimg_avgLum, ivec2(0, 0), vec4(max(adaptedLum, 0.001), 0.0, 0.0, 0.0));
    }
}