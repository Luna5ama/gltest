#version 460 core

#include "../ColorUtils.glsl"

uniform sampler2D usam_mainFbo_inputColor;

uniform float uInvGamma = 1.0 / 2.2;

in vec2 fTexCoordI;

out vec4 fragColor;

void main() {
    fragColor = texelFetch(usam_mainFbo_inputColor, ivec2(fTexCoordI), 0);
    fragColor.rgb = clamp(fragColor.rgb, 0.0, 1.0);
    fragColor.rgb = pow(fragColor.rgb, vec3(uInvGamma));
}