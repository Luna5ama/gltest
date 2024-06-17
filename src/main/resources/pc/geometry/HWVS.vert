#version 460 core
#define CUTOUT

#include "../GlobalCamera.glsl"

layout(std430) restrict readonly buffer ChunkOffsetData {
    uint ssbo_chunkOffsetData[];
};

layout(std430) restrict readonly buffer QuadPosDataBuffer {
    uint ssbo_quadPosDataBuffer[];
};

layout(std430) restrict readonly buffer QuadPosIndexBuffer {
    uint ssbo_quadPosIndexBuffer[];
};

uniform vec3 uval_originOffset;

vec4 readPos(uint idx) {
    uint data1 = ssbo_quadPosDataBuffer[idx];
    uint data2 = ssbo_quadPosDataBuffer[idx + 1];
    return vec4(uvec4(data1 & 0xFFFFu, data1 >> 16u, data2 & 0xFFFFu, data2 >> 16u));
}

layout(location = 0) out VertData {
    flat uint quadIndex;
    vec4 bCoord;
    float depth;
} vertDataOut;

const vec4 BCOORDS[4] = {
    vec4(1.0, 0.0, 0.0, 0.0),
    vec4(0.0, 1.0, 0.0, 0.0),
    vec4(0.0, 0.0, 1.0, 0.0),
    vec4(0.0, 0.0, 0.0, 1.0)
};

const vec3 coordConvert = vec3(2.51773861295491E-4);

const uvec3 CHUNK_OFFSET_BITMASK = (uvec3(1u) << uvec3(13u, 6u, 13u)) - uvec3(1u);
const uvec3 CHUNK_OFFSET_BITSHIFT = uvec3(0u, 13u, 13u + 6u);

void main() {
    uvec3 chunkOffsetEncoded = uvec3(ssbo_chunkOffsetData[gl_BaseInstance]);
    chunkOffsetEncoded = (chunkOffsetEncoded >> CHUNK_OFFSET_BITSHIFT) & CHUNK_OFFSET_BITMASK;
    vec3 chunkOffset = vec3(chunkOffsetEncoded);
    chunkOffset = chunkOffset * 16.0 + uval_originOffset;

    vertDataOut.quadIndex = uint(gl_VertexID) / 4;
    uint quadPosDataIdx = ssbo_quadPosIndexBuffer[vertDataOut.quadIndex] * 6u;

    vertDataOut.bCoord = BCOORDS[gl_VertexID % 4u];

    vec4 posX = readPos(quadPosDataIdx);
    vec4 posY = readPos(quadPosDataIdx + 2u);
    vec4 posZ = readPos(quadPosDataIdx + 4u);

    vec4 vertPos = vec4(
        dot(posX, vertDataOut.bCoord),
        dot(posY, vertDataOut.bCoord),
        dot(posZ, vertDataOut.bCoord),
        1.0
    );

    vertPos.xyz = vertPos.xyz * coordConvert + chunkOffset;

    gl_Position = ubo_cameraDefault.combined * vertPos;
    vertDataOut.depth = gl_Position.z;
}