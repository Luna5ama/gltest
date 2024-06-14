#version 460 core
#define CUTOUT

#include "vbuffer/VBuffers.glsl"

#ifdef CUTOUT
uniform sampler2D usam_blockAlbedoAtlas;

in vec2 frag_texCoord;
#endif
in vec4 frag_bCoord;
in float frag_depth;
flat in uint frag_quadIndex;

layout(location = 0) out uvec2 rt_vbuffer;

void main() {
    if (textureLod(usam_blockAlbedoAtlas, frag_texCoord, 0.0).a <= 0.5) discard;
    rt_vbuffer = uvec2(frag_quadIndex, vbuffer_encodeBCoord(frag_bCoord.xyz));
    gl_FragDepth = frag_depth;
}