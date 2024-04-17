#version 460 core

layout(binding = 0) uniform sampler1D usam_input;

in vec2 fTexCoord;

out vec4 fragColor;

void main() {
    #ifdef VERTICAL
    float coord = fTexCoord.y;
    #else
    float coord = fTexCoord.x;
    #endif
    fragColor = vec4(texture(usam_input, coord, 0).rrr, 1.0);
}