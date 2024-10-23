#version 460 core

#include "../GlobalCamera.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"

uniform sampler2D usam_prevDepthTex;

uniform vec3 uCameraDelta;

in vec2 fClipCoord;
in vec2 fTexCoord;

layout(location = 0) out vec2 outUvDiff;
layout(location = 1) out float outWorldVelocity;

vec4 projPrevToCurr(Camera currentCamera, Camera prevCamera, vec3 prevTexCoord) {
    vec4 prevClipCoord = vec4(prevTexCoord * 2.0 - 1.0, 1.0);
    vec4 prevWorldCoord = prevCamera.invCombined * prevClipCoord;
    prevWorldCoord /= prevWorldCoord.w;
    vec4 currWorldCoord = prevWorldCoord;
    currWorldCoord.xyz += uCameraDelta;
    vec4 currClipCoord = currentCamera.combined * currWorldCoord;
    return currClipCoord;
}

void main() {
    ivec2 intTexCoord = ivec2(gl_FragCoord.xy);
    float currDepth = texelFetch(usam_gDepth, intTexCoord, 0).x;
    float currDepthN = currDepth * 2.0 - 1.0;
    vec4 currentViewCoord = ubo_cameraDefault.invProjection * vec4(fClipCoord, currDepthN, 1.0);
    currentViewCoord /= currentViewCoord.w;

    vec4 currentWorldCoord = ubo_cameraDefault.invModelView * currentViewCoord;
    vec4 prevWorldCoord = currentWorldCoord;
    prevWorldCoord.xyz -= uCameraDelta;

    vec4 prevClipCoord = ubo_prevCameraDefault.combined * prevWorldCoord;
    vec4 prevClipNDCCoord = prevClipCoord;
    prevClipNDCCoord /= prevClipNDCCoord.w;

    vec3 prevTexCoord = prevClipNDCCoord.xyz * 0.5 + 0.5;
    vec2 texCoordDelta = fTexCoord - prevTexCoord.xy;

    outUvDiff = clamp(texCoordDelta, -1.0, 1.0);

    float prevDepth = texelFetch(usam_prevDepthTex, intTexCoord, 0).x;
    vec4 prevProjCoord = projPrevToCurr(ubo_cameraDefault, ubo_prevCameraDefault, vec3(fTexCoord, prevDepth));

    uint flag = 0u;
    flag |= uint(currDepth != 1.0) & uint(any(greaterThan(abs(prevClipCoord.xyz), vec3(prevClipCoord.w))));
    flag |= uint(prevDepth != 1.0) & uint(any(greaterThan(abs(prevProjCoord.xyz), vec3(prevProjCoord.w))));

    outWorldVelocity = float(flag);
}
