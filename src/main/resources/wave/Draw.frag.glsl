#version 460 core

layout(binding = 0) uniform sampler2D inputTexture;

in vec2 fTexCoordI;

out vec4 fragColor;

void main() {
    vec2 v = texelFetch(inputTexture, ivec2(fTexCoordI), 0).rg;
    fragColor = vec4(v.r, v.r, v.r, 1.0);
    fragColor.rgb;
}