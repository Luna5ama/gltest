#version 460 core

#include "../GlobalCamera.glsl"
#include "ShadowParams.glsl"
//#include "ShadowSamplers.glsl"

uniform vec3 originOffset;
uniform float uBaseImportance;

#include "../TerrainVertAttrib.glsl"

out float fImportance;

const vec3 coordConvert = vec3(2.51773861295491E-4);

void main() {
    // gl_BaseInstance exploit
    vec3 worldCoord = fma(vPos, coordConvert, vChunkOffset * 16.0 + originOffset);
    float aoMultiplier = float((vMdlAttrib >> 2u) & 1u);
    worldCoord += (1.0 - aoMultiplier) * uShadowMap.lightDir * 0.05;

    gl_Position = uShadowMap.matrix * vec4(worldCoord, 1.0);
    gl_Position /= gl_Position.w;

    float pDist = max(length(worldCoord), 4.0) / 4.0;
    vec3 viewDir = normalize(worldCoord);

    float importance = uBaseImportance;

    // Distance to Eye Function
    importance *= 1.0 / pDist;

    // Surface Normal Function
    const float SN_BETA = 0.5;
    importance *= 1.0 + SN_BETA * max(dot(vNormal, -viewDir), 0.0);

    const float LSN_BETA = 0.1;
    importance *= 1.0 + LSN_BETA * sqrt(pDist) * pow(1.0 - abs(dot(vNormal, uShadowMap.lightDir)), 8.0);

    // Shadow Edge function
//    vec2 shadowTS = gl_Position.xy * 0.5 + 0.5;
//    shadowTS = rtwsm_warpTexCoord(usam_warpingMap, shadowTS);
//    float center = texture(usam_shadowDepth, shadowTS).x;
//    float maxDiff = 0.0;
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(-1, -1)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(-1, 0)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(-1, 1)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(0, -1)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(0, 1)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(1, -1)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(1, 0)).x));
//    maxDiff = max(maxDiff, abs(center - textureOffset(usam_shadowDepth, shadowTS, ivec2(1, 1)).x));
//    maxDiff *= planeZ.y - planeZ.x;
//    const float NON_EDGE = 0.5;
//    importance *= mix(NON_EDGE, 1.0, smoothstep(1.0, 4.0, maxDiff));

    fImportance = importance;
}