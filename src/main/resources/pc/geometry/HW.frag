#version 460 core
#define CUTOUT

#include "../vbuffer/VBuffers.glsl"

#ifdef CUTOUT
uniform sampler2D usam_blockAlbedoAtlas;

layout(std430) readonly buffer QuadTexCoordDataBuffer {
    uvec4 ssbo_quadTexCoordDataBuffer[];
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

layout(location = 0) out uint rt_vbuffer;

void main() {
    #ifdef CUTOUT
    uvec4 texCoordXY = ssbo_quadTexCoordDataBuffer[ssbo_quadAttribIndexBuffer[vertDataIn.quadIndex]];
    vec4 texCoordX = vec4(
        unpackUnorm2x16(texCoordXY.x),
        unpackUnorm2x16(texCoordXY.y)
    );
    vec4 texCoordY = vec4(
        unpackUnorm2x16(texCoordXY.z),
        unpackUnorm2x16(texCoordXY.w)
    );
    vec2 texCoord = vec2(dot(texCoordX, vertDataIn.bCoord), dot(texCoordY, vertDataIn.bCoord));

    if (textureLod(usam_blockAlbedoAtlas, texCoord, 0.0).a <= 0.5) discard;
    #endif
    rt_vbuffer = (vertDataIn.quadIndex << 1u) | (uint(gl_PrimitiveID) & 1u);
}