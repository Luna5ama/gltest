#version 460 core

#extension GL_KHR_shader_subgroup_arithmetic : enable

#include "/util/Math.glsl"

layout(local_size_x = 32, local_size_y = 32) in;

layout(std430) buffer DataBuffer {
    ivec4 data;
} ssbo_data;

layout(rg32f) uniform readonly image2D uimg_noiseImage;

shared float shared_maxCount[32];

void main() {
    vec2 count2 = imageLoad(uimg_noiseImage, ivec2(gl_GlobalInvocationID.xy)).rg;
    float count = max2(abs(count2));

    float maxCount1 = subgroupMax(count);
    if (subgroupElect()) {
        shared_maxCount[gl_SubgroupID] = maxCount1;
    }
    barrier();

    if (gl_SubgroupID == 0) {
        float maxCount2 = shared_maxCount[gl_SubgroupInvocationID];
        float maxCount3 = subgroupMax(maxCount2);
        if (subgroupElect()) {
            atomicMax(ssbo_data.data.w, floatBitsToInt(maxCount3));
        }
    }
}
