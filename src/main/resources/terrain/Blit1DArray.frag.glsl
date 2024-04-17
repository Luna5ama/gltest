#version 460 core

uniform sampler1DArray usam_input;

uniform float uLayer;

in vec2 fTexCoord;

out vec4 fragColor;

void main() {
    #ifdef VERTICAL
    float coord = fTexCoord.y;
    #else
    float coord = fTexCoord.x;
    #endif
    float v = texture(usam_input, vec2(coord, uLayer)).r;
    fragColor = vec4(vec3(max(v, 0.0), max(-v, 0.0), 0.0), 1.0);
}