#version 460 core

#include "GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec3 fViewCoord;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    vec4 viewCoord = ubo_camera.invProjection * gl_Position;
    fViewCoord = viewCoord.xyz / viewCoord.w;
}