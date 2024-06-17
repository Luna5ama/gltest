#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#include "../GlobalCamera.glsl"

uniform sampler2D usam_vBufferDepth;

in vec2 fTexCoordI;

void main() {
    ivec2 coord = ivec2(fTexCoordI);
    float linearDepth = -texelFetch(usam_vBufferDepth, coord, 0).x;
    float normalizedDepth = linearDepth * ubo_cameraDefault.projection[2][2] + ubo_cameraDefault.projection[3][2];
    normalizedDepth += 0.005;
    normalizedDepth /= -linearDepth;
    gl_FragDepth = min(normalizedDepth, 1.0);
}