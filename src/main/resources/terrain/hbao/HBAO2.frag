#version 460 core
#pragma optionNV (unroll all)
#define NO_NORMALS

#include "../utils/Rand.glsl"
#include "../rtwsm/ShadowParams.glsl"
#include "../LightParams.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Math.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"
#include "../GlobalCamera.glsl"
#include "../utils/FastMath.glsl"

#define PI 3.14159265358979323846

uniform sampler2D usam_lightMap;
uniform float uval_tangentBias;
uniform vec2 uval_radius;
uniform vec2 uval_aoStrength;
uniform vec2 uval_giStrength;
uniform vec3 uval_projectParams;
uniform int uval_randR;

#include "../solid/Texs.glsl"
#include "../solid/Utils.glsl"

in vec2 fTexCoordI;
in vec2 fTexCoord;

layout(location = 0) out vec4 outValue;
layout(location = 1) out vec4 outIDK;

float blinnPhong(vec3 lightDir, vec3 viewDir, vec3 normal, float roughness) {
    vec3 halfDir = normalize(lightDir + viewDir);
    float NdotH = max(dot(normal, halfDir), 0.0);

    float roughness2 = max(roughness * roughness, 0.01);

    return max(pow(NdotH, 2.0 / (roughness2) - 2.0) * (1.0 - roughness2), 0.0);
}

#define F_INDEX ubo_cameraDefault.gFrameIndex
//#define F_INDEX 0

float IGN(vec2 v) {
    return fract(52.9829189 * fract(0.06711056 * v.x + 0.00583715 * v.y));
}

float IGN(vec2 v, uint frame) {
    frame = frame % 64u;
    v = v + 5.588238 * float(frame);
    return fract(52.9829189 * fract(0.06711056 * v.x + 0.00583715 * v.y));
}

float tangent(vec3 v) {
    return v.z * inversesqrt(dot(v.xy, v.xy));
}

float tan2sin(float x) {
    return x * inversesqrt(x * x + 1.0);
}

void main() {
    GBufferData gs_data;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

    vec4 slThis2 = texture(usam_solidLighting2, fTexCoord);

    vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
    vec3 viewDirThis = normalize(-gs_data.worldCoord);
    vec3 viewCordDirThis = normalize(-gs_viewCoord);

    //            vec3 worldNormalThis = slThis2.rgb;
//            vec3 worldNormalThis = gs_data.worldNormal;
    vec3 worldNormalThis = normalize(slThis2.rgb);

    vec3 gs_viewNormal = normalize(mat3(ubo_cameraDefault.modelView) * worldNormalThis);
    float roughnessThis = slThis2.a;
    roughnessThis *= roughnessThis;

    uint worldCoordRand0 = uint(rand(gs_data.worldCoord.xyz) * 256.0);
    uint worldCoordRand1 = uint(rand(gs_data.worldCoord.zxy) * 256.0);

    //    uint worldCoordRand0 = uint(rand(gl_FragCoord.yx) * 256.0);
    //    uint worldCoordRand1 = uint(rand(gl_FragCoord.yx) * 256.0);

    //    uint worldCoordRand0 = uint(IGN(gl_FragCoord.yx, ubo_cameraDefault.gFrameIndex) * 256.0);
    //    uint worldCoordRand1 = uint(IGN(gl_FragCoord.yx, ubo_cameraDefault.gFrameIndex) * 256.0);

    ivec2 intTexCoord = ivec2(fTexCoordI);

    float sampleAngleDelta = 2.0 * PI_CONST / HBAO_DIR_SAMPLES;
    //    float initialAngle = r2Seq1(F_INDEX + worldCoordRand0) * sampleAngleDelta - sampleAngleDelta * 0.5;
    float initialAngle = IGN(gl_FragCoord.xy, ubo_cameraDefault.gFrameIndex) * sampleAngleDelta - sampleAngleDelta * 0.5;
    vec2 sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy;

    vec4 sum = vec4(0.0);

    for (int i = 0; i < HBAO_DIR_SAMPLES; i++) {
        float sampleAngle = initialAngle + sampleAngleDelta * float(i);
        vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));
        vec2 sampleDirUVUnit = sampleDir * vec2(uval_projectParams.z, 1.0) * ubo_cameraDefault.screenSize.zw;
        vec2 sampleDirUV = sampleDir * sphereRadius;

        vec3 colorSum = vec3(0.0);
        float giSample = 0.0;

        uint dirStepBaseIdx = worldCoordRand1;
        dirStepBaseIdx += F_INDEX;
        dirStepBaseIdx += i;
        dirStepBaseIdx *= HBAO_STEP_SAMPLES;

        float maxOcclusion = 0.0;
        vec2 baseCoord = fTexCoord + sampleDirUVUnit;

        vec3 a = gs_viewNormal;
        vec3 b = vec3(sampleDir, 0.0);

        vec3 T = normalize(b - dot(b, a) * a);

        float t = acos(dot(vec3(sampleDir, 0.0), gs_viewNormal)) - (0.5 * PI) + uval_tangentBias;
        float sinT = sin(t);
        float maxSinH = sinT;

        float lastX = asin(sinT);
        vec3 lastY = vec3(0.0);
        vec3 lastNormal = worldNormalThis;
        bool first = true;

        for (int stepIdx = 0; stepIdx < HBAO_STEP_SAMPLES; stepIdx++) {
            float stepIdxF = float(stepIdx);
            float stepX = (stepIdxF + r2Seq1(dirStepBaseIdx + stepIdx)) / HBAO_STEP_SAMPLES;
//            float stepX = (stepIdxF + 0.5) / HBAO_STEP_SAMPLES;
            float stepY = stepX * stepX;
//            float stepY = stepX;
//            float stepY = exp2(stepX) - 1.0;
            vec2 sampleTexCoord = baseCoord + stepY * sampleDirUV;
            vec2 sampleClipCoord = sampleTexCoord * 2.0 - 1.0;
            if (any(greaterThanEqual(abs(sampleClipCoord), vec2(1.0)))) {
                break;
            }

            GBufferData gSampleData;
            gbuffer_unpack1(texture(usam_gData1, sampleTexCoord), gSampleData);

            vec3 viewCoordThat = (ubo_cameraDefault.modelView * vec4(gSampleData.worldCoord, 1.0)).xyz;
            vec3 diff = viewCoordThat - gs_viewCoord;
            float dist = length(diff);
            if (dist > uval_radius.y) continue;

//            float tanH = tangent(diff);
//            float sinH = tan2sin(tanH);
            float sinH = diff.z / dist;

            if (sinH >= maxSinH) {
                maxSinH = sinH;

                float aoAtt = 4.0;
                float aoAttenuation = aoAtt / (aoAtt + dist);
//                aoAttenuation = 1.0;
                float occlusionBase = sinH - sinT;
                float occlusion = min(aoAttenuation * occlusionBase * uval_aoStrength.x, 1.0);
//                float occlusion = occlusionBase;

                maxOcclusion = max(occlusion, maxOcclusion);

                vec4 sample1 = texture(usam_solidLighting1, sampleTexCoord);
                vec4 sample2 = texture(usam_solidLighting2, sampleTexCoord);

                vec3 incomingLight = vec3(0.0);
                vec3 currNormal = normalize(mix(lastNormal, sample2.rgb, 1.0));
                incomingLight += max(dot(currNormal, uShadowMap.lightDir), 0.0) * sample1.rgb;
                incomingLight += sample2.a * sample1.rgb;
                incomingLight *= sample1.a;
                incomingLight *= SUN_LIGHT_INTENSITY * SUN_LIGHT_COLOR;

                float giAtt = 16.0;
                float dist2 = dist * dist;
                float attenuation = giAtt / (giAtt + dist2);
//                attenuation = 1.0;

                vec3 thisLightDir = normalize(gSampleData.worldCoord - gs_data.worldCoord);
                vec3 thatRefDir = normalize(gs_data.worldCoord - gSampleData.worldCoord);

                // Trapzoidal integration
                float currX = asin(sinH);
                float refDot = dot(thatRefDir, currNormal);
                vec3 currY = max(dot(worldNormalThis, thisLightDir), 0.0) * (attenuation * incomingLight);
                refDot = 1.0 - max(refDot, 0.0);
                currY *= 1.0 - (refDot * refDot * refDot);
//                currY *= max(refDot, 0.0);
//                currY *= (1.0 - step(refDot, 0.0));
                if (first) {
                    lastY = currY;
                    first = false;
                }
                vec3 colorSample = (currX - lastX) * 0.5 * (lastY + currY);

                lastX = currX;
                lastY = currY;
                lastNormal = (sample2.rgb + lastNormal) / 2.0;

                colorSum += colorSample;
                giSample++;
            }
        }

        sum.a += maxOcclusion;
        if (giSample > 0.0) {
            sum.rgb += colorSum;
        }
    }

    sum /= HBAO_DIR_SAMPLES;
    sum.a = 1.0 - sum.a;
    sum.a = pow(sum.a, uval_aoStrength.y);

    outValue = sum;
}