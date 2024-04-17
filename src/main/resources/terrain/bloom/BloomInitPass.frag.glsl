#version 460 core
#pragma optionNV (unroll all)

#include "../ColorUtils.glsl"

uniform vec3 uParams;
#define pLumaMul uParams.x
#define pLumaAdd uParams.y
#define pLumaPow uParams.z

uniform sampler2D usam_mainFbo_inputColor;

in vec2 fTexCoordI;

out vec4 outputColor;

const ivec3 OFFSETS = ivec3(-1, 0, 1);

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);

    outputColor = texelFetch(usam_mainFbo_inputColor, intTexCoord, 0);
    float luma = linear2luminace(outputColor.rgb);

    outputColor.a *= pow(clamp(luma * pLumaMul + pLumaAdd, 0.0, 1.0), pLumaPow);
    outputColor *= 0.5;
}