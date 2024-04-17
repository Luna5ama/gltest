#version 460 core

layout(location = 0) in vec2 vPos;

uniform vec4 uval_InputSize;

out vec2 fTexCoordI;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoordI = (vPos * 0.5 + 0.5) * uval_InputSize.xy + uval_InputSize.zw;
}