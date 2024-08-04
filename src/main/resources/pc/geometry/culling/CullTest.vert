#version 460 core

#include "../Chunks.glsl"
#include "../../GlobalCamera.glsl"

struct CullTestCommand {
    uint chunkIndex;
};

layout(std430) restrict readonly buffer CullTestCommandBuffer {
    uint count;
    uint instanceCount;
    uint firstIndex;
    int baseVertex;
    uint baseInstance;
    uint _padding1;
    uint _padding2;
    uint _padding3;
    CullTestCommand commands[];
} ssbo_cullTestCommandBuffer;

const vec3 BOX_VERT_POS[8] = {
vec3(-1.0, -1.0, -1.0), // 0
vec3(17.0, -1.0, -1.0), // 1
vec3(-1.0, -1.0, 17.0), // 2
vec3(17.0, -1.0, 17.0), // 3
vec3(-1.0, 17.0, -1.0), // 4
vec3(17.0, 17.0, -1.0), // 5
vec3(-1.0, 17.0, 17.0), // 6
vec3(17.0, 17.0, 17.0)  // 7
};

layout(location = 0) out PrimData {
    flat uint chunkIndex;
} primDataOut;

void main() {
    uint batchIndex = gl_InstanceID;
    CullTestCommand cullCommand = ssbo_cullTestCommandBuffer.commands[batchIndex];

    vec3 chunkOffset = chunks_getChunkRenderPos(cullCommand.chunkIndex);

    primDataOut.chunkIndex = cullCommand.chunkIndex;

    gl_Position = ubo_cameraDefault.combined * vec4(BOX_VERT_POS[gl_VertexID] + chunkOffset, 1.0);
}
