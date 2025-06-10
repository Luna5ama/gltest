#version 460 core

#include "/util/Math.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

struct ParticleDataRaw {
    vec4 data1;
    uvec4 data2;
};

layout(std430) readonly buffer ParticleDataBuffer {
    uvec4 data;
    ParticleDataRaw particles[];
} ssbo_particleData;

uniform isampler2D usam_renderCount;

layout(r16) uniform writeonly image2D uimg_normalizedImage;

void main() {
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);
    vec2 dataMinMax = vec2(ssbo_particleData.data.xy);
    float value = float(texelFetch(usam_renderCount, texelPos, 0).r);
    value = linearStep(dataMinMax.x, dataMinMax.y, value);
    imageStore(uimg_normalizedImage, texelPos, vec4(value));
}