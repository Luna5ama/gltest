#version 460 core

#include "NDPacking.glsl"
#include "GBuffers.glsl"
#include "GenericGBuffer.glsl"

layout(location = 0) out uvec2 rt_data;

in vec2 fTexCoordI;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);

    GBufferData gData;
    gbuffer_unpack1(texelFetch(usam_gData1, intTexCoord, 0), gData);

    float depth = texelFetch(usam_gDepth, intTexCoord, 0).r;

    rt_data = ndpacking_pack(gData.worldNormal, depth);
}
