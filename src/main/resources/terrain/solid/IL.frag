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
#include "Utils.glsl"

uniform sampler2D usam_blockNormalAtlas;
uniform sampler2D usam_blockSpecularAtlas;
uniform sampler2D usam_lightMap;
uniform sampler2D usam_hbao;

uniform vec3 originOffset;
uniform vec4 uval_aoParams;

in vec2 fClipCoord;
in vec2 fTexCoord;
in vec2 fTexCoordI;
in mat3 fNormalMatrix;

layout(location = 0) out vec4 out1;

ivec2 intTexCoord = ivec2(fTexCoordI);

GBufferData gs_data;

vec3 gs_viewCoord;
vec3 gs_viewNormal;
vec3 gs_lightMapColor;
float gs_aoMul;

uint worldCoordRand[6];


vec3 gtaoMultiBounce(vec3 albedo, float ao) {
    vec3 a = 2.0404 * albedo - 0.3324;
    vec3 b = 0.0091 * albedo + 0.0151;
    vec3 c = 0.0071 * albedo + 0.0001;
    float ao2 = ao * ao;
    float ao3 = ao2 * ao;
    return a * ao3 + b * ao2 + c * ao;
}


void doStuff() {
    vec4 aoSample = texture(usam_hbao, fTexCoord);
    vec4 sl1 = texelFetch(usam_solidLighting1, intTexCoord, 0);
    vec4 sl2 = texelFetch(usam_solidLighting2, intTexCoord, 0);

    float emissive = sl1.a;
    float roughness = sl2.a;
    roughness *= roughness;

    vec3 mappedNormal = sl2.rgb;

    vec4 normalMap = textureLod(usam_blockNormalAtlas, gs_data.texCoord, 0.0);
    float materialAo = normalMap.b;

    vec3 viewDir = normalize(-gs_data.worldCoord);

    float blockLightMul = 64.0 * pow(aoSample.a, uval_aoParams.x);
    float skyLightMul = 32.0 * pow(aoSample.a, uval_aoParams.y);
    vec3 ambientLight = materialAo * calcAmbient(usam_lightMap, viewDir, mappedNormal, roughness, gs_data.lightCoord, blockLightMul, skyLightMul);

    vec3 v = vec3(0.0);
    v += ambientLight;
    v += pow(aoSample.a, uval_aoParams.z) * aoSample.rgb * uval_aoParams.w;
//    v += max(gtaoMultiBounce(aoSample.rgb, aoSample.a), vec3(0.0)) * uval_aoParams.w;
    v *= gs_data.albedo.rgb;
//    v += vec3(aoSample.a);

    out1.rgb = v;
    out1.a = 1.0;
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