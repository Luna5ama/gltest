#version 460 core

uniform samplerCube usam_skyboxCube;

in vec3 fViewDir;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(usam_skyboxCube, fViewDir).rgb, 1.0);
}