#version 460 core

#include "../GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec3 fViewDir;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    vec4 worldCoord = ubo_camera.invCombined * vec4(vPos, 1.0, 1.0);
    worldCoord /= worldCoord.w;
    fViewDir = normalize(worldCoord.xyz);
}