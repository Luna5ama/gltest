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

shared int shared_maxCount[32];

void main() {
    int count = imageLoad(uimg_renderCount, ivec2(gl_GlobalInvocationID.xy)).r;
//    if (bool(ssbo_particleData.data.y)) {
//        count = int(float(count) * 0.9);
//    }
    imageStore(uimg_renderCount, ivec2(gl_GlobalInvocationID.xy), ivec4(count));

    int count2 = subgroupMax(count);
    if (subgroupElect()) {
        shared_maxCount[gl_SubgroupID] = count2;
    }
    barrier();

    if (gl_SubgroupID == 0) {
        int count3 = shared_maxCount[gl_SubgroupInvocationID];
        int count4 = subgroupMax(count3);
        if (subgroupElect()) {
//            atomicAdd(ssbo_particleData.data.x, count4);
            atomicMax(ssbo_particleData.data.x, count4);
        }
    }
}
