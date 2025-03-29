#version 460 core

#include "GlobalCamera.glsl"

layout(location = 0) in vec2 vPos;

out vec2 fClipCoord;
out vec2 fTexCoord;
out vec2 fTexCoordI;
#ifndef NO_NORMALS
out mat3 fNormalMatrix;
#endif

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fClipCoord = vPos;
    fTexCoord = vPos * 0.5 + 0.5;
    fTexCoordI = fTexCoord * ubo_camera.screenSize.xy;
#ifndef NO_NORMALS
    fNormalMatrix = transpose(inverse(mat3(ubo_camera.modelView)));
#endif
}