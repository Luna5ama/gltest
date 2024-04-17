#version 460 core

uniform vec4 uval_inputUVRange;
uniform vec2 uval_inputTexelSize;
uniform float uval_radiusMul;

layout(location = 0) in vec2 vPos;

out vec2 fTexCoord;

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoord = (vPos * 0.5 + 0.5) * (uval_inputUVRange.zw - uval_inputUVRange.xy) + uval_inputUVRange.xy;
}