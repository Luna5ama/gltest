#version 460 core

#include "../ColorUtils.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"
#include "../utils/Math.glsl"

in vec2 fTexCoord;
in vec2 fTexCoordNear[4];
in mat3 fNormalMatrix;

out vec4 fragColor;

#define EDGE_THRESHOLD_MIN 0.1
#define EDGE_THRESHOLD_MAX 0.5

void main() {
    GBufferData gDataC;
    GBufferData gData1;
    GBufferData gData2;
    GBufferData gData3;
    GBufferData gData4;

    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gDataC);
    gbuffer_unpack1(texture(usam_gData1, fTexCoordNear[0]), gData1);
    gbuffer_unpack1(texture(usam_gData1, fTexCoordNear[1]), gData2);
    gbuffer_unpack1(texture(usam_gData1, fTexCoordNear[2]), gData3);
    gbuffer_unpack1(texture(usam_gData1, fTexCoordNear[3]), gData4);

    vec3 diff1 = gData1.worldCoord - gDataC.worldCoord;
    vec3 diff2 = gData2.worldCoord - gDataC.worldCoord;
    vec3 diff3 = gData3.worldCoord - gDataC.worldCoord;
    vec3 diff4 = gData4.worldCoord - gDataC.worldCoord;

    float dotV = 0.0;
    dotV += dot(gDataC.worldNormal, diff1);
    dotV += dot(gDataC.worldNormal, diff2);
    dotV += dot(gDataC.worldNormal, diff3);
    dotV += dot(gDataC.worldNormal, diff4);
    dotV = abs(dotV);
    dotV = step(dotV, 0.0);

    float avg = 1.0 - dotV;

    fragColor = vec4(avg, avg, avg, 1.0);
    fragColor.rgb *= vec3(0.0, 1.0, 0.0);
}