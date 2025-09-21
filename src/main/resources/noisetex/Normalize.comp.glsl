#version 460 core

#include "/util/Math.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(std430) readonly buffer DataBuffer {
    vec4 data;
} ssbo_data;

layout(r32f) uniform readonly image2D uimg_noiseImage;
layout(r16) uniform writeonly image2D uimg_normalizedImage;

void main() {
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);
    vec2 dataMinMax = vec2(ssbo_data.data.xy);
    float value = imageLoad(uimg_noiseImage, texelPos).r;
    value = linearStep(dataMinMax.x, dataMinMax.y, value);
//    value = linearStep(0.0, 1.6, value);
    imageStore(uimg_normalizedImage, texelPos, vec4(value));
}