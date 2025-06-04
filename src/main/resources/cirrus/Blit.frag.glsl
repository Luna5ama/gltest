#version 460 core

#include "/util/Math.glsl"

uniform isampler2D usam_renderCount;

struct ParticleDataRaw {
    vec4 data1;
    uvec4 data2;
};

layout(std430) readonly buffer ParticleDataBuffer {
    uvec4 data;
    ParticleDataRaw particles[];
} ssbo_particleData;

out vec4 fragColor;

void main() {
    vec2 screenPos = gl_FragCoord.xy / textureSize(usam_renderCount, 0);
    vec2 dataMinMax = vec2(ssbo_particleData.data.xy);
    fragColor.rgb = linearStep(dataMinMax.x, dataMinMax.y, float(texture(usam_renderCount, screenPos).r)).rrr;
    fragColor.a = 1.0;
}