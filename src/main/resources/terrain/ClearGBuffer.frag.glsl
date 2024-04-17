#version 460 core

layout(location = 0) out vec3 outViewCoord;

in vec3 fViewCoord;

void main() {
    outViewCoord = fViewCoord;
}
