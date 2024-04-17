#version 460 core

layout(location = 0) uniform vec2 halfTexel;

layout(location = 0) in vec2 vPos;

out vec2 fTexCoord0;
out vec2[4] fTexCoords;

const vec2[4] OFFSETS = vec2[](
    vec2(-1.0, -1.0),
    vec2(1.0, -1.0),
    vec2(-1.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    gl_Position = vec4(vPos, 1.0, 1.0);
    fTexCoord0 = vPos * 0.5 + 0.5;

    fTexCoords[0] = fTexCoord0 + halfTexel * OFFSETS[0];
    fTexCoords[1] = fTexCoord0 + halfTexel * OFFSETS[1];
    fTexCoords[2] = fTexCoord0 + halfTexel * OFFSETS[2];
    fTexCoords[3] = fTexCoord0 + halfTexel * OFFSETS[3];
}
