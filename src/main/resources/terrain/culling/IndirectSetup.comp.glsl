#version 460 core
//#extension GL_NV_gpu_shader5 : enable
//#define uint8_t uint

#define uint_t uint

layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;

struct IndirectDrawCommand {
    uint count;
    uint instanceCount;
    uint firstIndex;
    int baseVertex;
    uint baseInstance;
};

uniform uint uPrevVisibleOffset;
uniform uint uCameraChunkIdx;

layout(binding = 0) uniform atomic_uint indirectCounter;

layout(std430) restrict buffer VisibleBufferRW {
    uint_t visibleBufferRW[];
};

layout(std430) readonly buffer VisibleBuffer {
    uint_t visibleBuffer[];
};

layout(std430) readonly buffer RawIndirectBuffer {
    IndirectDrawCommand rawIndirects[];
};

layout(std430) writeonly buffer OutIndirectBuffer {
    IndirectDrawCommand outIndirects[];
};

subroutine void VisibleStateCompute();

layout(location = 0) subroutine uniform VisibleStateCompute usubroutine_VisibleStateCompute;

subroutine(VisibleStateCompute) void lastVisible() {
    bool isCameraChunk = gl_GlobalInvocationID.x == uCameraChunkIdx;
    uint prev = uint(visibleBufferRW[gl_GlobalInvocationID.x]) | uint(isCameraChunk);
    visibleBufferRW[gl_GlobalInvocationID.x] = uint_t(0u);
    visibleBufferRW[gl_GlobalInvocationID.x + uPrevVisibleOffset] = uint_t(prev);
    if (!bool(prev)) {
        return;
    }

    uint outIndex = atomicCounterIncrement(indirectCounter);
    outIndirects[outIndex] = rawIndirects[gl_GlobalInvocationID.x];
}

subroutine(VisibleStateCompute) void newlyVisible() {
    bool prev = bool(uint(visibleBuffer[gl_GlobalInvocationID.x + uPrevVisibleOffset]));
    bool curr = bool(uint(visibleBuffer[gl_GlobalInvocationID.x]));
    if (prev || !curr) {
        return;
    }

    uint outIndex = atomicCounterIncrement(indirectCounter);
    outIndirects[outIndex] = rawIndirects[gl_GlobalInvocationID.x];
}

subroutine(VisibleStateCompute) void currentVisible() {
    bool isCameraChunk = gl_GlobalInvocationID.x == uCameraChunkIdx;
    uint prev = uint(visibleBuffer[gl_GlobalInvocationID.x]) | uint(isCameraChunk);
    if (!bool(prev)) {
        return;
    }

    uint outIndex = atomicCounterIncrement(indirectCounter);
    outIndirects[outIndex] = rawIndirects[gl_GlobalInvocationID.x];
}

void main() {
    if (gl_GlobalInvocationID.x >= uPrevVisibleOffset) {
        return;
    }

    usubroutine_VisibleStateCompute();
}