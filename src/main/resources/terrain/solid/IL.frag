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
uniform sampler2D usam_giTex;
uniform sampler2D usam_aoTex;

uniform vec3 originOffset;
uniform vec4 uval_aoParams;
uniform float uval_giParams;

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


void doStuff() {
    vec3 giSample = texture(usam_giTex, fTexCoord).rgb;
    float aoSample = texture(usam_aoTex, fTexCoord).r;

    vec4 sl1 = texelFetch(usam_solidLighting1, intTexCoord, 0);
    vec4 sl2 = texelFetch(usam_solidLighting2, intTexCoord, 0);

    float emissive = sl1.a;
    float roughness = sl2.a;
    roughness *= roughness;

    vec3 mappedNormal = sl2.rgb * 2.0 - 1.0;

    vec4 normalMap = textureLod(usam_blockNormalAtlas, gs_data.texCoord, 0.0);
    float materialAo = normalMap.b;

    vec3 viewDir = normalize(-gs_data.worldCoord);

    float blockLightAo = pow(aoSample, uval_aoParams.x);
    float skyLightAo = pow(aoSample, uval_aoParams.y);
    vec3 blockLightMul = 8.0 * gtaoMultiBounce(gs_data.albedo.rgb, blockLightAo);
    vec3 skyLightMul = 4.0 * gtaoMultiBounce(gs_data.albedo.rgb, skyLightAo);
    vec3 ambientLight = materialAo * calcAmbient(usam_lightMap, viewDir, mappedNormal, 0.5, gs_data.lightCoord, blockLightMul, skyLightMul);

    vec3 v = vec3(0.0);

    v += ambientLight;
    v += giSample * uval_giParams;
    v *= gs_data.albedo.rgb;

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