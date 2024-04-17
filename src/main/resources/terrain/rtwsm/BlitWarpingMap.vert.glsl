#version 460 core

layout(location = 0) in vec2 vPos;

out vec2 fTexCoord;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoord = vPos * 0.5 + 0.5;
}