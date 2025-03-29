#version 460 core

layout(binding = 0) uniform sampler2D inputTexture;
layout(binding = 2) uniform sampler2D brightTexture;
layout(location = 1) uniform float bloomAmount;
layout(location = 2) uniform float bloomPower;
layout(location = 3) uniform float subtractAmount;

in vec2 fTexCoord0;
in vec2[8] fTexCoords;

out vec4 outputColor;

const float INV_SUM = 2.0 / 12.0;

void main() {
    outputColor = texture(inputTexture, fTexCoords[0]) * 2.0;
    outputColor += texture(inputTexture, fTexCoords[1]) * 2.0;
    outputColor += texture(inputTexture, fTexCoords[2]) * 2.0;
    outputColor += texture(inputTexture, fTexCoords[3]) * 2.0;

    outputColor += texture(inputTexture, fTexCoords[4]);
    outputColor += texture(inputTexture, fTexCoords[5]);
    outputColor += texture(inputTexture, fTexCoords[6]);
    outputColor += texture(inputTexture, fTexCoords[7]);

    outputColor *= INV_SUM;

    outputColor.a -= texture(brightTexture, fTexCoord0).a * subtractAmount;
    outputColor.a *= bloomAmount;
    outputColor.a = clamp(outputColor.a, 0.0, 1.0);

    outputColor.a = pow(outputColor.a, bloomPower);
}
