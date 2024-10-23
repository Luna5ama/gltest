#version 460 core

#include "../GlobalCamera.glsl"
#include "VelocityBufferSamplers.glsl"

uniform vec3 uWeight;
uniform float uDistanceThreshold;

in vec2 fTexCoord;
in vec2 fTexCoordI;

out vec4 fragColor;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);

//    float centerValue = texelFetch(inputTexture, intTexCoord, 0).z;
//    float sampleValue = centerValue;
//    sampleValue = min(sampleValue, texelFetch(inputTexture, intTexCoord + ivec2(-1, 0), 0).z);
//    sampleValue = min(sampleValue, texelFetch(inputTexture, intTexCoord + ivec2(1, 0), 0).z);
//    sampleValue = min(sampleValue, texelFetch(inputTexture, intTexCoord + ivec2(0, -1), 0).z);
//    sampleValue = min(sampleValue, texelFetch(inputTexture, intTexCoord + ivec2(0, 1), 0).z);

//    float newValue = step(uDistanceThreshold, sampleValue);
//    float newValue = -centerValue >= planeZ.y ? uWeight.x : uWeight.y;

//    fragColor = vec4(newValue, 0.0, 0.0, uWeight.z);
    float v = texelFetch(usam_velocity_worldVelocity, intTexCoord, 0).r;
    v = smoothstep(0.2, 1.0, v) * uWeight.x;

    fragColor = vec4(v, 0.0, 0.0, uWeight.z);
}