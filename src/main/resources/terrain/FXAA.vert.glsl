#version 460 core

#include "GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec2 texCoord;
out vec4 posPos;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    texCoord = vPos * 0.5 + 0.5;

    posPos.xy = texCoord.xy;
    posPos.zw = texCoord.xy - (ubo_cameraDefault.screenSize.zw * 0.5);
}
