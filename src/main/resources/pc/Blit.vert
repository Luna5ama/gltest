#version 460 core

#include "GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec2 fClipCoord;
out vec2 fTexCoord;
out mat3 fNormalMatrix;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fClipCoord = vPos;
    fTexCoord = vPos * 0.5 + 0.5;
    fNormalMatrix = transpose(inverse(mat3(ubo_camera.modelView)));
}