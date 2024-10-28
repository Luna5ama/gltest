#version 460 core

#include "../ColorUtils.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"

uniform sampler2D usam_mainFbo_inputColor;

in vec2 fTexCoord;
in vec2 fTexCoordNear[4];
in mat3 fNormalMatrix;

out vec4 fragColor;

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

    vec3 coordC = gDataC.worldCoord * gDataC.worldNormal;
    vec3 coord1 = gData1.worldCoord * gData1.worldNormal;
    vec3 coord2 = gData2.worldCoord * gData2.worldNormal;
    vec3 coord3 = gData3.worldCoord * gData3.worldNormal;
    vec3 coord4 = gData4.worldCoord * gData4.worldNormal;

    float bd = max(length(gDataC.worldCoord), 0.001);
    vec4 ef = vec4(
        distance(coordC, coord1),
        distance(coordC, coord2),
        distance(coordC, coord3),
        distance(coordC, coord4)
    ) / sqrt(bd);

    ef = smoothstep(EDGE_THRESHOLD_MIN, EDGE_THRESHOLD_MAX, ef);

    float avg = (ef.x + ef.y + ef.z + ef.w) / 4.0;
    avg = smoothstep(0.0, 0.5, clamp(avg, 0.0, 1.0));

    //    fragColor = mix(fragColor, vec4(1.0, 1.0, 1.0, 1.0), avg);
    fragColor = vec4(avg, avg, avg, 1.0);
    fragColor.rgb *= vec3(0.0, 1.0, 0.0);
    //    fragColor.rgb = normalC;
}