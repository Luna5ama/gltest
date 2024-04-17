#version 460 core

uniform mat4 uBoxMatries[6];

layout(location = 0) in vec4 vPos;

struct GeomData {
    vec3 gBoxCoord;
    int gFaceIndex;
};

out GeomData geomData;

void main() {
    geomData.gBoxCoord = vPos.xyz;
    geomData.gFaceIndex = int(vPos.w);
    gl_Position = uBoxMatries[geomData.gFaceIndex] * vec4(vPos.xyz, 1.0);
}