#version 460 core
#pragma optionNV (unroll all)

layout(early_fragment_tests) in;

#include "../ColorUtils.glsl"
#include "../GlobalCamera.glsl"
#include "../GBuffers.glsl"
#include "../rtwsm/ShadowParams.glsl"
#include "../LightParams.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Rand.glsl"

#include "../rtwsm/ShadowSamplers.glsl"
#include "../SolidGBuffer.glsl"
#include "Utils.glsl"

uniform sampler2D usam_blockNormalAtlas;
uniform sampler2D usam_blockSpecularAtlas;
uniform sampler2D usam_lightMap;

uniform vec3 originOffset;

in vec2 fClipCoord;
in vec2 fTexCoord;
in vec2 fTexCoordI;
in mat3 fNormalMatrix;
in vec3 fViewDir;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outParams;
layout(location = 2) out vec4 rt_mainFboOutput;

ivec2 intTexCoord = ivec2(fTexCoordI);

GBufferData gs_data;

vec3 gs_viewCoord;
vec3 gs_viewNormal;
vec3 gs_lightMapColor;
float gs_aoMul;

uint worldCoordRand[6];

float searchBlocker(vec3 shadowCoord, vec2 texelSize) {
    const float BLOCKER_SEARCH_LOD = 4.0;

    #define BLOCKER_SEARCH_N 8

    vec2 blockerSearchRange = texelSize * 8.0;
    uint idxB = ubo_cameraDefault.gFrameIndex * BLOCKER_SEARCH_N + worldCoordRand[1];

    float blockerDepth = 0.0f;
    int n = 0;

    #if BLOCKER_SEARCH_N == 1
    vec2 offset = r2Seq2(idxB) * blockerSearchRange - blockerSearchRange * 0.5;
    float depth = rtwsm_sampleShadowDepthOffset(usam_shadowDepth, shadowCoord.xy, BLOCKER_SEARCH_LOD, offset).r;
    blockerDepth += step(depth, shadowCoord.z) * depth;
    n += int(shadowCoord.z > depth);
    #else
    for (int i = 0; i < BLOCKER_SEARCH_N; i++) {
        vec2 offset = r2Seq2(idxB) * blockerSearchRange - blockerSearchRange * 0.5;
        float depth = rtwsm_sampleShadowDepthOffset(usam_shadowDepth, shadowCoord.xy, BLOCKER_SEARCH_LOD, offset).r;
        blockerDepth += step(depth, shadowCoord.z) * depth;
        n += int(shadowCoord.z > depth);
        idxB++;
    }
    blockerDepth /= float(n);
    #endif

    return n != 0 ? rtwsm_linearDepth(shadowCoord.z) - rtwsm_linearDepth(blockerDepth) : 0.0;
}

float calcShadow(vec3 lightViewNormal, float sssFactor) {
    vec3 coord = gs_viewCoord;

    float lightDirOffset = uShadowMap.boundSizeZ.y * 0.02;

    float minNormalOffset = 0.0001;
    float maxNormalOffset = clamp(length(gs_data.worldCoord) * 0.01, 0.01, 4.0);
    float normalOffsetFactor = max(dot(gs_data.worldNormal, uShadowMap.lightDir), 0.0) * 0.9 + 0.1;
    coord += lightViewNormal * mix(minNormalOffset, maxNormalOffset, normalOffsetFactor);

    vec4 worldCoord = ubo_cameraDefault.invModelView * vec4(coord, 1.0);
    worldCoord += uShadowMap.shadowWorldOffset;
    vec4 shadowCoordCS = uShadowMap.matrix * worldCoord;
    shadowCoordCS /= shadowCoordCS.w;

    vec3 shadowCoord = shadowCoordCS.xyz * 0.5 + 0.5;

    vec2 texelSize;
    shadowCoord.xy = rtwsm_warpTexCoordTexelSize(usam_warpingMap, shadowCoord.xy, texelSize);
    shadowCoord.z -= 0.000001 / (texelSize.x + texelSize.y);
    shadowCoord.z -= lightDirOffset;

    float blockerDistance = searchBlocker(shadowCoord, texelSize);
    float penumbraMult = 0.025 * blockerDistance;

    #define SAMPLE_N 16

    vec2 ssRange = texelSize * 2.0 * sssFactor;
    ssRange += texelSize * penumbraMult;

    float shadow = 0.0;
    uint idxSS = (ubo_cameraDefault.gFrameIndex + worldCoordRand[0]) * SAMPLE_N;
    uint idxSSS = (ubo_cameraDefault.gFrameIndex + worldCoordRand[1]) * SAMPLE_N;

    #if SAMPLE_N == 1
    vec2 offset = r2Seq2(idxSS) * ssRange - ssRange * 0.5;
    vec3 zOffsetShadowCoord = shadowCoord;
    shadow += rtwsm_sampleShadowDepthOffset(usam_shadowDepthComp, zOffsetShadowCoord, 0.0, offset);
    #else
    for (int i = 0; i < SAMPLE_N; i++) {
        vec2 offset = r2Seq2(idxSS) * ssRange - ssRange * 0.5;
        vec3 zOffsetShadowCoord = shadowCoord;
        shadow += rtwsm_sampleShadowDepthOffset(usam_shadowDepthComp, zOffsetShadowCoord, 0.0, offset);
        idxSS++;
        idxSSS++;
    }
    shadow /= SAMPLE_N;
    shadow = pow(shadow, 1.0 + sssFactor * 1.0);
    #endif

    return shadow;
}

void doStuff() {
    // Specular Mapping
    vec4 specMap = textureLod(usam_blockSpecularAtlas, gs_data.texCoord, 0.0);

    float perceptualSmoothness = specMap.r;
    float roughness = 1.0 - perceptualSmoothness;
    float alpha = roughness * roughness;

    float reflectance = specMap.g;
    reflectance = max(reflectance, 0.01);

    const float sssValueMin = 65.0 / 255.0;
    const float sssValueRangeInv = 1.0 / (1.0 - sssValueMin);
    float sssStrength = max(specMap.b - sssValueMin, 0.0) * sssValueRangeInv;

    float emissive = 1.0 - specMap.a;
//    emissive = 0.0;

    float sssFactor = sqrt(sssStrength / max(length(gs_data.worldCoord) / 64.0, 1.0));
//    sssFactor = 1.0;

    // TBN
    vec3 bitangent = cross(gs_data.worldNormal, gs_data.worldTangent);
    mat3 tbn = mat3(gs_data.worldTangent, bitangent, gs_data.worldNormal);

    // Normal Mapping
    vec4 normalMap = textureLod(usam_blockNormalAtlas, gs_data.texCoord, 0.0);
    vec3 tagentNormal = normalMap.rgb * 2.0 - 1.0;
    tagentNormal.z = sqrt(1.0 - dot(tagentNormal.xy, tagentNormal.xy));
    vec3 mappedNormal = normalize(tbn * tagentNormal);
    float materialAo = normalMap.b;

    vec3 viewDir = normalize(-gs_data.worldCoord);

    float blockLightMul = 4.0;
    float skyLightMul = 32.0;
    vec3 ambientLight = calcAmbient(usam_lightMap, mappedNormal, mappedNormal, alpha, gs_data.lightCoord, blockLightMul, skyLightMul);

    float sssBase = 0.1;
    float sssSheen = min(1.0 - dot(mappedNormal, viewDir), 1.0);
    sssSheen = sssSheen * 0.5;
    float sssValue = sssFactor * (sssBase + sssSheen);
    // Shadow
    float shadow = calcShadow(fNormalMatrix * gs_data.worldNormal, sssFactor);
    float diffuseIL = brdf_diffuse_orenNayar(uShadowMap.lightDir, mappedNormal, mappedNormal, alpha);
    float directLightIl = shadow * (diffuseIL + sssValue);

    vec3 v = vec3(0.0);
    v += materialAo * ambientLight;
    v += directLightIl * SUN_LIGHT_INTENSITY * SUN_LIGHT_COLOR;

    outColor.rgb = v;
    outColor.a = emissive;

    outParams.rgb = mappedNormal;
    outParams.a = roughness;

    float diffuseDL = brdf_diffuse_orenNayar(uShadowMap.lightDir, viewDir, mappedNormal, alpha);
    float directLightDL = shadow * (diffuseDL + sssValue);

    rt_mainFboOutput = vec4(0.0, 0.0, 0.0, 1.0);
    rt_mainFboOutput.rgb += directLightDL * SUN_LIGHT_INTENSITY * SUN_LIGHT_COLOR * gs_data.albedo.rgb;
    rt_mainFboOutput.rgb += emissive * EMISSIVE_INTENSITY * gs_data.albedo.rgb;
}

void main() {
    gbuffer_unpack1(texelFetch(usam_gSolidData1, intTexCoord, 0), gs_data);
    gbuffer_unpack2(texelFetch(usam_gSolidData2, intTexCoord, 0), gs_data);

    gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
    gs_viewNormal = normalize(fNormalMatrix * gs_data.worldNormal);
    gs_lightMapColor = texture(usam_lightMap, gs_data.lightCoord).rgb;
    gs_aoMul = float((gs_data.materialID >> 2u) & 1u);

    worldCoordRand[0] = uint(rand(gs_data.worldCoord.xyz) * 1024.0);
    worldCoordRand[1] = uint(rand(gs_data.worldCoord.xzy) * 1024.0);
    worldCoordRand[2] = uint(rand(gs_data.worldCoord.yxz) * 1024.0);
    worldCoordRand[3] = uint(rand(gs_data.worldCoord.yzx) * 1024.0);
    worldCoordRand[4] = uint(rand(gs_data.worldCoord.zxy) * 1024.0);
    worldCoordRand[5] = uint(rand(gs_data.worldCoord.zyx) * 1024.0);

    doStuff();
}