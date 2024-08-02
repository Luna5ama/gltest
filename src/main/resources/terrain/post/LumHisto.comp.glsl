#version 460 core

#include "../ColorUtils.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

#define EPSILON 0.001

// Uniforms:
uniform vec4 uParams = vec4(1.0, 0.1, 1.0, 1.0);
#define pRangePow uParams.x
#define pInvLumRange uParams.y

uniform sampler2D usam_mainFbo_inputColor;

layout(std430, binding = 1) buffer ssbo_histogramBuffer {
    uint histogram[];
};

// Shared histogram buffer used for storing intermediate sums for each work group
shared uint histogramShared[256];

void main() {
    // Initialize the bin for this thread to 0
    histogramShared[gl_LocalInvocationIndex] = 0;
    barrier();

    uvec2 dim = textureSize(usam_mainFbo_inputColor, 0).xy;
    // Ignore threads that map to areas beyond the bounds of our HDR image
    if (gl_GlobalInvocationID.x < dim.x && gl_GlobalInvocationID.y < dim.y) {
        vec4 color = texelFetch(usam_mainFbo_inputColor, ivec2(gl_GlobalInvocationID.xy), 0);
        float lum = linear2luminace(color.rgb) * color.a;

        uint binIndex;

        // Avoid taking the log of zero
        if (lum < EPSILON) {
            binIndex = 0;
        } else {
            float lumMapped = clamp((log2(lum * pInvLumRange) + uParams.x) / uParams.x, 0.0, 1.0);

            // Map [0, 1] to [1, 255]. The zeroth bin is handled by the epsilon check above.
            binIndex = uint(lumMapped * 254.0 + 1.0);
        }

        atomicAdd(histogramShared[binIndex], 1);
    }

    // Wait for all threads in the work group to reach this point before adding our
    // local histogram to the global one
    barrier();

    // Technically there's no chance that two threads write to the same bin here,
    // but different work groups might! So we still need the atomic add.
    atomicAdd(histogram[gl_LocalInvocationIndex], histogramShared[gl_LocalInvocationIndex]);
}