#version 460 core

layout(binding = 0) uniform sampler2D inputTexture;

in vec2[8] fTexCoords;

layout(location = 0) out vec4 outputColor;

const float INV_SUM = 1.0 / 12.0;

void main() {
    vec4 c0 = texture(inputTexture, fTexCoords[0]);
    vec4 c1 = texture(inputTexture, fTexCoords[1]);
    vec4 c2 = texture(inputTexture, fTexCoords[2]);
    vec4 c3 = texture(inputTexture, fTexCoords[3]);
    vec4 c4 = texture(inputTexture, fTexCoords[4]);
    vec4 c5 = texture(inputTexture, fTexCoords[5]);
    vec4 c6 = texture(inputTexture, fTexCoords[6]);
    vec4 c7 = texture(inputTexture, fTexCoords[7]);

    outputColor = vec4(c0.a, c1.a, c2.a, c3.a) * 2.0 + vec4(c4.a, c5.a, c6.a, c7.a);
    outputColor.xy = outputColor.xy + outputColor.zw;
    outputColor.a = outputColor.x + outputColor.y;
    outputColor.a *= INV_SUM;

    vec3 maxC = max(max(c0.rgb, c1.rgb), max(c2.rgb, c3.rgb));
    vec3 avgC = vec3(0.0);
    avgC += c0.rgb * 2.0;
    avgC += c1.rgb * 2.0;
    avgC += c2.rgb * 2.0;
    avgC += c3.rgb * 2.0;
    avgC += c4.rgb;
    avgC += c5.rgb;
    avgC += c6.rgb;
    avgC += c7.rgb;
    avgC *= INV_SUM;

    outputColor.rgb = avgC * 0.9 + maxC * 0.1;
}
