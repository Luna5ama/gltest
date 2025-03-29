#version 460 core

#include "../GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec2 fTexCoord;
out vec2 fTexCoordNear[4];
out mat3 fNormalMatrix;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoord = vPos * 0.5 + 0.5;
    vec2 offset = ubo_camera.screenSize.zw * 1.0;
    fTexCoordNear[0] = fTexCoord + vec2(offset.x, 0.0);
    fTexCoordNear[1] = fTexCoord + vec2(0.0, offset.y);
    fTexCoordNear[2] = fTexCoord + vec2(-offset.x, 0.0);
    fTexCoordNear[3] = fTexCoord + vec2(0.0, -offset.y);

    fNormalMatrix = transpose(inverse(mat3(ubo_camera.modelView)));
}