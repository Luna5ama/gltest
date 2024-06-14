#version 460 core
#define CUTOUT

#include "GlobalCamera.glsl"

#ifdef CUTOUT
layout(std430) readonly buffer QuadTexCoordDataBuffer {
    uint ssbo_quadTexCoordDataBuffer[];
};

layout(std430) readonly buffer QuadAttribIndexBuffer {
    uint ssbo_quadAttribIndexBuffer[];
};
#endif

layout(std430) restrict readonly buffer ChunkStartIndexBuffer {
    uint ssbo_chunkStartIndexBuffer[];
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

#ifdef CUTOUT
out vec2 frag_texCoord;
#endif
out vec4 frag_bCoord;
out float frag_depth;
flat out uint frag_quadIndex;

layout(location = 0) in int vert_chunkOffsetEncoded;

const vec4 BCOORDS[6] = {
    vec4(1.0, 0.0, 0.0, 0.0),
    vec4(0.0, 1.0, 0.0, 0.0),
    vec4(0.0, 0.0, 1.0, 0.0),
    vec4(0.0, 0.0, 1.0, 0.0),
    vec4(0.0, 0.0, 0.0, 1.0),
    vec4(1.0, 0.0, 0.0, 0.0)
};

const vec3 coordConvert = vec3(2.51773861295491E-4);

const uvec3 CHUNK_OFFSET_BITMASK = (uvec3(1u) << uvec3(13u, 6u, 13u)) - uvec3(1u);
const uvec3 CHUNK_OFFSET_BITSHIFT = uvec3(0u, 13u, 13u + 6u);

void main() {
    uvec3 chunkOffsetEncoded = uvec3(vert_chunkOffsetEncoded);
    chunkOffsetEncoded = (uvec3(vert_chunkOffsetEncoded) >> CHUNK_OFFSET_BITSHIFT) & CHUNK_OFFSET_BITMASK;
    vec3 chunkOffset = vec3(chunkOffsetEncoded);
    chunkOffset = chunkOffset * 16.0 + uval_originOffset;

    frag_quadIndex = ssbo_chunkStartIndexBuffer[gl_DrawID] + uint(gl_VertexID) / 6;
    uint quadPosDataIdx = ssbo_quadPosIndexBuffer[frag_quadIndex] * 6u;

    frag_bCoord = BCOORDS[gl_VertexID % 6u];

    vec4 posX = readPos(quadPosDataIdx);
    vec4 posY = readPos(quadPosDataIdx + 2u);
    vec4 posZ = readPos(quadPosDataIdx + 4u);

    vec4 vertPos = vec4(
        dot(posX, frag_bCoord),
        dot(posY, frag_bCoord),
        dot(posZ, frag_bCoord),
        1.0
    );

    vertPos.xyz = vertPos.xyz * coordConvert + chunkOffset;

    gl_Position = ubo_cameraDefault.combined * vertPos;
    frag_depth = gl_Position.z;

    #ifdef CUTOUT
    uint texCoordIdx = ssbo_quadAttribIndexBuffer[frag_quadIndex] * 4u;
    vec4 texCoordX = vec4(
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx]),
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx + 1u])
    );
    vec4 texCoordY = vec4(
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx + 2u]),
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx + 3u])
    );
    frag_texCoord = vec2(
        dot(texCoordX, frag_bCoord),
        dot(texCoordY, frag_bCoord)
    );
    #endif
}