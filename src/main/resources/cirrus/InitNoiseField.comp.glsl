#version 460 core

#include "/util/noise/GradientNoise.glsl"
#include "/util/noise/ValueNoise.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(rg16_snorm) uniform writeonly image2D uimg_noiseField;

uniform float uval_noiseZ;

void main() {
    vec3 seed = vec3(0.0);
    seed.xy = vec2(gl_GlobalInvocationID.xy) / NOISE_FIELD_SIZE;
    seed.xy += 113.0;
    seed.z += 111.0;
    seed.z = uval_noiseZ;
    seed.xy *= 10.0;
//    float noiseV = ValueNoise_3D_value(seed);
//    float noiseV = GradientNoise_3D_value(seed);
//    float angle = noiseV * 6.28318530718;// 2 * PI
//    float cosAngle = cos(angle);
//    float sinAngle = sin(angle);
//    vec2 noiseField = vec2(cosAngle, sinAngle);

//    vec2 noiseField = ValueNoise_3D_valueGrad(seed).yz;
    vec2 noiseField = GradientNoise_3D_valueGrad(seed).xy;
    noiseField = normalize(noiseField);
    imageStore(uimg_noiseField, ivec2(gl_GlobalInvocationID.xy), vec4(noiseField, 0.0, 0.0));
}
