#version 460 core

#extension GL_KHR_shader_subgroup_arithmetic : enable

layout(local_size_x = 32, local_size_y = 32) in;

struct ParticleDataRaw {
    vec4 data1;
    uvec4 data2;
};

layout(std430) buffer ParticleDataBuffer {
    uvec4 data;
    ParticleDataRaw particles[];
} ssbo_particleData;

layout(r32i) uniform iimage2D uimg_renderCount;

shared int shared_minCount[32];
shared int shared_maxCount[32];

void main() {
    int count = imageLoad(uimg_renderCount, ivec2(gl_GlobalInvocationID.xy)).r;
    imageStore(uimg_renderCount, ivec2(gl_GlobalInvocationID.xy), ivec4(count));

    int minCount1 = subgroupMin(count);
    int maxCount1 = subgroupMax(count);
    if (subgroupElect()) {
        shared_minCount[gl_SubgroupID] = minCount1;
        shared_maxCount[gl_SubgroupID] = maxCount1;
    }
    barrier();

    if (gl_SubgroupID == 0) {
        int minCount2 = shared_minCount[gl_SubgroupInvocationID];
        int maxCount2 = shared_maxCount[gl_SubgroupInvocationID];
        int minCount3 = subgroupMin(minCount2);
        int maxCount3 = subgroupMax(maxCount2);
        if (subgroupElect()) {
            atomicMin(ssbo_particleData.data.x, minCount3);
            atomicMax(ssbo_particleData.data.y, maxCount3);
        }
    }
}
