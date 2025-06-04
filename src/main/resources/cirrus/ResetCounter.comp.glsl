#version 460 core

layout(local_size_x = 1) in;

struct ParticleDataRaw {
    vec4 data1;
    uvec4 data2;
};

layout(std430) buffer ParticleDataBuffer {
    uvec4 data;
    ParticleDataRaw particles[];
} ssbo_particleData;

void main() {
    ssbo_particleData.data = uvec4(0xFFFFFFFFu, 0u, 0u, 0u);
}
