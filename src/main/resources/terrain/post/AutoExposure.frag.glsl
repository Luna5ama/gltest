#version 460 core

#include "../ColorUtils.glsl"

uniform sampler2D usam_mainFbo_inputColor;

uniform vec4 uParams;
#define pTargetLuma uParams.x
#define pMinInc uParams.y
#define pMaxInc uParams.z
#define pExposureMul uParams.w

in vec2 fTexCoordI;
in float avgLum;

out vec4 fragColor;

void main() {
    fragColor = texelFetch(usam_mainFbo_inputColor, ivec2(fTexCoordI), 0);
    fragColor.rgb = max(fragColor.rgb, 0.0001);
    fragColor.rgb *= clamp(pTargetLuma / (avgLum + 0.0001), pMinInc, pMaxInc);
    fragColor.rgb *= pExposureMul;
}