#version 460 core
//#pragma optionNV (unroll all)

#include "../utils/Rand.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Math.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"
#include "../GlobalCamera.glsl"

uniform float uval_tangentBias;
uniform float uval_radius;
uniform vec2 uval_aoStrength;
uniform float uval_giStrength;
uniform int uval_randR;
uniform uint uval_idk;

#include "../solid/Texs.glsl"

in vec2 fTexCoordI;
in vec2 fTexCoord;
in mat3 fNormalMatrix;

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

void main() {
    GBufferData gs_data;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

    vec4 slThis1 = texture(usam_solidLighting1, fTexCoord);
    vec4 slThis2 = texture(usam_solidLighting2, fTexCoord);

    vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;

    vec3 worldNormalThis = sl_unpackNormal(slThis2);

    vec3 gs_viewNormal = fNormalMatrix * worldNormalThis;

    float roughnessThis = texture(usam_solidLighting1, fTexCoord).a;
    float sThis = 0.25 + (1.0 - roughnessThis) * 4.0;

    uint worldCoordRand0 = uint(rand(gs_data.worldCoord.xyz) * 1024.0);
    uint worldCoordRand1 = uint(rand(gs_data.worldCoord.zxy) * 1024.0);

    ivec2 intTexCoord = ivec2(fTexCoordI);
    uint texCoordRand = uint((intTexCoord.x % uval_randR) + ((intTexCoord.y % uval_randR) * uval_randR)) ;

    float sampleAngleDelta = 2.0 * PI_CONST / HBAO_DIR_SAMPLES;
//    float initialAngle = r2Seq1(ubo_cameraDefault.gFrameIndex) * sampleAngleDelta - sampleAngleDelta * 0.5;
    float initialAngle = r2Seq1(ubo_cameraDefault.gFrameIndex + worldCoordRand0) * sampleAngleDelta - sampleAngleDelta * 0.5;
//    float initialAngle = r2Seq1((ubo_cameraDefault.gFrameIndex + texCoordRand) * uval_idk) * sampleAngleDelta - sampleAngleDelta * 0.5;
//    float initialAngle = r2Seq1(texCoordRand * uval_idk) * sampleAngleDelta - sampleAngleDelta * 0.5;

    vec4 sum = vec4(0.0);

    vec2 texelSize = clamp(vec2(uval_radius * (512.0 / HBAO_STEP_SAMPLES)) / length(gs_data.worldCoord), 1.0, 64.0) / textureSize(usam_gData1, 0);

    for (int i = 0; i < HBAO_DIR_SAMPLES; i++) {
        float sampleAngle = initialAngle + sampleAngleDelta * float(i);
        vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));
        vec3 sampleDir3 = vec3(sampleDir, 0.0);
        vec2 sampleDirUV = sampleDir * texelSize;

        float tangentAngle = (acos(dot(sampleDir3, gs_viewNormal)) - (0.5 * PI_CONST)) + uval_tangentBias;
        float sinTan = sin(tangentAngle);
        float horizonAngle = tangentAngle;

        vec3 lastDiff = vec3(0.0);
        vec3 colorSum = vec3(0.0);
        int samples = 0;

        float lastStep = 0.0;

        for (int j = 0; j < HBAO_STEP_SAMPLES; j++) {
            uint idx2 = j;
            idx2 += worldCoordRand1;
            //            idx2 += texCoordRand;
            idx2 += i * HBAO_STEP_SAMPLES;
            idx2 += ubo_cameraDefault.gFrameIndex * HBAO_STEP_SAMPLES * HBAO_DIR_SAMPLES;
            float stepSize = (float(j) + r2Seq1(idx2) * 2.0);
            vec2 sampleStep = stepSize * sampleDirUV;
            vec2 sampleTexCoord = fTexCoord + sampleStep;

            GBufferData gSampleData;
            gbuffer_unpack1(texture(usam_gData1, sampleTexCoord), gSampleData);

            vec3 sampleViewCoord = (ubo_cameraDefault.modelView * vec4(gSampleData.worldCoord, 1.0)).xyz;

            vec3 diff = sampleViewCoord.xyz - gs_viewCoord;
            float sampleDist = length(diff);
            if (length(diff) <= uval_radius) {
                float samplerElevationAngle = atan(diff.z / length(diff.xy));
                horizonAngle = max(horizonAngle, samplerElevationAngle);
                lastDiff = diff;

                vec3 worldDir = normalize(gs_data.worldCoord - gSampleData.worldCoord);

                vec4 sample1 = texture(usam_solidLighting1, sampleTexCoord);
                vec4 sample2 = texture(usam_solidLighting2, sampleTexCoord);

                vec3 worldNormalThat = gSampleData.worldNormal;

                vec3 color = sample1.rgb;
                float roughnessThat = sample1.a;
                float reflectance = max(sample2.r, 0.01);

                float normDist = sampleDist / uval_radius;

                float sThat = 0.25 + (1.0 - roughnessThat) * 4.0;
                float s = mix(sThis, sThat, 0.9);
                float f = pow(smoothstep(-0.5, 1.0, dot(worldDir, worldNormalThat)), s);
                f *= pow(max(dot(-worldDir, worldNormalThis), 0.0), s * 0.5);
                f *= 1.0 - normDist * normDist;

                float colorAtt = uval_giStrength;
                colorAtt *= 1.0 + (reflectance * reflectance * reflectance) * 64.0;
                colorSum += gtaoMultiBounce(color, f) * colorAtt;

                samples++;
            }
        }

        float norm = length(lastDiff) / uval_radius;
        float attenuation = 1.0 - norm * norm;

        float occlusion = clamp(attenuation * (sin(horizonAngle) - sinTan) * uval_aoStrength.x, 0.0, 1.0);
        sum.a += 1.0 - occlusion;

        if (samples > 0){
            sum.rgb += colorSum / float(samples);
        }
    }

    sum /= HBAO_DIR_SAMPLES;

    sum.a = pow(sum.a, uval_aoStrength.y);

    outValue = sum;
}