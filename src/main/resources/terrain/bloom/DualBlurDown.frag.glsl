#version 460 core

layout(binding = 0) uniform sampler2D inputTexture;

in vec2 fTexCoord0;
in vec2[4] fTexCoords;

out vec4 outputColor;

const float INV_SUM = 1.0 / 8.0;

void main() {
    vec4 c0 = texture(inputTexture, fTexCoord0);

    vec4 c1 = texture(inputTexture, fTexCoords[0]);
    vec4 c2 = texture(inputTexture, fTexCoords[1]);
    vec4 c3 = texture(inputTexture, fTexCoords[2]);
    vec4 c4 = texture(inputTexture, fTexCoords[3]);

    vec4 avgC = (c0 * 4.0 + c1 + c2 + c3 + c4) * INV_SUM;
    vec4 maxC = max(max(max(c0, c1), max(c2, c3)), c4);

    outputColor = avgC * 0.9 + maxC * 0.1;
}
