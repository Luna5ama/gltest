#version 460 core
//#extension GL_NV_gpu_shader5 : enable

#define uint_t uint

layout(early_fragment_tests) in;

layout(std430) restrict writeonly buffer VisibleBuffer {
    uint_t visibleBuffer[];
};

in flat uint rawIndirectIdx;

void main() {
    visibleBuffer[rawIndirectIdx] = uint_t(1u);
}