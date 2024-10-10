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

uniform sampler2D usam_linearDepth;

uniform float uval_tangentBias;
uniform vec2 uval_radius;
uniform vec2 uval_aoStrength;
uniform vec3 uval_projectParams;

#include "../solid/Texs.glsl"
#include "../solid/Utils.glsl"

in vec2 fTexCoordI;
in vec2 fTexCoord;

layout(location = 0) out vec4 outValue;

#define F_INDEX ubo_cameraDefault.gFrameIndex

float IGN(vec2 v) {
    return fract(52.9829189 * fract(0.06711056 * v.x + 0.00583715 * v.y));
}

float IGN(vec2 v, uint frame) {
    frame = frame % 64u;
    v = v + 5.588238 * float(frame);
    return fract(52.9829189 * fract(0.06711056 * v.x + 0.00583715 * v.y));
}

vec3 toViewSpace(vec2 texCoord, float viewZ, mat4 proj, mat4 projInv) {
    vec2 ndcXY = texCoord * 2.0 - 1.0;
    vec2 clipXY = ndcXY * -viewZ;
    vec2 viewXY = clipXY * vec2(projInv[0][0], projInv[1][1]);
    return vec3(viewXY, viewZ);
}

float stepC() {
    return exp2(float(HBAO_STEP_SAMPLES)) - 1.0;
}

float baseLodToRadius(float baseLod) {
    return 1.0 + stepC() * exp2(baseLod);
}

float radiusToBaseLod(float radius) {
    return log2((radius - 1.0) / stepC());
}

float lodTexelSize(float lod) {
    return exp2(lod);
}

float linearStep(float edge0, float edge1, float x) {
    return clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
}

void main() {
    GBufferData gs_data;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

    vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;

    vec3 viewDirThis = normalize(-gs_viewCoord);

    vec3 worldNormalThis = texture(usam_solidLighting2, fTexCoord).rgb;
    vec3 gs_viewNormal = mat3(ubo_cameraDefault.modelView) * worldNormalThis;

    float sampleAngleDelta = 2.0 * PI_CONST / HBAO_DIR_SAMPLES;
    float initialAngle = IGN(gl_FragCoord.xy, ubo_cameraDefault.gFrameIndex) * sampleAngleDelta - sampleAngleDelta * 0.5;
    vec2 sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy * ubo_cameraDefault.screenSize.xy;

    outValue = vec4(0.0);

    vec2 texelCoord = fTexCoord * ubo_cameraDefault.screenSize.xy;

    for (int dirIndex = 0; dirIndex < HBAO_DIR_SAMPLES; dirIndex++) {
        float sampleAngle = initialAngle + sampleAngleDelta * float(dirIndex);
        vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));

        float maxDistX = sampleDir.x != 0.0 ? (sampleDir.x >= 0.0 ? (ubo_cameraDefault.screenSize.x - texelCoord.x) / sampleDir.x : -texelCoord.x / sampleDir.x) : 1e6;
        float maxDistY = sampleDir.y != 0.0 ? (sampleDir.y < 0.0 ? (-texelCoord.y / sampleDir.y) : (ubo_cameraDefault.screenSize.y - texelCoord.y) / sampleDir.y) : 1e6;
        float maxDist = min(maxDistX, maxDistY);
        maxDist = min(maxDist, length(sampleDir * sphereRadius));

        float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
        float baseLod = clamp(radiusToBaseLod(maxDist), 0.0, maxLod);
        baseLod += IGN(gl_FragCoord.xy, ubo_cameraDefault.gFrameIndex * HBAO_DIR_SAMPLES + dirIndex) - 0.5;

        float sampleLod = baseLod;
        float sampleTexelDist = 1.41;

        vec3 T;
        {
            vec3 a = gs_viewNormal;
            vec3 b = vec3(sampleDir, 0.0);
            T = normalize(b - dot(b, a) * a);
        }

        float t = acos(dot(vec3(sampleDir, 0.0), gs_viewNormal)) - (0.5 * PI) + uval_tangentBias;
        float sinT = sin(t);
        float maxSinH = sinT;
        float maxOcclusion = 0.0;

        for (int stepIndex = 0; stepIndex < 16; stepIndex++) {
            float fi = float(stepIndex);

            float realSampleLod = round(sampleLod);

            float sampleLodTexelSize = lodTexelSize(realSampleLod) * 2.0;
            float sampleLodTexelSizeHalf = sampleLodTexelSize * 0.5;
            sampleTexelDist += sampleLodTexelSizeHalf;

            vec2 sampleTexelCoord = texelCoord + sampleDir * sampleTexelDist;
            vec2 sampleUV = sampleTexelCoord * ubo_cameraDefault.screenSize.zw;

            float sampleViewZ = textureLod(usam_linearDepth, sampleUV, realSampleLod).r;
            vec3 sampleViewXYZ = toViewSpace(sampleUV, sampleViewZ, ubo_cameraDefault.projection, ubo_cameraDefault.invProjection);

            vec3 diff = sampleViewXYZ - gs_viewCoord;
            float dist = length(diff);

            if (dist <= uval_radius.y) {
                float sinH = diff.z / dist;
                float aoAttenuation = linearStep(uval_radius.x, uval_radius.y, dist);
                sinH = mix(sinH, maxSinH, aoAttenuation);

                if (sinH >= maxSinH) {
                    maxSinH = sinH;
                    float occlusion = sinH - sinT;
                    maxOcclusion = max(occlusion, maxOcclusion);
                }
            }

            sampleLod = min(sampleLod + 0.3, maxLod);
            sampleTexelDist += sampleLodTexelSizeHalf;
        }

        outValue.a += maxOcclusion;
    }

    outValue.a /= float(HBAO_DIR_SAMPLES);
    outValue.a *= uval_aoStrength.x;
    outValue.a = 1.0 - outValue.a;
    outValue.a = pow(outValue.a, uval_aoStrength.y);
}