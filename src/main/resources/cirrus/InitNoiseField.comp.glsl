#version 460 core

#include "/util/noise/GradientNoise.glsl"
#include "/util/noise/ValueNoise.glsl"
#include "/util/Math.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(rg16_snorm) uniform writeonly image2D uimg_noiseField;

uniform float uval_noiseZ;

vec2 angleToVec2(float angle) {
    return vec2(cos(angle), sin(angle));
}

void main() {
    vec3 seed = vec3(0.0);
    vec2 screenPos = (vec2(gl_GlobalInvocationID.xy) + 0.5) / vec2(NOISE_FIELD_SIZE);
    seed.xy = screenPos;
    seed.xy += 113.0;
    seed.z += 111.0;
    seed.z = uval_noiseZ;
    seed.xy *= 10.0;
//    float noiseV = ValueNoise_3D_value(seed);
    float angle = 0.0;

    float noiseV1 = GradientNoise_3D_value(seed * vec3(1.0, 1.0, 0.5));
    float curl1 = noiseV1 * PI * 8.0;
    angle += curl1 * pow(1.0 - screenPos.y, 12.0) * 2.0;

    float noiseV2 = GradientNoise_3D_value(seed * vec3(1.0, 1.0, 2.0));
    float curl2 = noiseV2 * PI * 8.0;
    angle += curl2 * pow(screenPos.y, 2.0) * 0.5;

    float noiseFlow1 = GradientNoise_3D_value(seed);
    float flow = noiseFlow1 * PI + PI * 0.4;
    angle += flow;

    vec2 noiseField = angleToVec2(angle);

//    noiseField += flow * screenPos.y;
//    noiseField = normalize(noiseField);

    imageStore(uimg_noiseField, ivec2(gl_GlobalInvocationID.xy), vec4(noiseField, 0.0, 0.0));
}
