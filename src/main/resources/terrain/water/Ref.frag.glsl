#version 460 core
#pragma optionNV (unroll all)

#define MAX_LINEAR_ITERATION 8
#define MAX_TOTAL_ITERATION 16

#include "../GlobalCamera.glsl"
#include "../GBuffers.glsl"
#include "../SolidGBuffer.glsl"
#include "../WaterGBuffer.glsl"

uniform sampler2D usam_mainFbo_inputColor;

in vec2 fClipCoord;
in vec2 fTexCoord;
in mat3 fNormalMatrix;

layout(location = 0) out vec4 refUV;

vec3 gw_worldCoord;
vec3 gw_worldNormal;
float gw_depth;

vec3 gw_viewCoord;
vec3 gw_viewNormal;

vec3 sampleOrigin;
vec3 viewDir;

const float RIOR = 1.0 / 1.05;

bool findIntersection(sampler2D depthTexture, vec3 refDir, out vec3 intersectionOut) {
    float maxDist = refDir.x >= 0 ? (1 - fTexCoord.x) / refDir.x  : -fTexCoord.x / refDir.x;
    maxDist = min(maxDist, refDir.y < 0 ? (-fTexCoord.y / refDir.y) : ((1 - fTexCoord.y) / refDir.y));
    maxDist = min(maxDist, refDir.z < 0 ? (-gw_depth / refDir.z) : ((1 - gw_depth) / refDir.z));

    vec3 sampleEnd = sampleOrigin + refDir * maxDist;

    vec3 sampleDelta = sampleEnd.xyz - sampleOrigin.xyz;
    ivec2 sampleScreenPos = ivec2(sampleOrigin.xy * ubo_cameraDefault.screenSize.xy);
    ivec2 endPosScreenPos = ivec2(sampleEnd.xy * ubo_cameraDefault.screenSize.xy);

    ivec2 dp2 = abs(endPosScreenPos - sampleScreenPos);
    vec3 pixelDelta = sampleDelta / max(dp2.x, dp2.y);

    vec3 rayStart = sampleOrigin + pixelDelta;
    vec3 rayEnd = sampleEnd;

    vec3 rayMin = rayStart;
    vec3 rayMax = rayEnd;
    vec3 rayCur;

    float depth;

    vec3 linearDir = (rayMax - rayMin) / float(MAX_LINEAR_ITERATION + 1);

    float binarySearch = 0.0;

    for (int i = 0; i < MAX_TOTAL_ITERATION; i++) {
        binarySearch = float(i >= MAX_LINEAR_ITERATION);

        rayCur = mix(rayMin + linearDir, (rayMin + rayMax) * 0.5, binarySearch);
//        rayCur = (rayMin + rayMax) * 0.5;
//        rayCur = rayMin + linearDir;
        depth = textureLod(depthTexture, rayCur.xy, 0.0).x;

        if (depth < rayCur.z) {
            binarySearch = 1.0;
            rayMax = rayCur;
        } else {
            rayMin = rayCur;
        }
    }

//    for (int i = 0; i < 64; i++) {
//        rayCur = (rayMin + rayMax) * 0.5;
//        depth = texture(depthTexture, rayCur.xy).x;
//
//        if (depth < rayCur.z) {
//            rayMax = rayCur;
//        } else {
//            rayMin = rayCur;
//        }
//    }

    intersectionOut = rayCur;

    return depth == 1.0 || distance(rayCur.xy, rayEnd.xy) > 0.0001;
}

vec3 calcRefDirTS(vec3 refDirView) {
    vec4 refEndView = vec4(gw_viewCoord + refDirView * 1000, 1.0);

    vec4 reflEndClip = ubo_cameraDefault.projection * vec4(refEndView.xyz, 1);
    reflEndClip /= reflEndClip.w;

    vec3 refDir = normalize((reflEndClip.xyz - vec3(fClipCoord, gw_depth)).xyz);
    refDir.xy *= 0.5;

    return refDir;
}

void calcReflection() {
    vec3 adjNormal = normalize(gw_viewNormal + fNormalMatrix * vec3(0.0, 1.0, 0.0));
//    vec3 adjNormal = gw_viewNormal;
    vec3 reflectDirView = reflect(viewDir, adjNormal);
    vec3 refDir = calcRefDirTS(reflectDirView);

    refUV.xy = vec2(2.0);

    vec3 intersection;
    if (findIntersection(usam_gWaterDepth, refDir, intersection)) {
        if (distance(intersection.xy, fTexCoord) > 0.001) {
            refUV.xy = intersection.xy;
        } else {
            refUV.xy = vec2(-1.0);
        }
    }
}

void calcRefraction() {
    vec3 adjNormal = gw_viewNormal;
    vec3 refractDirView = refract(viewDir, adjNormal, RIOR);
    vec3 refDir = calcRefDirTS(refractDirView);

    refUV.zw = fTexCoord;
    vec3 intersection;

    if (findIntersection(usam_gSolidDepth, refDir, intersection)) {
//        vec2 offset = normalize(refDir.xy) * ubo_cameraDefault.screenSize.zw * 2.0;
//        GBufferData tempData;
//        gbuffer_unpack1(texture(usam_gSolidData1, intersection.xy), tempData);
//        refUV.zw = tempData.albedo.a > 0.0 ? intersection.xy : fTexCoord;

        refUV.zw = intersection.xy;
        vec2 adjCoord = fClipCoord;
        adjCoord.y = adjCoord.y * 0.5 - 0.5;
        adjCoord.x *= 0.5;
//        refUV.zw = mix(refUV.zw, fTexCoord, clamp(length(adjCoord) * 2.0 - 1.0, 0.0, 1.0));
    }
}

void main() {
    GBufferData gw_data;

    gbuffer_unpack1(texture(usam_gWaterData1, fTexCoord), gw_data);

    gw_worldCoord = gw_data.worldCoord;
    gw_worldNormal = gw_data.worldNormal;
    gw_depth = textureLod(usam_gWaterDepth, fTexCoord, 0.0).x;

    gw_viewCoord = (ubo_cameraDefault.modelView * vec4(gw_worldCoord, 1.0)).xyz;
    gw_viewNormal = fNormalMatrix * gw_worldNormal;

    sampleOrigin = vec3(fTexCoord, gw_depth);
    viewDir = normalize(gw_viewCoord);

    calcReflection();
    calcRefraction();
}