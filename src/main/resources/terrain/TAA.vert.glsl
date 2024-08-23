#version 460 core

#include "GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

uniform vec2 uJitter;

out vec2 fTexCoord;
out vec2 fTexCoordI;
out vec2 fJitterTexCoord;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoord = vPos * 0.5 + 0.5;
    fTexCoordI = fTexCoord * ubo_cameraDefault.screenSize.xy;
    fJitterTexCoord = fTexCoord + uJitter * ubo_cameraDefault.screenSize.zw;
}