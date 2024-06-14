#version 460 core
#define CUTOUT

#include "vbuffer/VBuffers.glsl"

#ifdef CUTOUT
uniform sampler2D usam_blockAlbedoAtlas;

layout(std430) readonly buffer QuadTexCoordDataBuffer {
    uint ssbo_quadTexCoordDataBuffer[];
};

layout(std430) readonly buffer QuadAttribIndexBuffer {
    uint ssbo_quadAttribIndexBuffer[];
};
#endif

in vec4 frag_bCoord;
in float frag_depth;
flat in uint frag_quadIndex;

layout(depth_greater) out float gl_FragDepth;
layout(location = 0) out uvec2 rt_vbuffer;

void main() {
    gl_FragDepth = frag_depth;

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
    vec2 texCoord = vec2(dot(texCoordX, frag_bCoord), dot(texCoordY, frag_bCoord));

    if (textureLod(usam_blockAlbedoAtlas, texCoord, 0.0).a <= 0.5) discard;
    #endif
    rt_vbuffer = uvec2(frag_quadIndex, vbuffer_encodeBCoord(frag_bCoord.xyz));
}