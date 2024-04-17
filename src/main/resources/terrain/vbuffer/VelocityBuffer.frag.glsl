#version 460 core

#include "../GlobalCamera.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"

uniform sampler2D usam_prevDepthTex;

uniform vec3 uCameraDelta;

in vec2 fClipCoord;
in vec2 fTexCoord;
in vec2 fTexCoordI;

layout(location = 0) out vec2 outUvDiff;
layout(location = 1) out vec4 outWorldVelocity;

vec3 projToPrev(Camera currentCamera, Camera prevCamera, vec4 currentClipCoord) {
    vec4 currentViewCoord = currentCamera.invProjection * currentClipCoord;
    currentViewCoord /= currentViewCoord.w;

    vec4 currentWorldCoord = currentCamera.invModelView * currentViewCoord;
    vec4 prevWorldCoord = currentWorldCoord;
    prevWorldCoord.xyz -= uCameraDelta;

    vec4 prevClipCoord = prevCamera.combined * prevWorldCoord;
    prevClipCoord /= prevClipCoord.w;
    vec3 prevTexCoord = prevClipCoord.xyz * 0.5 + 0.5;

    return prevTexCoord;
}

float depthDiff(Camera camera, float a, float b) {
    return abs(global_linearizeDepth(camera, a) - global_linearizeDepth(camera, b));
}

void minDepthDiff(Camera currentCamera, Camera prevCamera, vec4 clipCoord) {
    vec3 prevTexCoord = projToPrev(currentCamera, prevCamera, clipCoord);
    float prevAcutalDepth = texture(usam_prevDepthTex, prevTexCoord.xy).x;
    outWorldVelocity.w = min(outWorldVelocity.w, depthDiff(prevCamera, prevTexCoord.z, prevAcutalDepth));
}

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    float currDepth = texelFetch(usam_gDepth, intTexCoord, 0).x;
    float currDepthN = currDepth * 2.0 - 1.0;
    vec4 currentViewCoord = ubo_cameraDefault.invProjection * vec4(fClipCoord, currDepthN, 1.0);
    currentViewCoord /= currentViewCoord.w;

    vec4 currentWorldCoord = ubo_cameraDefault.invModelView * currentViewCoord;

    vec4 prevWorldCoord = currentWorldCoord;
    prevWorldCoord.xyz -= uCameraDelta;

    vec4 prevViewCoord = ubo_prevCameraDefault.modelView * prevWorldCoord;
    vec4 prevClipCoord = ubo_prevCameraDefault.projection * prevViewCoord;
    prevClipCoord /= prevClipCoord.w;

    vec3 prevTexCoord = prevClipCoord.xyz * 0.5 + 0.5;
    vec2 texCoordDelta = fTexCoord - prevTexCoord.xy;

    outUvDiff = clamp(texCoordDelta, -1.0, 1.0);

    float prevAcutalDepth = texture(usam_prevDepthTex, prevTexCoord.xy).x;

    if (any(greaterThan(abs(prevClipCoord.xy), vec2(1.0)))) {
        outWorldVelocity.w = ubo_cameraDefault.planeZ.y * 2.0;
//        outWorldVelocity.w = 0.0;
    } else if (currDepth == 1.0 && prevAcutalDepth == 1.0) {
        outWorldVelocity.w = ubo_cameraDefault.planeZ.y * 2.0;
//        outWorldVelocity.w = 0.0;
    } else {
        outWorldVelocity.w = ubo_cameraDefault.planeZ.y * 2.0;

        vec2 cDelta = ubo_cameraDefault.screenSize.zw * 2.0;

        float cDepth1 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(-1, 0)).x * 2.0 - 1.0;
        float cDepth2 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(1, 0)).x * 2.0 - 1.0;
        float cDepth3 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(0, -1)).x * 2.0 - 1.0;
        float cDepth4 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(0, 1)).x * 2.0 - 1.0;

        float cDepth5 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(-1, -1)).x * 2.0 - 1.0;
        float cDepth6 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(1, -1)).x * 2.0 - 1.0;
        float cDepth7 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(-1, 1)).x * 2.0 - 1.0;
        float cDepth8 = texelFetchOffset(usam_gDepth, intTexCoord, 0, ivec2(1, 1)).x * 2.0 - 1.0;

        vec4 clipCoord0 = vec4(fClipCoord, currDepthN, 1.0);
        vec4 clipCoord1 = vec4(fClipCoord + vec2(-cDelta.x, 0.0), cDepth1, 1.0);
        vec4 clipCoord2 = vec4(fClipCoord + vec2(cDelta.x, 0.0), cDepth2, 1.0);
        vec4 clipCoord3 = vec4(fClipCoord + vec2(0.0, -cDelta.y), cDepth3, 1.0);
        vec4 clipCoord4 = vec4(fClipCoord + vec2(0.0, cDelta.y), cDepth4, 1.0);

        vec4 clipCoord5 = vec4(fClipCoord + vec2(-cDelta.x, -cDelta.y), cDepth5, 1.0);
        vec4 clipCoord6 = vec4(fClipCoord + vec2(cDelta.x, -cDelta.y), cDepth6, 1.0);
        vec4 clipCoord7 = vec4(fClipCoord + vec2(-cDelta.x, cDelta.y), cDepth7, 1.0);
        vec4 clipCoord8 = vec4(fClipCoord + vec2(cDelta.x, cDelta.y), cDepth8, 1.0);

        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord0);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord1);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord2);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord3);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord4);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord5);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord6);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord7);
        minDepthDiff(ubo_cameraJitter, ubo_prevCameraJitter, clipCoord8);

        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord0);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord1);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord2);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord3);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord4);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord5);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord6);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord7);
        minDepthDiff(ubo_prevCameraNoJitter, ubo_prevCameraNoJitter, clipCoord8);

        outWorldVelocity.w /= pow(max(global_linearizeDepth(ubo_cameraDefault, currDepth), 1.0), 0.4) * 0.2;
    }

    outWorldVelocity.xyz = prevWorldCoord.xyz - currentWorldCoord.xyz;
}
