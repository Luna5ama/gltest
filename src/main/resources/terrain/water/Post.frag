#version 460 core

layout(early_fragment_tests) in;

#include "../ColorUtils.glsl"
#include "../GlobalCamera.glsl"
#include "../LightParams.glsl"
#include "../GBuffers.glsl"

#include "../rtwsm/ShadowParams.glsl"
#include "../utils/R2Seqs.glsl"

layout(std140) uniform FogParameters {
    vec4 fogColor;
    vec2 fogParams;
} fogParameters;

#include "../SolidGBuffer.glsl"
#include "../WaterGBuffer.glsl"
#include "../rtwsm/ShadowSamplers.glsl"

uniform sampler2D usam_backSceneTex;
uniform sampler2D usam_lightMap;
uniform sampler2D usam_refUVTex;
uniform samplerCube usam_skyboxCube;

uniform vec4 skyColor;

in vec2 fClipCoord;
in vec2 fTexCoord;
in vec2 fTexCoordI;
in mat3 fNormalMatrix;

out vec4 outColor;

ivec2 intTexCoord = ivec2(fTexCoordI);

vec3 gs_worldCoord;

vec3 gs_viewCoord;

GBufferData gw_data;

vec3 gw_viewCoord;
vec3 gw_viewNormal;

const float REFLECTION_BRIGHNESS = (1.0 / SUN_LIGHT_INTENSITY) * 2.0;
const float REFRACTION_BRIGHNESS = (1.0 / SUN_LIGHT_INTENSITY) * 2.0;

float calcFog() {
    #if FOG_SHAPE == FOG_SHAPE_SPHERE
    float fogDistance = min(length(gw_viewCoord), length(gw_data.worldCoord));
    #else
    float fogDistance = min(length(gw_viewCoord.xz), length(gw_data.worldCoord.xz));
    #endif

    #if FOG_TYPE == FOG_TYPE_LINEAR
    float fogAmount = clamp(fma(fogParameters.fogParams.x, fogDistance, fogParameters.fogParams.y), 0.0, 1.0);
    #elif FOG_TYPE == FOG_TYPE_EXP
    fogAmount = exp(-fogParameters.fogParams.x * fogDistance);
    #else
    float exponent = fogParameters.fogParams.x * fogDistance;
    fogAmount = exp(-(exponent * exponent));
    #endif

    return fogAmount;
}

float calcDiffuse(vec3 normal, vec3 lightDir) {
    return max(dot(normal, lightDir), 0.0);
}

float rand(vec2 uv) {
    return fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43578.5453);
}

uint worldCoordRand = uint(rand(gw_data.worldCoord.xz) * 4096.0);

float calcShadow() {
    vec3 coord = gw_viewCoord;

    coord += fNormalMatrix * uShadowMap.lightDir * 0.05;
    coord += gw_viewNormal * 0.1;

    vec4 worldCoord = ubo_camera.invModelView * vec4(coord, 1.0);
    worldCoord += uShadowMap.shadowWorldOffset;
    vec4 shadowCoord = uShadowMap.matrix * worldCoord;
    shadowCoord /= shadowCoord.w;
    shadowCoord.xyz = shadowCoord.xyz * 0.5 + 0.5;

    shadowCoord.z -= 0.00001;
    vec2 texelSize;
    shadowCoord.xy = rtwsm_warpTexCoordTexelSize(usam_warpingMap, shadowCoord.xy, texelSize);

    vec3 shadowPos = shadowCoord.xyz;

    #define SAMPLE_N (16)

    vec2 ssRange = texelSize * 1024.0;

    vec2 INV_RANGE = 1.0 / ssRange;

    float shadow = 0.0;
    uint idx2 = ubo_camera.gFrameIndex * SAMPLE_N + worldCoordRand;

    #if SAMPLE_N == 1
    vec2 offset = r2Seq2(idx2) * ssRange - ssRange * 0.5;
    shadow += rtwsm_sampleShadowDepthOffset(usam_shadowDepthComp, shadowPos, 4.0, offset);
    #else
    for (int i = 0; i < SAMPLE_N; i++) {
        vec2 offset = r2Seq2(idx2) * ssRange - ssRange * 0.5;
        shadow += rtwsm_sampleShadowDepthOffset(usam_shadowDepthComp, shadowPos, 4.0, offset);
        idx2++;
    }
    shadow /= float(SAMPLE_N);
    #endif

    return shadow;
}

const float M_PI = 3.14159265359;

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

vec3 ggxDirect(vec3 N, vec3 V, vec3 dif, vec3 spec, float rough) {
    vec3 lightIntensity = SUN_LIGHT_INTENSITY * SUN_LIGHT_COLOR;
    vec3 L = uShadowMap.lightDir;

    // Compute our lambertion term (N dot L)
    float NdotL = max(dot(N, L), 0.0001);

    // Shoot our shadow ray to our randomly selected light
    float shadowMult = calcShadow();

    // Compute half vectors and additional dot products for GGX
    vec3 H = normalize(V + L);
    float NdotH = max(dot(N, H), 0.0001);
    float LdotH = max(dot(L, H), 0.0001);
    float NdotV = max(dot(N, V), 0.0001);

//    // Evaluate terms for our GGX BRDF model
//    float D = ggxNormalDistribution(NdotH, rough);
//    float G = ggxSchlickMaskingTerm(NdotL, NdotV, rough);
//    vec3 F = schlickFresnel(spec, LdotH);
//
//    // Evaluate the Cook-Torrance Microfacet BRDF model
//    //     Cancel NdotL here to avoid catastrophic numerical precision issues.
//    vec3 ggxTerm = D * G * F / (4 * NdotV);
//
//    // Compute our final color (combining diffuse lobe plus specular GGX lobe)
//    return shadowMult * lightIntensity * (ggxTerm + NdotL * dif / M_PI);

    // Evaluate terms for our GGX BRDF model
    float D = ggxNormalDistribution(NdotH, rough);
    float G = ggxSchlickMaskingTerm(NdotL, NdotV, rough);
    vec3 F = schlickFresnel(spec, LdotH);

    // Evaluate the Cook-Torrance Microfacet BRDF model
    //     Cancel NdotL here to avoid catastrophic numerical precision issues.
    vec3 ggxTerm = D * G * F * shadowMult / (4 * NdotV * NdotL);

    // Compute our final color (combining diffuse lobe plus specular GGX lobe)
    return min(shadowMult + 0.5, 1.0) * lightIntensity * (ggxTerm + NdotL * dif / M_PI);
}

vec3 calcLight(vec3 diffuseColor) {
    return ggxDirect(gw_data.worldNormal, normalize(-gw_data.worldCoord), diffuseColor, SUN_LIGHT_COLOR, 0.01);
}

vec4 sampleReflectionTex(vec2 uv) {
    vec3 refDir = reflect(normalize(gw_data.worldCoord), gw_data.worldNormal);
    vec3 skyColor = texture(usam_skyboxCube, refDir).rgb;

    vec2 refUV = texture(usam_refUVTex, uv).xy;
    vec4 refColor = texture(usam_backSceneTex, refUV);

    vec2 mCoord = (refUV * 2.0 - 1.0) * vec2(0.9, 1.0);
    float screenEdgeFactor = 1.0 - smoothstep(0.8, 0.99, length(mCoord));
    refColor = mix(vec4(skyColor, 1.0), refColor, step(0.0, refUV.x) * screenEdgeFactor);
    refColor.a = step(-0.1, refUV.y);

    return refColor;
}

vec4 calcReflectColor() {
    vec4 reflectColor = sampleReflectionTex(fTexCoord);
    reflectColor.rgb *= REFLECTION_BRIGHNESS;

    return reflectColor;
}

vec3 sampleRefractionTex(vec2 uv) {
    vec2 refUV = texture(usam_refUVTex, uv).zw;
    vec2 mCoord = refUV * vec2(1.0, 1.1);
    float screenEdgeFactor = 1.0 - smoothstep(0.7, 0.95, length(mCoord * 2.0 - 1.0));

    vec3 color = texture(usam_backSceneTex, mix(uv, refUV, screenEdgeFactor)).rgb;

    return color;
}

const float MAX_VISIBLE_DEPTH = 32.0;
const float INV_MAX_VISIBLE_DEPTH = 1.0 / MAX_VISIBLE_DEPTH;
const float DEPTH_POWER = 1.0;

vec3 calcRefractColor() {
    vec3 refractColor = sampleRefractionTex(fTexCoord);
    vec2 halfTexel = ubo_camera.screenSize.zw * 0.5;
    refractColor *= REFRACTION_BRIGHNESS;

    return refractColor;
}

void main() {
    GBufferData tempData;
    gbuffer_unpack1(texelFetch(usam_gSolidData1, intTexCoord, 0), tempData);
    gs_worldCoord = tempData.worldCoord;

    gbuffer_unpack1(texelFetch(usam_gWaterData1, intTexCoord, 0), gw_data);
    gbuffer_unpack2(texelFetch(usam_gWaterData2, intTexCoord, 0), gw_data);

    gs_viewCoord = (ubo_camera.modelView * vec4(gs_worldCoord, 1.0)).xyz;
    
    gw_viewCoord = (ubo_camera.modelView * vec4(gw_data.worldCoord, 1.0)).xyz;
    gw_viewNormal = fNormalMatrix * gw_data.worldNormal;

    vec3 refractColor = calcRefractColor();
    vec4 reflectColor = calcReflectColor();

    vec2 refUV = texture(usam_refUVTex, fTexCoord).zw;
    float refractDepth = gw_viewCoord.z - gs_viewCoord.z;
    float depthFactor = pow(clamp(refractDepth * INV_MAX_VISIBLE_DEPTH, 0.0, 1.0), DEPTH_POWER);

    refractColor.rgb = mix(gw_data.albedo.rgb, refractColor.rgb, (1.0 - min(depthFactor + 0.2, 1.0)));
    reflectColor.rgb = mix(gw_data.albedo.rgb, reflectColor.rgb, 0.95 * reflectColor.a);

    float reflectFactor = 0.8 + pow(1.0 + dot(normalize(gw_data.worldCoord), gw_data.worldNormal), 4.0) * 0.2;
    reflectFactor = clamp(reflectFactor, 0.0, 0.98);
    //    reflectFactor = 1.0;

    outColor.rgb = mix(refractColor, reflectColor.rgb, reflectFactor);

//    outColor.rgb = gw_data.albedo.rgb;
//    outColor.a = gw_data.albedo.a;
    outColor.rgb = calcLight(outColor.rgb);
//
    outColor.a = 1.0;
//    outColor.a *= calcFog();
//
//    outColor.a *= smoothstep(0.2, 0.5, gw_viewCoord.z - gs_viewCoord.z);
}