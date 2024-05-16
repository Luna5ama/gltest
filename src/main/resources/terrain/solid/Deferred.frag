#version 460 core
#ifndef CUTOUT
layout(early_fragment_tests) in;
#endif

#include "../GBuffers.glsl"
#include "../ColorUtils.glsl"

uniform sampler2D usam_blockAlbedoAtlas;

struct GeomData {
    vec3 worldCoord;
    vec3 worldNormal;
    vec2 texCoord;
    vec2 lightCoord;
    vec4 colorMul;
//    float lodMul;
    float alphaTestV;
};

in GeomData fragData;
in flat uint frag_mdlAttrib;

layout(location = 0) out uvec4 rt_tex1;
layout(location = 1) out uvec4 rt_tex2;

void main() {
    GBufferData outData;

    outData.texCoord = fragData.texCoord;
//    float lod = max(textureQueryLod(blockTexture, albedo).y, 0.0);
    outData.albedo = textureLod(usam_blockAlbedoAtlas, outData.texCoord, 0.0);

    #ifdef CUTOUT
    if (outData.albedo.a <= 0.5) discard;
    #endif

    outData.albedo *= fragData.colorMul;
    outData.albedo.rgb = srgb2linear(outData.albedo.rgb);

    outData.worldCoord = fragData.worldCoord;
    outData.worldNormal = fragData.worldNormal;
    outData.materialID = frag_mdlAttrib;
    outData.lightCoord = fragData.lightCoord;
    outData.worldTangent = vec3(1.0, 0.0, 0.0);

    gbuffer_pack1(rt_tex1, outData);
    gbuffer_pack2(rt_tex2, outData);
}