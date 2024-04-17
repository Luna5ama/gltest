#version 460 core

#include "../GlobalCamera.glsl"
#include "../rtwsm/ShadowParams.glsl"

in vec2 fPos;
in vec2 fTexCoord;
in vec2 fTexCoordI;

out vec4 fragColor;

void main() {
    fragColor = vec4(SUN_LIGHT_INTENSITY * SUN_LIGHT_COLOR, 4.0);

    vec4 camVec = ubo_cameraDefault.invCombined * vec4(fPos, 0.0, 1.0);
    camVec.xyz *= 1.0 / camVec.w;

    vec3 camNormal = normalize(camVec.xyz);
    float prod = max(dot(camNormal, uShadowMap.lightDir), 0.0);
    fragColor *= pow(smoothstep(0.995, 1.0, prod), 2.0);
}