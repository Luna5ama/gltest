#version 460 core
#pragma optionNV (unroll all)

layout(early_fragment_tests) in;

#include "../ColorUtils.glsl"
#include "../GlobalCamera.glsl"
#include "../GBuffers.glsl"
#include "../LightParams.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Rand.glsl"

#include "Texs.glsl"
#include "../SolidGBuffer.glsl"

uniform sampler2D usam_lightMap;
uniform sampler2D usam_hbao;

uniform vec3 originOffset;

in vec2 fClipCoord;
in vec2 fTexCoord;
in vec2 fTexCoordI;
in mat3 fNormalMatrix;

layout(location = 0) out vec4 out1;
layout(location = 1) out vec4 out2;

ivec2 intTexCoord = ivec2(fTexCoordI);

GBufferData gs_data;

vec3 gs_viewCoord;
vec3 gs_viewNormal;
vec3 gs_lightMapColor;
float gs_aoMul;

uint worldCoordRand[6];

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

const vec3 AMBIENT_DIFFUSE_1 = vec3(0.16169041, 0.80845207, -0.5659165);
const vec3 AMBIENT_DIFFUSE_2 = vec3(-0.16169041, 0.80845207, 0.5659165);
const vec3 AMBIENT_DIFFUSE_BASE_COLOR = vec3(1.0, 0.95, 0.8);
const float AMBIENT_DIFFUSE_BASE_INTENSITY = 1.0;
const float AMBIENT_DIFFUSE_INTENSITY = 12.0;

const float AMBIENT_DIFFUSE_BASE_INTENSITY_2 = 0.01;
const float AMBIENT_DIFFUSE_INTENSITY_2 = 0.1;

void doStuff() {
    vec4 aoSample = texture(usam_hbao, fTexCoord, 0);
    vec4 sl1 = texelFetch(usam_solidLighting1, intTexCoord, 0);
    vec4 sl2 = texelFetch(usam_solidLighting2, intTexCoord, 0);

    vec3 dlV = sl1.rgb;
    vec3 mappedNormal = sl_unpackNormal(sl2);

    vec3 v = vec3(0.0);

    v += pow(aoSample.a, 2.0) * dlV;

    vec3 ambientLight1 = AMBIENT_DIFFUSE_BASE_INTENSITY * AMBIENT_DIFFUSE_BASE_COLOR;
    ambientLight1 += AMBIENT_DIFFUSE_INTENSITY * gs_lightMapColor;
    float ambientLdotN = 0.5;
    ambientLdotN += max(dot(AMBIENT_DIFFUSE_1, mappedNormal), 0.0);
    ambientLdotN += max(dot(AMBIENT_DIFFUSE_2, mappedNormal), 0.0);

    v += aoSample.a * ambientLdotN * gs_data.albedo.rgb * aoSample.rgb * ambientLight1;

    vec3 ambientLight2 = AMBIENT_DIFFUSE_BASE_INTENSITY_2 * AMBIENT_DIFFUSE_BASE_COLOR;
    ambientLight2 += AMBIENT_DIFFUSE_INTENSITY_2 * gs_lightMapColor;

    v += aoSample.a * ambientLdotN * gs_data.albedo.rgb * ambientLight2;

    out1.rgb = v;
    out1.a = 1.0;
    out2 = out1;
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