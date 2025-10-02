#version 460 core

#extension GL_KHR_shader_subgroup_basic : enable

layout(local_size_x = 128) in;

layout(rgba32f) uniform restrict readonly image1D uimg_input;
layout(rgba32f) uniform restrict writeonly image1D uimg_output;

uniform uint size;
uniform uint stride;

void main() {
    uint lowLane = (gl_SubgroupInvocationID < (gl_SubgroupInvocationID ^ stride)) ? 1u : 0u;
    uint descending = ((gl_SubgroupInvocationID & size) == 0u) ? 0u : 1u;
    uint flip = (gl_SubgroupID & 1u);
    if (bool(lowLane ^ descending ^ flip)) {
        imageStore(uimg_output, int(gl_LocalInvocationID.x), vec4(0.0));
    }
}