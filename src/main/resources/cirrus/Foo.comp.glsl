#version 460 core

layout(local_size_x = 128) in;

layout(rgba32f) uniform restrict readonly image1D uimg_input;
layout(rgba32f) uniform restrict writeonly image1D uimg_output;

void main() {
    vec4 sum = vec4(0.0);
    int start = int(gl_LocalInvocationID.x) - 16;
    int end = int(gl_LocalInvocationID.x) + 16;
    for (int i = -start; i <= end; i++) {
         sum += imageLoad(uimg_input, i);
    }
    sum /= 5.0;
    imageStore(uimg_output, int(gl_LocalInvocationID.x), sum);
}