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

#ifdef MESH_SHADER
layout(location = 0) perprimitiveNV in PrimData {
    uint chunkIndex;
} primDataIn;
#else
layout(location = 0) in PrimData {
    flat uint chunkIndex;
} primDataIn;
#endif

void main() {
    ssbo_visibleBuffer.states[primDataIn.chunkIndex] = FLAG_TYPE(1u);
}