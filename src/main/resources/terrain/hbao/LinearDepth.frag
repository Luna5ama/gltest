#version 460 core

#include "../GlobalCamera.glsl"
#include "../GenericGBuffer.glsl"

in vec2 fTexCoord;

layout(location = 0) out float rt_linearDepth;

vec3 depthToView(vec2 texCoord, float depth, mat4 projInv) {
    vec4 ndc = vec4(texCoord, depth, 1) * 2 - 1;
    vec4 viewPos = projInv * ndc;
    return viewPos.xyz / viewPos.w;
}

void main() {
    float depth = texelFetch(usam_gDepth, ivec2(gl_FragCoord.xy), 0).r;
    rt_linearDepth = depthToView(fTexCoord, depth, ubo_cameraDefault.invProjection).z;
}
