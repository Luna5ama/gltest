#version 460 core

#include "/util/Dither.glsl"
#include "/util/Math.glsl"
#include "/util/Rand.glsl"

uniform float uval_screenSize;
uniform uint uval_frameCounter;

uniform sampler2D usam_normalizedImage;

out vec4 fragColor;

float dither(float x, float noiseV) {
    float result = x;
    result *= 255.0;
    result = round(result + (noiseV - 0.5));
    result /= 255.0;
    return result;
}

void main() {
    vec2 screenPos = gl_FragCoord.xy / uval_screenSize;
    float noiseV = texture(usam_normalizedImage, screenPos * 1.0).r;
//        noiseV = 1.0 - noiseV;
//    noiseV = pow(noiseV, 2.0);
    noiseV = dither(noiseV, rand_IGN(ivec2(gl_FragCoord.xy), uval_frameCounter));
    fragColor.rgb = vec3(noiseV);
    fragColor.a = 1.0;
}