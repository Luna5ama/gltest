#version 460 core
#pragma optionNV (unroll all)
#define NO_NORMALS

#include "../utils/Rand.glsl"
#include "../utils/Hash.glsl"
#include "../rtwsm/ShadowParams.glsl"
#include "../LightParams.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Math.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"
#include "../GlobalCamera.glsl"
#include "../utils/FastMath.glsl"

uniform sampler2D usam_linearDepth;

uniform float uval_thickness;
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

#define SSILVB_SAMPLE_STEPS 16

float radiusToLodStep(float y) {
    #if SSILVB_SAMPLE_STEPS == 16
    const float a0 = 0.0771784;
    const float a1 = 0.139577;
    const float a2 = -1.34389;
    #elif SSILVB_SAMPLE_STEPS == 32
    const float a0 = 0.0382693;
    const float a1 = 0.0663488;
    const float a2 = -1.20751;
    #endif
    y = clamp(y, 16.0, 32768.0);
    return saturate(a0 * log2(a1 * y + a2));
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
    uint angleBit = uint(0xFFFFFFFFu >> (SECTOR_COUNT - horizonAngle));
    angleBit = uint(-int(horizonAngle > 0u)) & angleBit;
    uint currentBitfield = angleBit << startBit;
    return currentBitfield;
}

#define NOISE_FRAME ubo_cameraDefault.gFrameIndex
//#define NOISE_FRAME 0u

const float WEIGHTS[32] = float[](
    0.049067674,
    0.048949466,
    0.048713334,
    0.048359848,
    0.047889858,
    0.047304497,
    0.046605176,
    0.045793579,
    0.044871661,
    0.043841643,
    0.042706007,
    0.041467489,
    0.040129071,
    0.03869398,
    0.037165671,
    0.035547826,
    0.033844344,
    0.032059328,
    0.030197078,
    0.028262081,
    0.026258998,
    0.024192654,
    0.022068029,
    0.019890239,
    0.017664533,
    0.015396271,
    0.013090917,
    0.010754027,
    0.0083912296,
    0.0060082167,
    0.0036107295,
    0.001204543
);

void main() {
    outValue = vec4(1.0);
    if (textureLod(usam_gDepth, fTexCoord, 0).r != 0.0) {
        GBufferData gs_data;
        gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

        vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
        vec3 gs_viewNormal = textureLod(usam_solidLighting2, fTexCoord, 0.0).rgb * 2.0 - 1.0;

        vec3 viewLightDir = mat3(ubo_cameraDefault.modelView) * uShadowMap.lightDir;
        vec3 viewDir = normalize(-gs_viewCoord);

        float sampleAngleDelta = 2.0 * PI_CONST / SSILVB_SAMPLE_SLICES;
        float initialAngle = IGN(gl_FragCoord.xy, NOISE_FRAME) * sampleAngleDelta;
        vec2 sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy * ubo_cameraDefault.screenSize.xy;

        outValue = vec4(0.0);

        uint r2Index = (hash21(uvec2(gl_FragCoord.xy)) & 1023u) + NOISE_FRAME;
        float baseSampleLod = r2Seq1(r2Index) * 0.5 - 0.25;

        for (uint sliceIndex = 0; sliceIndex < SSILVB_SAMPLE_SLICES; sliceIndex++) {
            float sampleAngle = initialAngle + sampleAngleDelta * float(sliceIndex);
            vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));

            vec3 planeNormal = normalize(cross(vec3(sampleDir, 0.0), viewDir));
            vec3 sliceTangent = cross(viewDir, planeNormal);
            vec3 projNormal = normalize(gs_viewNormal - planeNormal * dot(gs_viewNormal, planeNormal));

            float maxDist = length(sampleDir * sphereRadius);

            vec3 backOffset = viewDir * uval_thickness;

            float lodStep = radiusToLodStep(maxDist);
            float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
            float sampleLod = baseSampleLod;

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
                float distSq = dot(diff, diff);

                if (distSq <= uval_radius.w) {
                    vec3 frontPos = diff;
                    vec3 backPos = diff - backOffset;

                    vec2 cosH;
                    cosH.x = dot(frontPos, projNormal) * inversesqrt(dot(frontPos, frontPos));
                    cosH.y = dot(backPos, projNormal) * inversesqrt(dot(backPos, backPos));

                    cosH = saturate(cosH);

                    vec2 H = acosFastPositive(cosH) * RCP_PI_HALF_CONST;

                    uint stepSectorBits = calcSectorBits(min(H.x, H.y), max(H.x, H.y));

                    uint ilBit = bitCount(stepSectorBits & ~sampleSectorBits);

                    uint ilCond = uint(ilBit != 0);
                    ilCond &= uint(all(greaterThanEqual(sampleUV, vec2(0.0))));
                    ilCond &= uint(all(lessThanEqual(sampleUV, vec2(1.0))));

                    if (bool(ilCond)) {
                        vec4 sample1 = textureLod(usam_solidLighting1, sampleUV, sampleLod * 0.5);
                        vec4 sample2 = textureLod(usam_solidLighting2, sampleUV, sampleLod * 0.5);
                        vec3 sampleNormal = sample2.rgb * 2.0 - 1.0;
                        vec3 direct = sample1.rgb;

                        float ilDiffuse = (float(ilBit) / float(SECTOR_COUNT));
                        float rcpDist = fastRcpSqrtNR0(distSq);
                        ilDiffuse *= saturate(dot(gs_viewNormal, diff) * rcpDist);
                        ilDiffuse *= saturate(dot(sampleNormal, -diff) * rcpDist);

                        outValue.rgb += ilDiffuse * direct;
                    }

                    sampleSectorBits |= stepSectorBits;
                }
                sampleLod = min(sampleLod + lodStep, maxLod);
                sampleTexelDist += stepTexelSize;
            }

            // Blute force cosine weighted hemisphere LOL
            for (uint bitIndex = 0u; bitIndex < 32; bitIndex++) {
                outValue.a += float((sampleSectorBits >> bitIndex) & 1u) * WEIGHTS[bitIndex];
            }
        }

        outValue /= float(SSILVB_SAMPLE_SLICES);
        outValue.rgb *= 2.0;

        outValue.a = linearStep(0.01, 1.0, outValue.a);
        outValue.a *= uval_aoStrength.x;
        outValue.a = saturate(1.0 - outValue.a);
        outValue.a = pow(outValue.a, uval_aoStrength.y);
    }
}