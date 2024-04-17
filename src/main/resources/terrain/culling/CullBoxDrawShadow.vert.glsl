#version 460 core

#include "../GlobalCamera.glsl"
#include "../rtwsm/ShadowParams.glsl"
#include "../rtwsm/ShadowSamplers.glsl"

uniform vec3 originOffset;

layout(location = 0) in uint vertRawIndirectIdx;
layout(location = 1) in vec3 vertChunkOffset;
layout(location = 2) in vec3 vertBoxPos;

out flat uint rawIndirectIdx;

void main() {
    rawIndirectIdx = vertRawIndirectIdx;

    vec4 worldCoord = vec4(vertBoxPos + vertChunkOffset * 16.0, 1.0);
    worldCoord.xyz += originOffset;
    gl_Position = uShadowMap.matrix * worldCoord;
    gl_Position /= gl_Position.w;

    vec2 vPosTS = gl_Position.xy * 0.5 + 0.5;

    vPosTS = rtwsm_warpTexCoord(usam_warpingMap, vPosTS);

    gl_Position.xy = vPosTS * 2.0 - 1.0;
//    gl_Position.xy *= 1.01;
}
