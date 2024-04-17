#version 460 core

#include "../GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec2 fPos;
out vec2 fTexCoord;
out vec2 fTexCoordI;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);

    fPos = vPos;
    fTexCoord = vPos * 0.5 + 0.5;
    fTexCoordI = fTexCoord * ubo_cameraDefault.screenSize.xy;
}