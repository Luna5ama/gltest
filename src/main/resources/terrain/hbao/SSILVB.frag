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

float radiusToLodStep(float y) {
    const float a0 = 0.0771784;
    const float a1 = 0.139577;
    const float a2 = -1.34389;
    y = clamp(y, 16.0, 32768.0);
    return saturate(a0 * log2(a1 * y + a2));
}

float lodTexelSize(float lod) {
    return exp2(lod);
}

vec2 acosF1(vec2 inX) {
    vec2 s;

    s = vec2(1.58648);
    s += -1.10912 * inX;
    s += -0.00000159536 * exp2(18.0454 * inX);

    return s;
}

// https://cdrinmatane.github.io/posts/ssaovb-code/
const float SECTOR_COUNT_F = 32.0;
const uint SECTOR_COUNT = 32u;
uint calcSectorBits(float minHorizon, float maxHorizon) {
    uint startBit = uint(minHorizon * SECTOR_COUNT_F);
    uint horizonAngle = uint(ceil((maxHorizon - minHorizon) * SECTOR_COUNT_F));
    uint angleBit = uint(0xFFFFFFFFu >> (SECTOR_COUNT - horizonAngle));
    angleBit = uint(-int(horizonAngle > 0u)) & angleBit;
    uint currentBitfield = angleBit << startBit;
    return currentBitfield;
}

void main() {
    outValue = vec4(1.0);
    if (textureLod(usam_gDepth, fTexCoord, 0).r != 1.0) {

        GBufferData gs_data;
        gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

        vec3 worldNormalThis = texture(usam_solidLighting2, fTexCoord).rgb;

        vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
        vec3 gs_viewNormal = mat3(ubo_cameraDefault.modelView) * worldNormalThis;
        vec3 viewDir = normalize(-gs_viewCoord);

        float sampleAngleDelta = 2.0 * PI_CONST / SSILVB_SAMPLE_SLICES;
        float initialAngle = IGN(gl_FragCoord.xy, ubo_cameraDefault.gFrameIndex) * sampleAngleDelta;
        vec2 sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy * ubo_cameraDefault.screenSize.xy;

        outValue = vec4(0.0);

        for (uint sliceIndex = 0; sliceIndex < SSILVB_SAMPLE_SLICES; sliceIndex++) {
            float sampleAngle = initialAngle + sampleAngleDelta * float(sliceIndex);
            vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));

            vec3 planeNormal = normalize(cross(vec3(sampleDir, 0.0), viewDir));
            vec3 sliceTangent = cross(viewDir, planeNormal);
            vec3 projNormal = normalize(gs_viewNormal - planeNormal * dot(gs_viewNormal, planeNormal));

            float maxDist = length(sampleDir * sphereRadius);

            vec3 frontOffset = projNormal * length(gs_viewCoord) * uval_thickness.x;
            vec3 backOffset = frontOffset + viewDir * uval_thickness.y;

            float lodStep = radiusToLodStep(maxDist);
            float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
            float sampleLod = 0.0;
            sampleLod += r2Seq1(ubo_cameraDefault.gFrameIndex + uint(IGN(gl_FragCoord.xy) * 1024.0)) - 0.5;
            float sampleTexelDist = 1.5;
            uint sampleSectorBits = 0u;

            for (int stepIndex = 0; stepIndex < SSILVB_SAMPLE_STEPS; stepIndex++) {
                float sampleLodTexelSize = lodTexelSize(sampleLod) * 1.0;
                float stepTexelSize = sampleLodTexelSize * 0.5;
                sampleTexelDist += stepTexelSize;

                vec2 sampleTexelCoord = gl_FragCoord.xy + sampleDir * sampleTexelDist;
                vec2 sampleUV = sampleTexelCoord * ubo_cameraDefault.screenSize.zw;

                float sampleViewZ = textureLod(usam_linearDepth, sampleUV, sampleLod * 0.5).r;
                vec3 sampleViewXYZ = toViewSpace(sampleUV, sampleViewZ, ubo_cameraDefault.projection, ubo_cameraDefault.invProjection);
                vec3 diff = sampleViewXYZ - gs_viewCoord;

                if (dot(diff, diff) <= uval_radius.w) {
                    vec3 frontPos = diff - frontOffset;
                    vec3 backPos = diff - backOffset;

                    vec2 cosH;
                    cosH.x = dot(frontPos, projNormal) * inversesqrt(dot(frontPos, frontPos));
                    cosH.y = dot(backPos, projNormal) * inversesqrt(dot(backPos, backPos));

                    cosH = saturate(cosH);
                    vec2 H = acosFastPositive(cosH) / PI_HALF_CONST;
                    uint stepSectorBits = calcSectorBits(min(H.x, H.y), max(H.x, H.y));
                    sampleSectorBits |= stepSectorBits;
                }

                sampleLod = min(sampleLod + lodStep, maxLod);
                sampleTexelDist += stepTexelSize;
            }

            outValue.r += maxDist;
            outValue.a += float(bitCount(sampleSectorBits)) / float(SECTOR_COUNT);
        }

        outValue.r /= float(SSILVB_SAMPLE_SLICES);
        outValue.a /= float(SSILVB_SAMPLE_SLICES);
        outValue.a = linearStep(0.01, 1.0, outValue.a);
        outValue.a *= uval_aoStrength.x;
        outValue.a = saturate(1.0 - outValue.a);
        outValue.a = pow(outValue.a, uval_aoStrength.y);
    }
}