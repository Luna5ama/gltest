#version 460 core

#include "GlobalCamera.glsl"

uniform sampler2D usam_mainFbo_inputColor;

in vec2 texCoord;
in vec4 posPos;

out vec4 outputColor;

const float FXAA_REDUCE_MIN = 1.0 / 128.0;
const float FXAA_REDUCE_MUL = 1.0 / 8.0;
const float FXAA_SPAN_MAX = 8.0;

void main() {
    vec4 colorC = texture(usam_mainFbo_inputColor, posPos.xy);
    vec3 colorNW = texture(usam_mainFbo_inputColor, posPos.zw).xyz;
    vec3 colorNE = textureOffset(usam_mainFbo_inputColor, posPos.zw, ivec2(1, 0)).xyz;
    vec3 colorSW = textureOffset(usam_mainFbo_inputColor, posPos.zw, ivec2(0, 1)).xyz;
    vec3 colorSE = textureOffset(usam_mainFbo_inputColor, posPos.zw, ivec2(1, 1)).xyz;

    vec3 colorM = colorC.xyz;

    vec3 luma = vec3(0.299, 0.587, 0.114);
    float lumaNW = dot(colorNW, luma);
    float lumaNE = dot(colorNE, luma);
    float lumaSW = dot(colorSW, luma);
    float lumaSE = dot(colorSE, luma);
    float lumaM = dot(colorM, luma);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir = vec2(
        -((lumaNW + lumaNE) - (lumaSW + lumaSE)),
        ((lumaNW + lumaSW) - (lumaNE + lumaSE))
    );

    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * FXAA_REDUCE_MUL), FXAA_REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = min(vec2(FXAA_SPAN_MAX), max(vec2(-FXAA_SPAN_MAX), dir * rcpDirMin)) * ubo_camera.screenSize.zw;

    vec4 colorA = 0.5 * (texture(usam_mainFbo_inputColor, posPos.xy + dir * vec2(1.0 / 3.0 - 0.5)) + texture(usam_mainFbo_inputColor, posPos.xy + dir * vec2(2.0 / 3.0 - 0.5)));
    vec4 colorB = colorA * 0.5 + 0.25 * (texture(usam_mainFbo_inputColor, posPos.xy + dir * vec2(0.0 / 3.0 - 0.5)) + texture(usam_mainFbo_inputColor, posPos.xy + dir * vec2(3.0 / 3.0 - 0.5)));

    float lumaB = dot(colorB.rgb, luma);

    outputColor = mix(colorB, colorA, min(step(lumaMin, lumaB) + step(lumaB, lumaMax), 1.0));
}
