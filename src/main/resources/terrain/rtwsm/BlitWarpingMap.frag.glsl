#version 460 core

layout(binding = 0) uniform sampler1DArray inputTexture;

uniform float uPrevCoeff;

in vec2 fTexCoord;

layout(location = 0) out vec4 out1;
layout(location = 1) out vec4 out2;

void main() {
    out1 = vec4(texture(inputTexture, vec2(fTexCoord.x, 0.0)).rgb, uPrevCoeff);
    out2 = vec4(texture(inputTexture, vec2(fTexCoord.x, 1.0)).rgb, uPrevCoeff);
}