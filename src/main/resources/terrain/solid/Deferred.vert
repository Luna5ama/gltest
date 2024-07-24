#version 460 core

#include "../ColorUtils.glsl"
#include "../GlobalCamera.glsl"
#include "../rtwsm/ShadowParams.glsl"

uniform vec3 originOffset;

#include "../TerrainVertAttrib.glsl"

struct GeomData {
    vec3 worldCoord;
    vec3 worldNormal;
    vec2 texCoord;
    vec2 lightCoord;
    vec4 colorMul;
    float lodMul;
    float alphaTestV;
};

out GeomData fragData;
out uint frag_mdlAttrib;

const vec3 COORD_CONVERT = vec3(2.51773861295491E-4);

void main() {
    vec3 worldCoord = fma(vPos, COORD_CONVERT, vChunkOffset * 16.0 + originOffset);
    gl_Position = ubo_cameraDefault.combined * vec4(worldCoord, 1.0);

    fragData.worldCoord = worldCoord;
    fragData.colorMul = vec4(vColorMul, 1.0);
    fragData.texCoord = vTexCoord;
    fragData.lightCoord = (vLightCoord + 8.0) * 0.00390625;
    fragData.worldNormal = vNormal;
    fragData.lodMul = float(vMdlAttrib & 1u);
    fragData.alphaTestV = float((vMdlAttrib >> 1u) & 1u) - 0.5;

    frag_mdlAttrib = vMdlAttrib;
}