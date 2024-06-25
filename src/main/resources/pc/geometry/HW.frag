#version 460 core
#define CUTOUT

#include "../vbuffer/VBuffers.glsl"

#ifdef CUTOUT
uniform sampler2D usam_blockAlbedoAtlas;

layout(std430) readonly buffer QuadTexCoordDataBuffer {
    uint ssbo_quadTexCoordDataBuffer[];
};

layout(std430) readonly buffer QuadAttribIndexBuffer {
    uint ssbo_quadAttribIndexBuffer[];
};
#endif

layout(location = 0) in VertData {
    flat uint quadIndex;
    flat uint chunkIndex;
    vec4 bCoord;
} vertDataIn;

layout(location = 0) out uvec2 rt_vbuffer;

void main() {
    #ifdef CUTOUT
    uint texCoordIdx = ssbo_quadAttribIndexBuffer[vertDataIn.quadIndex] * 4u;
    vec4 texCoordX = vec4(
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx]),
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx + 1u])
    );
    vec4 texCoordY = vec4(
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx + 2u]),
        unpackUnorm2x16(ssbo_quadTexCoordDataBuffer[texCoordIdx + 3u])
    );
    vec2 texCoord = vec2(dot(texCoordX, vertDataIn.bCoord), dot(texCoordY, vertDataIn.bCoord));

    if (textureLod(usam_blockAlbedoAtlas, texCoord, 0.0).a <= 0.5) discard;
    #endif
    rt_vbuffer = uvec2((vertDataIn.quadIndex << 1u) | (uint(gl_PrimitiveID) & 1u), vertDataIn.chunkIndex);
}