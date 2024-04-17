#version 460 core

layout(binding = 0) uniform sampler2D usImpostorTex;

in vec3 fSurfacePos;
in vec3 fNormal;

out vec4 fragColor;

void main() {
    fragColor = vec4(1.0, 1.0, 1.0, 1.0);
    float diffuse = calcDiffuse(lightPos1) + calcDiffuse(lightPos2);
    float lightness = min(diffuse * 0.6 + 0.4, 1.0);
    fragColor.rgb *= lightness;
}
