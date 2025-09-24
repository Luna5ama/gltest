#version 460 core

#include "/util/Math.glsl"
#include "/util/Hash.glsl"
#include "/util/Mat2.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(r32f) uniform writeonly image2D uimg_noiseImage;

uniform float uval_timeSecs;

vec2 hash22(uvec2 p) {
    return hash_uintToFloat(hash_22_q3(p));
}

vec2 hash12(int p) {
    return hash_uintToFloat(hash_22_q3(uvec2(p, _HASH_XXHASH32_PRIME32_3)));
}

vec4 worleyNoiseFs(vec2 x, int freq, mat2 rotMat) {
    ivec2 centerI = ivec2(floor(x));
    vec2 centerPos = fract(x);

    vec4 minDists = vec4(1.0);

    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            ivec2 offset = ivec2(x, y);
            //                ivec3 hashCoord = mod(centerI + offset, ivec3(freq));
            //                ivec3 hashCoord = (centerI + offset);
            ivec2 hashCoord = (centerI + offset) & (freq - 1);

            vec2 samplePos = hash22(uvec2(hashCoord));
            samplePos = samplePos * 2.0 - 1.0;
            samplePos = rotMat * samplePos;
            samplePos = samplePos * 0.5 + 0.5;
            samplePos += vec2(offset);

            vec2 diff = centerPos - samplePos;
            float currDist = dot(diff, diff);
            //            float currDist = length(diff);
            //                        float currDist = max2(abs(diff));
            //            float currDist = sum2(abs(diff));

            if (currDist < minDists.x) {
                minDists = minDists.xxyz;
                minDists.x = currDist;
            }
        }
    }

    return minDists;
}

float worleyNoise(vec2 x, int freq, mat2 rotMat) {
    vec4 v = worleyNoiseFs(x, freq, rotMat);
    //    return v.y;
    //    return (v.y - v.x);
    return v.x;
}



float smoothWorley(vec2 x, int freq,/* mat2 rotMat,*/ float w) {
    ivec2 centerI = ivec2(floor(x));
    vec2 centerPos = fract(x);

    float minDists = 16.0;

    int r = 12;

    for (int x = -r; x <= r; ++x) {
        for (int y = -r; y <= r; ++y) {
            ivec2 offset = ivec2(x, y);
            //                ivec3 hashCoord = mod(centerI + offset, ivec3(freq));
            //                ivec3 hashCoord = (centerI + offset);
            ivec2 hashCoord = (centerI + offset) & (freq - 1);

            vec2 samplePos = hash22(uvec2(hashCoord));
//            samplePos = samplePos * 2.0 - 1.0;
//            samplePos = rotMat * samplePos;
//            samplePos = samplePos * 0.5 + 0.5;
            samplePos += vec2(offset);

            vec2 diff = centerPos - samplePos;
                        float currDist = dot(diff, diff);
//            float currDist = length(diff);
//                                    float currDist = max2(abs(diff));
//                        float currDist = sum2(abs(diff));


            float h = smoothstep(-1.0, 1.0, (minDists - currDist) / w);
            minDists = mix(minDists, currDist, h) - h * (1.0 - h) * w / (1.0 + 3.0 * w);// distance
        }
    }

    return minDists;
}

#define rotMatGR     mat2(-0.047220096, 0.998884509, -0.998884509, -0.047220096)
#define rotMatGA     mat2(-0.737368878, 0.675490294, -0.675490294, -0.737368878)
#define mat2id      mat2(1.0, 0.0, 0.0, 1.0)

void main() {
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);

    vec2 noisePos = (vec2(texelPos) + vec2(0.5)) / NOISE_TEX_SIZE;
    noisePos = noisePos * 2.0 - 1.0;

    int freq = 1;


    float v = 0.0;
    float amp = 1.0;
//    mat2 rotMat = mat2id;

    float m = 0.1;

    int k = 0;
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j < 4; ++j) {
            float fj = float(j);
            float mm = fj * 2.0 + 1.0;
            vec2 randOffset = hash12(k++) * 2.0 - 1.0;
            noisePos = (noisePos + randOffset);
            float l = hash12(k++).x + 1.0;
            v += amp * l * smoothWorley(noisePos * float(freq), freq * 2, m * mm);
        }
        amp *= 0.5;
        m = m * 1.8;
        freq *= 2;
    }

    imageStore(uimg_noiseImage, texelPos, vec4(v));
}