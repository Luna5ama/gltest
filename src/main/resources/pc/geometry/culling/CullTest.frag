#version 460 core

#if FLAG_TYPE == uint8_t
#extension GL_NV_gpu_shader5 : enable
#elif FLAG_TYPE == uint16_t
#extension GL_AMD_gpu_shader_int16 : enable
#endif

layout(early_fragment_tests) in;

layout(std430) restrict writeonly buffer VisibleBuffer {
    uint lastOffset;
    uint _padding1;
    uint _padding2;
    uint _padding3;
    FLAG_TYPE states[];
} ssbo_visibleBuffer;

layout(location = 0) in VertData {
    flat uint chunkIndex;
} vertDataIn;

void main() {
    ssbo_visibleBuffer.states[vertDataIn.chunkIndex] = FLAG_TYPE(1u);
}