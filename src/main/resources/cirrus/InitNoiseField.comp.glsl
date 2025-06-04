#version 460 core

layout(local_size_x = 16, local_size_y = 16) in;

layout(rg16_snorm) uniform writeonly image2D uimg_noiseField;

uniform float uval_noiseZ;

uint xxhash32(uvec3 p)
{
    const uint PRIME32_2 = 2246822519U, PRIME32_3 = 3266489917U;
    const uint PRIME32_4 = 668265263U, PRIME32_5 = 374761393U;
    uint h32 =  p.z + PRIME32_5 + p.x*PRIME32_3;
    h32 = PRIME32_4*((h32 << 17) | (h32 >> (32 - 17)));
    h32 += p.y * PRIME32_3;
    h32 = PRIME32_4*((h32 << 17) | (h32 >> (32 - 17)));
    h32 = PRIME32_2*(h32^(h32 >> 15));
    h32 = PRIME32_3*(h32^(h32 >> 13));
    return h32^(h32 >> 16);
}

float rand_uintToFloat(uint v) {
    return float(v) * (1.0 / float(0xffffffffU));
}

float hash(uvec3 x) {
    return rand_uintToFloat(xxhash32(x)) * 2.0 - 1.0;
}

vec4 noised(in vec3 x) {
    uvec3 i = uvec3(abs(x));
    vec3 w = fract(x);

    #if INTERPOLANT==1
    // quintic interpolation
    vec3 u = w*w*w*(w*(w*6.0-15.0)+10.0);
    vec3 du = 30.0*w*w*(w*(w-2.0)+1.0);
    #else
    // cubic interpolation
    vec3 u = w*w*(3.0-2.0*w);
    vec3 du = 6.0*w*(1.0-w);
    #endif

    float a = hash(i + uvec3(0, 0, 0));
    float b = hash(i + uvec3(1, 0, 0));
    float c = hash(i + uvec3(0, 1, 0));
    float d = hash(i + uvec3(1, 1, 0));
    float e = hash(i + uvec3(0, 0, 1));
    float f = hash(i + uvec3(1, 0, 1));
    float g = hash(i + uvec3(0, 1, 1));
    float h = hash(i + uvec3(1, 1, 1));

    float k0 =   a;
    float k1 =   b - a;
    float k2 =   c - a;
    float k3 =   e - a;
    float k4 =   a - b - c + d;
    float k5 =   a - c - e + g;
    float k6 =   a - b - e + f;
    float k7 = - a + b + c - d + e - f - g + h;

    return vec4(k0 + k1*u.x + k2*u.y + k3*u.z + k4*u.x*u.y + k5*u.y*u.z + k6*u.z*u.x + k7*u.x*u.y*u.z,
        du * vec3(k1 + k4*u.y + k6*u.z + k7*u.y*u.z,
            k2 + k5*u.z + k4*u.x + k7*u.z*u.x,
            k3 + k6*u.x + k5*u.y + k7*u.x*u.y));
}

void main() {
    vec3 seed = vec3(0.0);
    seed.xy = vec2(gl_GlobalInvocationID.xy) / NOISE_FIELD_SIZE;
    seed.xy += 113.0;
    seed.z += 111.0;
    seed.z = uval_noiseZ;
    seed.xy *= 10.0;
    vec4 noise = noised(seed);
    float angle = noise.x * 6.28318530718;// 2 * PI
    float cosAngle = cos(angle);
    float sinAngle = sin(angle);
    vec2 noiseField = vec2(cosAngle, sinAngle);
    imageStore(uimg_noiseField, ivec2(gl_GlobalInvocationID.xy), vec4(noiseField, 0.0, 0.0));
}
