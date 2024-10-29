#version 460 core

#include "../GlobalCamera.glsl"
#include "../NDPacking.glsl"
#include "../utils/Math.glsl"

uniform usampler2D usam_velocity_ndTex;
uniform usampler2D usam_velocity_prevNDTex;

uniform vec3 uCameraDelta;

in vec2 fClipCoord;
in vec2 fTexCoord;

layout(location = 0) out vec2 outUvDiff;
layout(location = 1) out vec2 outWorldVelocity;

vec4 projPrevToCurr(Camera currentCamera, Camera prevCamera, vec3 prevClipCoord) {
    vec4 prevWorldCoord = prevCamera.invCombined * vec4(prevClipCoord, 1.0);
    prevWorldCoord /= prevWorldCoord.w;
    vec4 currWorldCoord = prevWorldCoord;
    currWorldCoord.xyz += uCameraDelta;
    vec4 currClipCoord = currentCamera.combined * currWorldCoord;
    return currClipCoord;
}

vec4 projPrevClipToCurrWorld(Camera currentCamera, Camera prevCamera, vec3 prevClipCoord) {
    vec4 prevWorldCoord = prevCamera.invCombined * vec4(prevClipCoord, 1.0);
    prevWorldCoord /= prevWorldCoord.w;
    vec4 currWorldCoord = prevWorldCoord;
    return currWorldCoord;
}

vec3 projClipToWorld(Camera currentCamera, vec3 clipCoord) {
    vec4 worldCoord = currentCamera.invCombined * vec4(clipCoord, 1.0);
    worldCoord /= worldCoord.w;
    return worldCoord.xyz;
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
    vec4 prevClipNDCCoord = prevClipCoord / prevClipCoord.w;

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

    outWorldVelocity.x = float(flag);

    {
        float ndpCurrDepth;
        vec3 ndpCurrNormal;
        ndpacking_unpack(texture(usam_velocity_ndTex, fTexCoord).xy, ndpCurrNormal, ndpCurrDepth);

        vec3 ndpCurrWorldCoord = projClipToWorld(ubo_cameraDefault, vec3(fClipCoord, ndpCurrDepth));
        ndpCurrWorldCoord -= uCameraDelta;

        vec4 prevNDPDepths = uintBitsToFloat(textureGather(usam_velocity_prevNDTex, fTexCoord.xy, 1));

        vec3 diff;
        float dotV = 0.0;
        diff = projClipToWorld(ubo_prevCameraDefault, vec3(fClipCoord.xy, prevNDPDepths.x)) - ndpCurrWorldCoord;
        dotV += dot(ndpCurrNormal, diff);
        diff = projClipToWorld(ubo_prevCameraDefault, vec3(fClipCoord.xy, prevNDPDepths.y)) - ndpCurrWorldCoord;
        dotV += dot(ndpCurrNormal, diff);
        diff = projClipToWorld(ubo_prevCameraDefault, vec3(fClipCoord.xy, prevNDPDepths.z)) - ndpCurrWorldCoord;
        dotV += dot(ndpCurrNormal, diff);
        diff = projClipToWorld(ubo_prevCameraDefault, vec3(fClipCoord.xy, prevNDPDepths.w)) - ndpCurrWorldCoord;
        dotV += dot(ndpCurrNormal, diff);

//        dotV /= 4.0;
        dotV = step(3.0, abs(dotV));

        outWorldVelocity.y = dotV;
    }
}
