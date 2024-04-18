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

#include "../SolidGBuffer.glsl"
#include "../rtwsm/ShadowSamplers.glsl"

uniform sampler2D usam_blockNormalAtlas;
uniform sampler2D usam_blockSpecularAtlas;
uniform sampler2D usam_lightMap;
uniform sampler2D usam_hbao;

uniform vec3 originOffset;

in vec2 fClipCoord;
in vec2 fTexCoord;
in vec2 fTexCoordI;
in mat3 fNormalMatrix;
in vec3 fViewDir;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outParams;

ivec2 intTexCoord = ivec2(fTexCoordI);

GBufferData gs_data;

vec3 gs_viewCoord;
vec3 gs_viewNormal;
vec3 gs_lightMapColor;
float gs_aoMul;

uint worldCoordRand[6];

float tFactor;

float searchBlocker(vec2 shadowCoord, vec2 texelSize) {
    const float BLOCKER_SEARCH_LOD = 6.0;

    #define BLOCKER_SEARCH_N 4

    vec2 blockerSearchRange = texelSize * 4096.0;
    uint idxB = ubo_cameraDefault.gFrameIndex * BLOCKER_SEARCH_N + worldCoordRand[1];

    #if BLOCKER_SEARCH_N == 1
    vec2 offset = r2Seq2(idxB) * blockerSearchRange - blockerSearchRange * 0.5;
    float blockerDepth = rtwsm_sampleShadowDepthOffset(usam_shadowDepth, shadowCoord, BLOCKER_SEARCH_LOD, offset).r;
    #else
    float blockerDepth = 0.0;
    for (int i = 0; i < BLOCKER_SEARCH_N; i++) {
        vec2 offset = r2Seq2(idxB) * blockerSearchRange - blockerSearchRange * 0.5;
        blockerDepth += rtwsm_sampleShadowDepthOffset(usam_shadowDepth, shadowCoord, BLOCKER_SEARCH_LOD, offset).r;
        idxB++;
    }
    blockerDepth /= float(BLOCKER_SEARCH_N);
    #endif

    return blockerDepth;
}

float calcShadow(vec3 lightViewNormal) {
    vec3 coord = gs_viewCoord;

    float SSS_RANGE = max(-dot(gs_data.worldNormal, uShadowMap.lightDir), 0.2) * mix(0.3, 2.0, gs_aoMul);

    float lightDirOffset = uShadowMap.boundSizeZ.y * mix(0.02, SSS_RANGE, tFactor);

    float minNormalOffset = 0.001;
    float maxNormalOffset = clamp(length(gs_data.worldCoord) * 0.05, 0.5, 2.0);
    maxNormalOffset *= 0.1 + 0.9 * (1.0 - abs(dot(normalize(-gs_data.worldCoord), gs_data.worldNormal)));
    float normalOffsetFactor = max(dot(gs_data.worldNormal, uShadowMap.lightDir), 0.0) * 0.95 + 0.05;
    coord += lightViewNormal * mix(minNormalOffset, maxNormalOffset, normalOffsetFactor);

    vec4 worldCoord = ubo_cameraDefault.invModelView * vec4(coord, 1.0);
    worldCoord += uShadowMap.shadowWorldOffset;
    vec4 shadowCoordCS = uShadowMap.matrix * worldCoord;
    shadowCoordCS /= shadowCoordCS.w;

    vec3 shadowCoord = shadowCoordCS.xyz * 0.5 + 0.5;

    vec2 texelSize;
    shadowCoord.xy = rtwsm_warpTexCoordTexelSize(usam_warpingMap, shadowCoord.xy, texelSize);

    float blockerDepth = searchBlocker(shadowCoord.xy, texelSize);
    float blockerDistance = rtwsm_linearDepth(shadowCoord.z) - rtwsm_linearDepth(blockerDepth);
    float penumbraMult = 256.0 * blockerDistance;

    #define SAMPLE_N 128

    vec2 ssRange = mix(
        texelSize * 64.0 + texelSize * penumbraMult,
        texelSize * 2048.0 / max(length(gs_data.worldCoord), 1.0),
        tFactor
    );

    float shadow = 0.0;
    uint idxSS = (ubo_cameraDefault.gFrameIndex + worldCoordRand[0]) * SAMPLE_N;
    uint idxSSS = (ubo_cameraDefault.gFrameIndex + worldCoordRand[1]) * SAMPLE_N;

    #if SAMPLE_N == 1
    vec2 offset = r2Seq2(idxSS) * ssRange - ssRange * 0.5;
    vec3 zOffsetShadowCoord = shadowCoord;
    zOffsetShadowCoord.z -= r2Seq1(idxSSS) * lightDirOffset;
    shadow += rtwsm_sampleShadowDepthOffset(usam_shadowDepthComp, zOffsetShadowCoord, 0.0, offset);
    #else
    for (int i = 0; i < SAMPLE_N; i++) {
        vec2 offset = r2Seq2(idxSS) * ssRange - ssRange * 0.5;
        vec3 zOffsetShadowCoord = shadowCoord;
        zOffsetShadowCoord.z -= r2Seq1(idxSSS) * lightDirOffset;
        shadow += rtwsm_sampleShadowDepthOffset(usam_shadowDepthComp, zOffsetShadowCoord, 0.0, offset);
        idxSS++;
        idxSSS++;
    }
    shadow /= float(SAMPLE_N);
    #endif

    return shadow;
}

const float EMISSIVE_INTENSITY = 20.0;
const float M_PI = 3.14159265359;

//float ggxDistribution(float NdotH, float roughness) {
//    float a2 = roughness * roughness;
//    float NdotH2 = NdotH * NdotH;
//    float d = (NdotH2 * (a2 - 1.0) + 1.0);
//    return a2 / (d * d * M_PI);
//}
//
//float geometrySmith(float NdotL, float NdotV, float roughness) {
//    float r = (roughness + 1.0);
//    float k = (r * r) / 8.0;
//    float ggx2  = NdotV / (NdotV * (1.0 - k) + k);
//    float ggx1  = NdotL / (NdotL * (1.0 - k) + k);
//    return ggx1 * ggx2;
//}
//
//vec3 fresnelSchlick(vec3 f0, float lDotH) {
//    return f0 + (vec3(1.0f) - f0) * pow(1.0f - lDotH, 5.0f);
//}
//
//vec3 ggxDirect(vec3 N, vec3 V, vec3 albedo, vec3 spec, float reflectance, float rough, vec3 lightIntensity, vec3 L, float shadowMult) {
//    float NdotL = max(dot(N, L), 0.00001);
//
//    vec3 H = normalize(V + L);
//    float NdotH = max(dot(N, H), 0.00001);
//    float LdotH = max(dot(L, H), 0.00001);
//    float NdotV = max(dot(N, V), 0.00001);
//
//    float D = ggxDistribution(NdotH, rough);
//    float G = geometrySmith(NdotL, NdotV, rough);
//    vec3 F = fresnelSchlick(spec, LdotH);
//
//    vec3 Ks = F;
//    vec3 Kd = vec3(1.0) - Ks;
//    Kd *= 1.0 - reflectance;
//
//    vec3 ggxTerm = D * G * F / (4 * NdotV * NdotL);
//
//    return shadowMult * lightIntensity * (ggxTerm * NdotL + Kd * albedo * NdotL / M_PI);
//}

float ggxNormalDistribution(float NdotH, float roughness) {
    float a2 = roughness * roughness;
    float d = ((NdotH * a2 - NdotH) * NdotH + 1);
    return a2 / (d * d * M_PI);
}

float ggxSchlickMaskingTerm(float NdotL, float NdotV, float roughness) {
    // Karis notes they use alpha / 2 (or roughness^2 / 2)
    float k = roughness*roughness / 2;

    // Compute G(v) and G(l).  These equations directly from Schlick 1994
    //     (Though note, Schlick's notation is cryptic and confusing.)
    float g_v = NdotV / (NdotV*(1 - k) + k);
    float g_l = NdotL / (NdotL*(1 - k) + k);
    return g_v * g_l;
}

vec3 schlickFresnel(vec3 f0, float lDotH) {
    return f0 + (vec3(1.0f) - f0) * pow(1.0f - lDotH, 5.0f);
}

vec3 ggxDirect(vec3 N, vec3 V, vec3 dif, vec3 spec, float rough, float shadowMult) {
    vec3 lightIntensity = SUN_LIGHT_INTENSITY * SUN_LIGHT_COLOR;
    vec3 L = uShadowMap.lightDir;

    // Compute our lambertion term (N dot L)
    float NdotL = max(dot(N, L), 0.0001);

    // Compute half vectors and additional dot products for GGX
    vec3 H = normalize(V + L);
    float NdotH = max(dot(N, H), 0.0001);
    float LdotH = max(dot(L, H), 0.0001);
    float NdotV = max(dot(N, V), 0.0001);

    // Evaluate terms for our GGX BRDF model
    float D = ggxNormalDistribution(NdotH, rough);
    float G = ggxSchlickMaskingTerm(NdotL, NdotV, rough);
    vec3 F = schlickFresnel(spec, LdotH);

    // Evaluate the Cook-Torrance Microfacet BRDF model
    //     Cancel NdotL here to avoid catastrophic numerical precision issues.
    vec3 ggxTerm = D * G * F / (4 * NdotV);

    // Compute our final color (combining diffuse lobe plus specular GGX lobe)
    return shadowMult * lightIntensity * (ggxTerm + NdotL * dif / M_PI);
}

const vec3 AMBIENT_DIFFUSE_1 = vec3(0.16169041, 0.80845207, -0.5659165);
const vec3 AMBIENT_DIFFUSE_2 = vec3(-0.16169041, 0.80845207, 0.5659165);
const vec3 AMBIENT_DIFFUSE_BASE_COLOR = vec3(1.0, 0.95, 0.8);
const float AMBIENT_DIFFUSE_BASE_INTENSITY = 0.1;
const float AMBIENT_DIFFUSE_INTENSITY = 0.5;

void doStuff() {
    // Specular Mapping
    vec4 specMap = textureLod(usam_blockSpecularAtlas, gs_data.texCoord, 0.0);

    float perceptualSmoothness = specMap.r;
    float roughness = 1.0 - perceptualSmoothness;
    roughness *= roughness;

    float reflectance = specMap.g;
    reflectance = max(reflectance, 0.01);

    const float sssValueMin = 65.0 / 255.0;
    float sssStrength = step(sssValueMin, specMap.b);

    float emissive = 1.0 - pow(specMap.a, 1.0); // WTF

    tFactor = sssStrength / max(length(gs_data.worldCoord) / 64.0, 1.0);
//    tFactor = 0.0;

    // TBN
    vec3 lightWorldNormal = gs_data.worldNormal;
    lightWorldNormal = mix(lightWorldNormal, uShadowMap.lightDir, tFactor * mix(0.02, 0.1, gs_aoMul));
    lightWorldNormal = lightWorldNormal + uShadowMap.lightDir * tFactor * mix(0.05, 0.2, gs_aoMul);
    lightWorldNormal = normalize(lightWorldNormal);
    vec3 bitangent = cross(gs_data.worldNormal, gs_data.worldTangent);
    mat3 tbn1 = mat3(gs_data.worldTangent, bitangent, normalize(gs_data.worldNormal + lightWorldNormal * 0.5));
    mat3 tbn2 = mat3(gs_data.worldTangent, bitangent, lightWorldNormal);

    // Normal Mapping
    vec4 normalMap = textureLod(usam_blockNormalAtlas, gs_data.texCoord, 0.0);
    vec3 tagentNormal = normalMap.rgb * 2.0 - 1.0;
    tagentNormal.z = sqrt(1.0 - dot(tagentNormal.xy, tagentNormal.xy));
    float materialAo = normalMap.b;
    vec3 mappedNormal1 = normalize(tbn1 * tagentNormal);
    vec3 mappedNormal2 = normalize(tbn2 * tagentNormal);

    float ao = materialAo;

    vec3 viewDir = normalize(-gs_data.worldCoord);

    // Shadow
    float shadow = calcShadow(fNormalMatrix * lightWorldNormal);

    vec3 v = vec3(0.0);
    v += tFactor * mix(2.0, 4.0, gs_aoMul) * ao * shadow * gs_data.albedo.rgb * gs_lightMapColor;

//    vec3 specColor = mix(gs_data.albedo.rgb, SUN_LIGHT_COLOR, reflectance * 0.5) * (reflectance + 0.01);
    vec3 specColor = gs_data.albedo.rgb;

//    v += ggxDirect(
//        mappedNormal1,
//        viewDir,
//        gs_data.albedo.rgb,
//        specColor,
//        reflectance,
//        roughness,
//        SUN_LIGHT_COLOR * SUN_LIGHT_INTENSITY + reflectance * gs_data.albedo.rgb * 16.0,
//        uShadowMap.lightDir,
//        shadow * ao
//    );
    v += ggxDirect(
        mappedNormal1,
        viewDir,
        gs_data.albedo.rgb,
        specColor,
        roughness,
        shadow
    );

    v += emissive * EMISSIVE_INTENSITY * gs_data.albedo.rgb;

    vec3 ambientLight = AMBIENT_DIFFUSE_BASE_INTENSITY * AMBIENT_DIFFUSE_BASE_COLOR;
    ambientLight += AMBIENT_DIFFUSE_INTENSITY * gs_lightMapColor;
    float blockLight = gs_data.lightCoord.x * 2.0;
    blockLight += max(gs_data.lightCoord.x - gs_data.lightCoord.y, 0.0) * 4.0;
    ambientLight *= 2.0 + blockLight;

    float ambientLdotN = 0.3;
    ambientLdotN += max(dot(AMBIENT_DIFFUSE_1, gs_data.worldNormal), 0.0) * 0.35;
    ambientLdotN += max(dot(AMBIENT_DIFFUSE_2, gs_data.worldNormal), 0.0) * 0.35;

    v += ao * ambientLdotN * gs_data.albedo.rgb * ambientLight;

    outColor.rgb = v;
    outColor.a = roughness;
    outParams.r = reflectance;
    outParams.g = (shadow + 0.1) * sign(mappedNormal2.z);
    outParams.ba = mappedNormal2.xy;
//    outColor.rgb = shadow.rrr;
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