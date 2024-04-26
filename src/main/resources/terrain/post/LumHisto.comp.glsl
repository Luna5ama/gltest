#version 460 core

#include "../ColorUtils.glsl"

#define THREADS_X 16
#define THREADS_Y 16
#define GROUP_SIZE THREADS_X * THREADS_Y

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

#define EPSILON 0.005

// Uniforms:
uniform vec4 uParams = vec4(1.0, 0.1, 1.0, 1.0);
#define pRangePow uParams.x
#define pInvLumRange uParams.y

uniform sampler2D usam_mainFbo_inputColor;

layout(std430, binding = 1) buffer ssbo_histogramBuffer {
    uint histogram[];
};

// Shared histogram buffer used for storing intermediate sums for each work group
shared uint histogramShared[GROUP_SIZE];


// For a given color and luminance range, return the histogram bin index
uint colorToBin(vec3 hdrColor, float inverseLumRange) {
    // Convert our RGB value to Luminance
    float lum = linear2luminace(hdrColor);

    // Avoid taking the log of zero
    if (lum < EPSILON) {
        return 0;
    }

    // Calculate the log_2 luminance and express it as a value in [0.0, 1.0]
    // where 0.0 represents the minimum luminance, and 1.0 represents the max.
    float lumMapped = clamp(lum * inverseLumRange, 0.0, 1.0);
    lumMapped = 1.0 - pow(1.0 - lumMapped, pRangePow);

    // Map [0, 1] to [1, 255]. The zeroth bin is handled by the epsilon check above.
    return uint(lumMapped * 254.0 + 1.0);
}

void main() {
    // Initialize the bin for this thread to 0
    histogramShared[gl_LocalInvocationIndex] = 0;
    barrier();

    uvec2 dim = textureSize(usam_mainFbo_inputColor, 0).xy;
    // Ignore threads that map to areas beyond the bounds of our HDR image
    if (gl_GlobalInvocationID.x < dim.x && gl_GlobalInvocationID.y < dim.y) {
        vec3 hdrColor = texelFetch(usam_mainFbo_inputColor, ivec2(gl_GlobalInvocationID.xy), 0).xyz;
        uint binIndex = colorToBin(hdrColor, pInvLumRange);
        // We use an atomic add to ensure we don't write to the same bin in our
        // histogram from two different threads at the same time.
        atomicAdd(histogramShared[binIndex], 1);
    }

    // Wait for all threads in the work group to reach this point before adding our
    // local histogram to the global one
    barrier();

    // Technically there's no chance that two threads write to the same bin here,
    // but different work groups might! So we still need the atomic add.
    atomicAdd(histogram[gl_LocalInvocationIndex], histogramShared[gl_LocalInvocationIndex]);
}