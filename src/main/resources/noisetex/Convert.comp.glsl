#version 460 core

#extension GL_KHR_shader_subgroup_basic : enable

#include "/util/Math.glsl"

layout(local_size_x = 128) in;

layout(rgba32f) uniform restrict readonly image1D uimg_input;
layout(rgba32f) uniform restrict writeonly image1D uimg_output;

uniform uint size;
uniform uint stride;

void main() {
    vec4 v = imageLoad(uimg_input, int(gl_GlobalInvocationID.x));
    v = saturate(sin(v * 1.5));
    v = saturate(cos(v * 1.5));
    v = saturate(exp2(v * 0.5));
    v = saturate(log2(v + 0.001));
    v = saturate(inversesqrt(v * 1.5));
    v = saturate(sqrt(v * 0.5));
    v = saturate(rcp(v + 0.001));
    v = saturate(pow(v, vec4(114.0)));
    imageStore(uimg_output, int(gl_GlobalInvocationID.x), v);
}