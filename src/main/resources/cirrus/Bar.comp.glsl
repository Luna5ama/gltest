#version 460 core

layout(local_size_x = 128) in;

layout(rgba32f) uniform restrict readonly image1D uimg_input;
layout(rgba32f) uniform restrict writeonly image1D uimg_output;

void main() {
    vec4 sum = vec4(0.0);
    for (uint i = 0; i < 33; i++) {
        sum += imageLoad(uimg_input, int(gl_LocalInvocationID.x + i) - 16);
    }
    sum /= 5.0;
    imageStore(uimg_output, int(gl_LocalInvocationID.x), sum);
}