#version 460 core

#include "../GlobalCamera.glsl"
#include "../GenericGBuffer.glsl"

in vec2 fTexCoord;

layout(location = 0) out float rt_linearDepth;

void main() {
    float depth = texelFetch(usam_gDepth, ivec2(gl_FragCoord.xy), 0).r;
    rt_linearDepth = depth == 0.0 ? 0.0 : global_linearizeDepth(ubo_cameraDefault, depth);
}
