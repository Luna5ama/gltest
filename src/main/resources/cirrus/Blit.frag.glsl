#version 460 core

#include "/util/Math.glsl"

uniform sampler2D usam_normalizedImage;
uniform sampler2D usam_tiledImage;

out vec4 fragColor;

void main() {
    fragColor.rgb = texelFetch(usam_tiledImage, ivec2(gl_FragCoord.xy), 0).rrr;
    fragColor.a = 1.0;
}