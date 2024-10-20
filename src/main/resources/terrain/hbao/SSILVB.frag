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

uniform sampler2D usam_linearDepth;

uniform vec2 uval_thickness;
uniform float uval_maxCosH;
uniform vec4 uval_radius;
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

float lodToRadius(float baseLod) {
    return 1.5 + GTAO_SAMPLE_STEPS * exp2(baseLod);
}

float radiusToLod(float radius) {
    return log2((radius - 1.5) / GTAO_SAMPLE_STEPS);
}

float lodTexelSize(float lod) {
    return exp2(lod);
}

// https://cdrinmatane.github.io/posts/ssaovb-code/
const float SECTOR_COUNT_F = 32.0;
const uint SECTOR_COUNT = 32u;
uint calcSectorBits(float minHorizon, float maxHorizon) {
    uint startBit = uint(minHorizon * SECTOR_COUNT_F);
    uint horizonAngle = uint(ceil((maxHorizon - minHorizon) * SECTOR_COUNT_F));
    uint angleBit = horizonAngle > 0u ? uint(0xFFFFFFFFu >> (SECTOR_COUNT - horizonAngle)) : 0u;
    uint currentBitfield = angleBit << startBit;
    return currentBitfield;
}

void main() {
    if (textureLod(usam_gDepth, fTexCoord, 0).r == 1.0) {
        outValue = vec4(1.0);
        return;
    }

    GBufferData gs_data;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

    vec3 worldNormalThis = texture(usam_solidLighting2, fTexCoord).rgb;

    vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
    vec3 gs_viewNormal = mat3(ubo_cameraDefault.modelView) * worldNormalThis;
    vec3 viewDir = normalize(-gs_viewCoord);

    float sampleAngleDelta = 2.0 * PI_CONST / GTAO_SAMPLE_SLICES;
    float initialAngle = IGN(gl_FragCoord.xy, ubo_cameraDefault.gFrameIndex) * sampleAngleDelta;
    vec2 sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy * ubo_cameraDefault.screenSize.xy;

    outValue = vec4(0.0);

    for (uint sliceIndex = 0; sliceIndex < GTAO_SAMPLE_SLICES; sliceIndex++) {
        float sampleAngle = initialAngle + sampleAngleDelta * float(sliceIndex);
        vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));

        vec3 planeNormal = normalize(cross(vec3(sampleDir, 0.0), viewDir));
        vec3 sliceTangent = cross(viewDir, planeNormal);
        vec3 projNormal = normalize(gs_viewNormal - planeNormal * dot(gs_viewNormal, planeNormal));

        float maxDistX = sampleDir.x != 0.0 ? (sampleDir.x >= 0.0 ? (ubo_cameraDefault.screenSize.x - gl_FragCoord.x) / sampleDir.x : -gl_FragCoord.x / sampleDir.x) : 1e6;
        float maxDistY = sampleDir.y != 0.0 ? (sampleDir.y < 0.0 ? (-gl_FragCoord.y / sampleDir.y) : (ubo_cameraDefault.screenSize.y - gl_FragCoord.y) / sampleDir.y) : 1e6;
        float maxDist = min(maxDistX, maxDistY);
        maxDist = min(maxDist, length(sampleDir * sphereRadius));

        vec3 frontOffset = projNormal * length(gs_viewCoord) * uval_thickness.x;
        vec3 backOffset = frontOffset + viewDir * uval_thickness.y;

        float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
//        maxLod = 2.0;
        float sampleLod = radiusToLod(maxDist);
        sampleLod += r2Seq1(ubo_cameraDefault.gFrameIndex + uint(IGN(gl_FragCoord.xy) * 1024.0)) * 2.0 - 1.0;
        sampleLod = clamp(sampleLod, 0.0, maxLod);
        float sampleLodTexelSize = lodTexelSize(sampleLod) * 1.0;
        sampleLod = round(sampleLod);
//        sampleLod = 0.0;

        float stepTexelSize = sampleLodTexelSize * 0.5;
        float sampleTexelDist = 1.5;

        uint sampleSectorBits = 0u;

        for (int stepIndex = 0; stepIndex < GTAO_SAMPLE_STEPS; stepIndex++) {
            float fi = float(stepIndex);

            sampleTexelDist += stepTexelSize;

            vec2 sampleTexelCoord = gl_FragCoord.xy + sampleDir * sampleTexelDist;
            vec2 sampleUV = sampleTexelCoord * ubo_cameraDefault.screenSize.zw;

            float sampleViewZ = textureLod(usam_linearDepth, sampleUV, sampleLod).r;
            vec3 sampleViewXYZ = toViewSpace(sampleUV, sampleViewZ, ubo_cameraDefault.projection, ubo_cameraDefault.invProjection);
            vec3 diff = sampleViewXYZ - gs_viewCoord;

            if (length(diff) <= uval_radius.y) {
                vec3 frontPos = diff - frontOffset;
                vec3 backPos = diff - backOffset;

                vec2 cosH;
                cosH.x = dot(frontPos, projNormal) * inversesqrt(dot(frontPos, frontPos));
                cosH.y = dot(backPos, projNormal) * inversesqrt(dot(backPos, backPos));

                cosH = max(cosH, 0.0);
                vec2 H = acosFastPositive(cosH) / PI_HALF_CONST;
                uint stepSectorBits = calcSectorBits(min(H.x, H.y), max(H.x, H.y));
                sampleSectorBits |= stepSectorBits;
            }

            sampleTexelDist += stepTexelSize;
        }

        outValue.a += float(bitCount(sampleSectorBits)) / float(SECTOR_COUNT);
    }

    outValue.a /= float(GTAO_SAMPLE_SLICES);
    outValue.a *= uval_aoStrength.x;
    outValue.a = max(1.0 - outValue.a, 0.0);
    outValue.a = pow(outValue.a, uval_aoStrength.y);
}