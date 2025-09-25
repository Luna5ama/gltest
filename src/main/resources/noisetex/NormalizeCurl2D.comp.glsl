#version 460 core

#include "/util/Math.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(std430) readonly buffer DataBuffer {
    vec4 data;
} ssbo_data;

layout(rg32f) uniform readonly image2D uimg_noiseImage;
layout(rg16_snorm) uniform writeonly image2D uimg_normalizedImage;

void main() {
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);
    vec2 value = imageLoad(uimg_noiseImage, texelPos).rg;
    value = clamp(value / ssbo_data.data.w, -1.0, 1.0);
    imageStore(uimg_normalizedImage, texelPos, vec4(value, value));
}