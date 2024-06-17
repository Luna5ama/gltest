#version 460 core

#include "../GlobalCamera.glsl"

struct CullTestCommand {
    uint chunkIndex;
    uint chunkOffsetEncoded;
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

uniform vec3 uval_originOffset;

const vec3 BOX_VERT_POS[8] = {
vec3(-1.0, -1.0, -1.0),
vec3(17.0, -1.0, -1.0),
vec3(-1.0, -1.0, 17.0),
vec3(17.0, -1.0, 17.0),
vec3(-1.0, 17.0, -1.0),
vec3(17.0, 17.0, -1.0),
vec3(-1.0, 17.0, 17.0),
vec3(17.0, 17.0, 17.0)
};

const uvec3 CHUNK_OFFSET_BITMASK = (uvec3(1u) << uvec3(13u, 6u, 13u)) - uvec3(1u);
const uvec3 CHUNK_OFFSET_BITSHIFT = uvec3(0u, 13u, 13u + 6u);

layout(location = 0) out PrimData {
    flat uint chunkIndex;
} primDataOut;

void main() {
    uint batchIndex = gl_InstanceID;
    CullTestCommand cullCommand = ssbo_cullTestCommandBuffer.commands[batchIndex];

    uvec3 chunkOffsetEncoded = uvec3(cullCommand.chunkOffsetEncoded);
    chunkOffsetEncoded = (chunkOffsetEncoded >> CHUNK_OFFSET_BITSHIFT) & CHUNK_OFFSET_BITMASK;
    vec3 chunkOffset = vec3(chunkOffsetEncoded);
    chunkOffset = chunkOffset * 16.0 + uval_originOffset;

    primDataOut.chunkIndex = cullCommand.chunkIndex;

    gl_Position = ubo_cameraDefault.combined * vec4(BOX_VERT_POS[gl_VertexID] + chunkOffset, 1.0);
}
