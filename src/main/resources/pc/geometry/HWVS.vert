#version 460 core
#define CUTOUT

#include "../GlobalCamera.glsl"
#include "../utils/SWRUtils.glsl"
#include "Chunks.glsl"

layout(std430) readonly buffer QuadPosDataBuffer {
    uvec2 ssbo_quadPosDataBuffer[];
};

layout(std430) readonly buffer QuadPosIndexBuffer {
    uint ssbo_quadPosIndexBuffer[];
};

layout(location = 0) out VertData {
    flat uint quadIndex;
    flat uint chunkIndex;
    vec4 bCoord;
} vertDataOut;

const vec4 BCOORDS[4] = {
    vec4(1.0, 0.0, 0.0, 0.0),
    vec4(0.0, 1.0, 0.0, 0.0),
    vec4(0.0, 0.0, 1.0, 0.0),
    vec4(0.0, 0.0, 0.0, 1.0)
};

const vec3 COORD_CONVERT = vec3(2.51773861295491E-4);

const uvec3 CHUNK_OFFSET_BITMASK = (uvec3(1u) << uvec3(13u, 6u, 13u)) - uvec3(1u);
const uvec3 CHUNK_OFFSET_BITSHIFT = uvec3(0u, 13u, 13u + 6u);

void main() {
    vertDataOut.chunkIndex = gl_BaseInstance;
    vec3 chunkOffset = chunks_getChunkRenderPos(gl_BaseInstance);

    vertDataOut.quadIndex = uint(gl_VertexID) / 4;
    uint quadPosDataIdx = ssbo_quadPosIndexBuffer[vertDataOut.quadIndex] * 3u;

    uvec2 posXData = ssbo_quadPosDataBuffer[quadPosDataIdx];
    uvec2 posYData = ssbo_quadPosDataBuffer[quadPosDataIdx + 1u];
    uvec2 posZData = ssbo_quadPosDataBuffer[quadPosDataIdx + 2u];

    vec4 posXs = decodePos(posXData);
    vec4 posYs = decodePos(posYData);
    vec4 posZs = decodePos(posZData);

    vertDataOut.bCoord = BCOORDS[gl_VertexID % 4u];

    vec4 vertPos = vec4(
        dot(posXs, vertDataOut.bCoord),
        dot(posYs, vertDataOut.bCoord),
        dot(posZs, vertDataOut.bCoord),
        1.0
    );

    vertPos.xyz = vertPos.xyz * COORD_CONVERT + chunkOffset;

    gl_Position = ubo_cameraDefault.combined * vertPos;
//    vertDataOut.depth = gl_Position.z;
}