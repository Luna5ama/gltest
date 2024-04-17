#version 460 core

layout(binding = 0) uniform sampler2D usam_input;

uniform float uPower = 1.0;
uniform float uMul = 1.0;
uniform float uLod = 7.0;

in vec2 fTexCoord;

out vec4 fragColor;

void main() {
    float value = textureLod(usam_input, fTexCoord, uLod).r * uMul;
    value = pow(value, uPower);
    fragColor = vec4(vec3(value), 1.0);
}