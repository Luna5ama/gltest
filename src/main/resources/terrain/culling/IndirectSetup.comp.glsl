#version 460 core
//#extension GL_NV_gpu_shader5 : enable
//#define uint8_t uint

#define uint_t uint

layout(local_size_x = 128) in;

struct IndirectDrawCommand {
    uint count;
    uint instanceCount;
    uint firstIndex;
    int baseVertex;
    uint baseInstance;
};

uniform uint uval_prevVisibleOffset;
uniform vec3 uval_originOffset;

layout(binding = 0) uniform atomic_uint indirectCounter;

layout(std430) restrict readonly buffer ChunkOffsetBuffer {
    uint chunkOffsets[];
};

layout(std430) restrict buffer VisibleBufferRW {
    uint_t visibleBufferRW[];
};

layout(std430) restrict readonly buffer VisibleBuffer {
    uint_t visibleBuffer[];
};

layout(std430) restrict readonly buffer RawIndirectBuffer {
    IndirectDrawCommand rawIndirects[];
};

layout(std430) restrict writeonly buffer OutIndirectBuffer {
    IndirectDrawCommand outIndirects[];
};

subroutine void VisibleStateCompute();

subroutine uniform VisibleStateCompute usubroutine_VisibleStateCompute;

subroutine(VisibleStateCompute) void lastVisible() {
    IndirectDrawCommand cmd = rawIndirects[gl_GlobalInvocationID.x];
    vec3 offset = vec3(uvec3(chunkOffsets[cmd.baseInstance]) >> uvec3(0u, 8u, 16u) & uvec3(255u));
    vec3 coord = offset * 16.0f + uval_originOffset;

    bool isCameraChunk = dot(coord, coord) < 1024.0;
    bool prev = bool(visibleBufferRW[gl_GlobalInvocationID.x]) || isCameraChunk;
    visibleBufferRW[gl_GlobalInvocationID.x] = uint_t(0u);
    visibleBufferRW[gl_GlobalInvocationID.x + uval_prevVisibleOffset] = uint_t(prev);
    if (!prev) {
        return;
    }

    uint outIndex = atomicCounterIncrement(indirectCounter);
    outIndirects[outIndex] = rawIndirects[gl_GlobalInvocationID.x];
}

subroutine(VisibleStateCompute) void newlyVisible() {
    bool prev = bool(uint(visibleBuffer[gl_GlobalInvocationID.x + uval_prevVisibleOffset]));
    bool curr = bool(uint(visibleBuffer[gl_GlobalInvocationID.x]));
    if (prev || !curr) {
        return;
    }

    uint outIndex = atomicCounterIncrement(indirectCounter);
    outIndirects[outIndex] = rawIndirects[gl_GlobalInvocationID.x];
}

subroutine(VisibleStateCompute) void currentVisible() {
    IndirectDrawCommand cmd = rawIndirects[gl_GlobalInvocationID.x];
    vec3 offset = 8.0f + unpackUnorm4x8(chunkOffsets[cmd.baseInstance]).xyz * 16.0f + uval_originOffset;

    bool isCameraChunk = dot(offset, offset) < 1024.0;
    bool prev = bool(visibleBuffer[gl_GlobalInvocationID.x]) || isCameraChunk;
    if (!prev) {
        return;
    }

    uint outIndex = atomicCounterIncrement(indirectCounter);
    outIndirects[outIndex] = cmd;
}

void main() {
    if (gl_GlobalInvocationID.x >= uval_prevVisibleOffset) {
        return;
    }

    usubroutine_VisibleStateCompute();
}