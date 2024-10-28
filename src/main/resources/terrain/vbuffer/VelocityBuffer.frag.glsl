#version 460 core

#include "../GlobalCamera.glsl"
#include "../NDPacking.glsl"

uniform usampler2D usam_velocity_ndTex;
uniform usampler2D usam_velocity_prevNDTex;

uniform vec3 uCameraDelta;

in vec2 fClipCoord;
in vec2 fTexCoord;

layout(location = 0) out vec2 outUvDiff;
layout(location = 1) out float outWorldVelocity;

vec4 projPrevToCurr(Camera currentCamera, Camera prevCamera, vec3 prevClipCoord) {
    vec4 prevWorldCoord = prevCamera.invCombined * vec4(prevClipCoord, 1.0);
    prevWorldCoord /= prevWorldCoord.w;
    vec4 currWorldCoord = prevWorldCoord;
    currWorldCoord.xyz += uCameraDelta;
    vec4 currClipCoord = currentCamera.combined * currWorldCoord;
    return currClipCoord;
}

void main() {
    ivec2 intTexCoord = ivec2(gl_FragCoord.xy);
    float currDepth;
    vec3 currNormal;
    ndpacking_unpack(texelFetch(usam_velocity_ndTex, intTexCoord, 0).xy, currNormal, currDepth);
    currDepth = max(currDepth, 0.0000001);

    vec4 currWorldCoord = ubo_cameraDefault.invCombined * vec4(fClipCoord, currDepth, 1.0);
    currWorldCoord /= currWorldCoord.w;
    vec4 prevWorldCoord = currWorldCoord;
    prevWorldCoord.xyz -= uCameraDelta;

    vec4 prevClipCoord = ubo_prevCameraDefault.combined * prevWorldCoord;
    vec4 prevClipNDCCoord = prevClipCoord;
    prevClipNDCCoord /= prevClipNDCCoord.w;

    vec3 prevTexCoord = prevClipNDCCoord.xyz * 0.5 + 0.5;
    vec2 texCoordDelta = fTexCoord - prevTexCoord.xy;

    outUvDiff = clamp(texCoordDelta, -1.0, 1.0);

    float prevDepth;
    vec3 prevNormal;
    ndpacking_unpack(texelFetch(usam_velocity_prevNDTex, intTexCoord, 0).xy, prevNormal, prevDepth);

    vec4 prevProjCoord = projPrevToCurr(ubo_cameraDefault, ubo_prevCameraDefault, vec3(fClipCoord, prevDepth));

    uint flag = 0u;
    flag |= uint(currDepth != 0.0) & uint(any(greaterThan(abs(prevClipCoord.xyz), vec3(prevClipCoord.w))));
    flag |= uint(prevDepth != 0.0) & uint(any(greaterThan(abs(prevProjCoord.xyz), vec3(prevProjCoord.w))));

    outWorldVelocity = float(flag);
}
