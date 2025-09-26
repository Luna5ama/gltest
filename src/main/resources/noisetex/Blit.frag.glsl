#version 460 core

#include "/util/Dither.glsl"
#include "/util/Math.glsl"
#include "/util/Rand.glsl"

uniform float uval_screenSize;
uniform uint uval_frameCounter;

uniform sampler2D usam_normalizedImage;

out vec4 fragColor;

vec3 dither(vec3 x, float noiseV) {
    vec3 result = x;
    result *= 255.0;
    result = round(result + (noiseV - 0.5));
    result /= 255.0;
    return result;
}

void main() {
    vec2 screenPos = gl_FragCoord.xy / uval_screenSize;
    vec3 noiseV = texture(usam_normalizedImage, screenPos * 2.0).rgb;
    #ifdef SIGNED
    noiseV = noiseV * 0.5 + 0.5;
    #endif
    //        noiseV = 1.0 - noiseV;

    noiseV = dither(noiseV, rand_IGN(ivec2(gl_FragCoord.xy), uval_frameCounter));
    fragColor.rgb = noiseV;
    fragColor.a = 1.0;
}