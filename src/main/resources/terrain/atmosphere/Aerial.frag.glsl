#version 460 core

#include "../GlobalCamera.glsl"
#include "../rtwsm/ShadowParams.glsl"

layout(binding = 5) uniform sampler2D inputTexture;

in vec2 fTexCoordI;
in vec3 fSunViewDir;

out vec4 fragColor;

uniform vec2 uCoefficients;
uniform vec3 uBetaScAir;
uniform vec3 uBetaScHaze;
//uniform vec3 uBetaExAir;
//uniform vec3 uBetaExHaze;
uniform vec2 uMieG;

#define uRayleightLengthCoeff uCoefficients.x
#define uMieLengthCoeff uCoefficients.y
#define uInScatterG uMieG.x
#define uHazeG uMieG.y

const vec3 E_SUN = SUN_LIGHT_COLOR * SUN_LIGHT_INTENSITY;

const float PI = 3.14159265359;

float phase(float cosTheta, float g) {
    float g2 = g * g;
    float cosTheta2 = cosTheta * cosTheta;
    float result = (3 * (1 - g2)) / (2 * (2 + g2));
    result *= (1 + cosTheta2) / pow(1 + g2 - 2 * g * cosTheta, 3.0 / 2.0);
    return result;
}

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    vec3 color = texelFetch(inputTexture, intTexCoord, 0).rgb;
    vec3 viewCoord = texelFetch(gViewCoord, intTexCoord, 0).rgb;
    float viewDist = length(viewCoord);

    float sAir = viewDist * uRayleightLengthCoeff;
    float sHaze = viewDist * uMieLengthCoeff;
    float cosTheta = dot(-normalize(viewCoord), fSunViewDir);

    color.rgb = extincitonFunc(sAir, sHaze);

    fragColor = vec4(color, 1.0);
}