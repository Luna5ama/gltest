#version 460 core

layout(binding = 0) uniform sampler2D usam_input;

uniform vec4 uval_inputUVRange;
uniform vec2 uval_inputTexelSize;
uniform float uval_radiusMul;

in vec2 fTexCoord;

out vec4 outputValue;

vec4 sampleValue(vec2 coord) {
    return texture(usam_input, clamp(coord, uval_inputUVRange.xy, uval_inputUVRange.zw));
}

subroutine void SampleDirection();

layout(location = 0) subroutine uniform SampleDirection usubroutine_SampleDirection;

subroutine(SampleDirection) void sampleH() {
    CODE_H;
}

subroutine(SampleDirection) void sampleV() {
    CODE_V;
}

void main() {
    outputValue = vec4(0.0);
    usubroutine_SampleDirection();
}