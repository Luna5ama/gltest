#version 460 core
#include "../GlobalCamera.glsl"

uniform sampler2D usam_avgLum;

layout(location = 0) in vec2 vPos;

out vec2 fTexCoord;
out vec2 fTexCoordI;
out float fExpMul;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoord = vPos * 0.5 + 0.5;
    fTexCoordI = fTexCoord * ubo_camera.screenSize.xy;
    fExpMul = texelFetch(usam_avgLum, ivec2(0, 0), 0).r;
}