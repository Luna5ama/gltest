#version 460 core

#include "../GlobalCamera.glsl"
#include "../rtwsm/ShadowParams.glsl"

layout(location = 0) in vec2 vPos;

out vec2 fClipCoord;
out vec2 fTexCoord;
out vec2 fTexCoordI;
out mat3 fNormalMatrix;
out vec3 fSunViewDir;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fClipCoord = vPos;
    fTexCoord = vPos * 0.5 + 0.5;
    fTexCoordI = fTexCoord * ubo_camera.screenSize.xy;
    fNormalMatrix = transpose(inverse(mat3(ubo_camera.modelView)));
    fSunViewDir = normalize(fNormalMatrix * uShadowMap.lightDir);
}