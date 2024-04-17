#version 460 core

#include "../GlobalCamera.glsl"

uniform vec3 originOffset;

layout(location = 0) in uint vertRawIndirectIdx;
layout(location = 1) in vec3 vertChunkOffset;
layout(location = 2) in vec3 vertBoxPos;

out flat uint rawIndirectIdx;

void main() {
    vec4 worldCoord = vec4(vertBoxPos + vertChunkOffset * 16.0, 1.0);
    worldCoord.xyz += originOffset;

    rawIndirectIdx = vertRawIndirectIdx;
    gl_Position = ubo_cameraDefault.combined * worldCoord;
}
