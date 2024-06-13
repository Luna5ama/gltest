#version 460 core
#pragma optionNV (unroll all)
#define NO_NORMALS

#include "../utils/Rand.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Math.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"
#include "../GlobalCamera.glsl"
#include "../utils/FastMath.glsl"

uniform sampler2D usam_lightMap;
uniform float uval_tangentBias;
uniform vec4 uval_radius;
uniform vec2 uval_aoStrength;
uniform vec2 uval_giStrength;
uniform vec3 uval_projectParams;
uniform int uval_randR;
uniform uint uval_idk;

#include "../solid/Texs.glsl"
#include "../solid/Utils.glsl"

in vec2 fTexCoordI;
in vec2 fTexCoord;

layout(location = 0) out vec4 outValue;
layout(location = 1) out vec4 outIDK;

vec3 gtaoMultiBounce(vec3 albedo, float ao) {
    vec3 a = 2.0404 * albedo - 0.3324;
    vec3 b = 0.0091 * albedo + 0.0151;
    vec3 c = 0.0071 * albedo + 0.0001;
    float ao2 = ao * ao;
    float ao3 = ao2 * ao;
    return a * ao3 + b * ao2 + c * ao;
}

float blinnPhong(vec3 lightDir, vec3 viewDir, vec3 normal, float roughness) {
    vec3 halfDir = normalize(lightDir + viewDir);
    float NdotH = max(dot(normal, halfDir), 0.0);
    float roughness2 = roughness * roughness;

    const float PI_CONST = 3.14159265359;
    return (1.0 / (PI_CONST * roughness2)) * pow(NdotH, 2.0 / roughness2 - 2.0);
}

#define F_INDEX ubo_cameraDefault.gFrameIndex
//#define F_INDEX 0

void main() {
    GBufferData gs_data;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

    vec4 slThis2 = texture(usam_solidLighting2, fTexCoord);

    vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
    vec3 viewDirThis = normalize(-gs_data.worldCoord);

//        vec3 worldNormalThis = slThis2.rgb;
//    vec3 worldNormalThis = gs_data.worldNormal;
    vec3 worldNormalThis = normalize(gs_data.worldNormal + slThis2.rgb);

    vec3 gs_viewNormal = mat3(ubo_cameraDefault.modelView) * worldNormalThis;
    float roughnessThis = slThis2.a;
    roughnessThis *= roughnessThis;

    uint worldCoordRand0 = uint(rand(gs_data.worldCoord.xyz) * 1024.0);
    uint worldCoordRand1 = uint(rand(gs_data.worldCoord.zxy) * 1024.0);

    ivec2 intTexCoord = ivec2(fTexCoordI);
    uint texCoordRand = uint((intTexCoord.x % uval_randR) + ((intTexCoord.y % uval_randR) * uval_randR));

    float sampleAngleDelta = 2.0 * PI_CONST / HBAO_DIR_SAMPLES;
    float initialAngle = r2Seq1(F_INDEX + worldCoordRand0) * sampleAngleDelta - sampleAngleDelta * 0.5;
    vec2 sphereRadius = (max(uval_radius.x, uval_radius.z) / -gs_viewCoord.z) * uval_projectParams.xy;

    vec4 sum = vec4(0.0);

    for (int i = 0; i < HBAO_DIR_SAMPLES; i++) {
        float sampleAngle = initialAngle + sampleAngleDelta * float(i);
        vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));
        vec2 sampleDirUVUnit = sampleDir * vec2(uval_projectParams.z, 1.0) * ubo_cameraDefault.screenSize.zw;
        vec2 sampleDirUV = sampleDir * sphereRadius / HBAO_STEP_SAMPLES;

        vec2 localSpaceNormal = vec2(dot(gs_viewNormal.xy, sampleDir), dot(gs_viewNormal.xy, sampleDir));
        float tangentAngle = asinFast4(-dot(sampleDir, gs_viewNormal.xy)) + uval_tangentBias;

        vec3 colorSum = vec3(0.0);
        float giSample = 0.0;

        uint dirStepBaseIdx = worldCoordRand1;
        dirStepBaseIdx += F_INDEX;
        dirStepBaseIdx += i;
        dirStepBaseIdx *= HBAO_STEP_SAMPLES;

        float sinTan = sin(tangentAngle);
        float maxOcclusion = 0.0;
        vec2 baseCoord = fTexCoord + sampleDirUVUnit;

        for (int stepIdx = 0; stepIdx < HBAO_STEP_SAMPLES; stepIdx++) {
            float stepSize = float(stepIdx) + r2Seq1(dirStepBaseIdx + stepIdx);
            vec2 sampleStep = stepSize * sampleDirUV;
            vec2 sampleTexCoord = baseCoord + sampleStep;
            vec2 sampleClipCoord = sampleTexCoord * 2.0 - 1.0;
            if (any(greaterThanEqual(abs(sampleClipCoord), vec2(1.0)))) {
                break;
            }

            GBufferData gSampleData;
            gbuffer_unpack1(texture(usam_gData1, sampleTexCoord), gSampleData);

            vec3 diff = gSampleData.worldCoord - gs_data.worldCoord;
            float dist = length(diff);
            if (dist > uval_radius.x) break;

                vec3 diffView = mat3(ubo_cameraDefault.modelView) * diff;
                float normLastDist = dist * uval_radius.y;
                float aoAttenuation = 1.0 - normLastDist;
                float xSample = diffView.z * fastRcpSqrtNR0(dot(diffView.xy, diffView.xy));
                float sinSample = xSample * fastRcpSqrtNR0(1.0 + xSample * xSample);
                maxOcclusion = clamp(aoAttenuation * (sinSample - sinTan) * uval_aoStrength.x, maxOcclusion, 1.0);


                vec4 sample1 = texture(usam_solidLighting1, sampleTexCoord);
                vec4 sample2 = texture(usam_solidLighting2, sampleTexCoord);

                vec3 dirToThat = normalize(gSampleData.worldCoord - gs_data.worldCoord);
                vec3 worldNormalThat = sample2.rgb;

                vec3 emissiveLightThat = sample1.a * EMISSIVE_INTENSITY * EMISSIVE_INTENSITY * gSampleData.albedo.rgb;
                vec3 diffuseLightThat = uval_giStrength.x * sample1.rgb * gSampleData.albedo.rgb + emissiveLightThat;
                vec3 specLightThat = uval_giStrength.y * (sample1.rgb + emissiveLightThat);

                vec3 incomingLight = specLightThat + diffuseLightThat;

                float diffuseThis = brdf_diffuse_orenNayar(dirToThat, viewDirThis, worldNormalThis, roughnessThis);
//                float diffuseThis = max(dot(worldNormalThis, dirToThat), 0.0);
                float angle = dot(-dirToThat, worldNormalThat);
//                angle = angle * angle * angle * (angle * (6.0 * angle - 15.0) + 10.0);
                angle = max(angle, 0.0);

                float attenuation = 1.0 / (1.0 + dist * dist);
//                attenuation *= 1.0 - smoothstep(uval_radius.z * 0.1, uval_radius.z, dist);

                colorSum += angle * diffuseThis * attenuation * incomingLight;

                giSample++;
        }

        sum.a += maxOcclusion;
//        sum.a += 1.0 - maxOcclusion;
        sum.rgb += step(1.0, giSample) * (colorSum / float(max(giSample, 1)));
    }

    sum /= HBAO_DIR_SAMPLES;
//    sum.a = min(sum.a * uval_aoStrength.x, 1.0);
    sum.a = 1.0 - sum.a;

    outValue = sum;
}