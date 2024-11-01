#version 460 core
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

uniform vec2 uval_thickness;
uniform float uval_maxCosH;
uniform vec4 uval_radius;
uniform vec2 uval_aoStrength;
uniform vec3 uval_projectParams;

#include "../solid/Texs.glsl"
#include "../solid/Utils.glsl"

in vec2 fTexCoordI;
in vec2 fTexCoord;

layout(location = 0) out vec3 rt_outGI;
layout(location = 1) out float rt_outAO;

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

float calcHorizonAngles(vec3 projNormal, vec3 pos) {
    float cosH = dot(pos, projNormal) * inversesqrt(dot(pos, pos));
    cosH = saturate(cosH);
    return acosFastPositive(cosH) * RCP_PI_HALF_CONST;
}

void main() {
    rt_outGI = vec3(0.0);
    rt_outAO = 1.0;

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

        uint r2Index = (hash21(uvec2(gl_FragCoord.xy)) & 1023u) + NOISE_FRAME;
        float baseSampleLod = r2Seq1(r2Index) * 0.5 - 0.25;

        rt_outAO = 0.0;

        for (uint sliceIndex = 0; sliceIndex < SSILVB_SAMPLE_SLICES; sliceIndex++) {
            float sampleAngle = initialAngle + sampleAngleDelta * float(sliceIndex);
            vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));

            vec3 planeNormal = normalize(cross(vec3(sampleDir, 0.0), viewDir));
            vec3 sliceTangent = cross(viewDir, planeNormal);
            vec3 projNormal = normalize(gs_viewNormal - planeNormal * dot(gs_viewNormal, planeNormal));

            float maxDist = length(sampleDir * sphereRadius);

            float lodStep = radiusToLodStep(maxDist);
            float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
            float sampleLod = baseSampleLod;

            float sampleTexelDist = 1.5;
            uint aoSectionBits = 0u;
            uint giSectionBits = 0u;

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
                    float frontH = calcHorizonAngles(projNormal, frontPos);

                    uint aoStepSectorBits;
                    {
                        vec3 backOffset = viewDir * uval_thickness.x;
                        vec3 backPos = diff - backOffset;
                        float backH = calcHorizonAngles(projNormal, backPos);
                        aoStepSectorBits = calcSectorBits(min(frontH, backH), max(frontH, backH));
                    }

                    uint giStepSectorBits;
                    {
                        vec3 backOffset = viewDir * uval_thickness.y * float(stepIndex);
                        vec3 backPos = diff - backOffset;
                        float backH = calcHorizonAngles(projNormal, backPos);
                        giStepSectorBits = calcSectorBits(min(frontH, backH), max(frontH, backH));
                    }

                    uint ilBit = bitCount(giStepSectorBits & ~giSectionBits);
                    aoSectionBits |= aoStepSectorBits;
                    giSectionBits |= giStepSectorBits;

                    uint ilCond = uint(ilBit != 0);
                    ilCond &= uint(all(greaterThanEqual(sampleUV, vec2(0.0))));
                    ilCond &= uint(all(lessThanEqual(sampleUV, vec2(1.0))));

                    if (bool(ilCond)) {
                        vec4 sample1 = textureLod(usam_solidLighting1, sampleUV, sampleLod * 0.5);
                        vec4 sample2 = textureLod(usam_solidLighting2, sampleUV, sampleLod * 0.5);
                        vec3 sampleNormal = sample2.rgb * 2.0 - 1.0;
                        vec3 direct = sample1.rgb;

                        float ilBitCoeff = (float(ilBit) / 32.0);
                        float rcpDist = fastRcpSqrtNR0(distSq);
                        float emitterCos = dot(sampleNormal, -diff) * rcpDist;
                        emitterCos = mix(0.5 + linearStep(-0.6, 0.0, emitterCos), emitterCos, step(sample2.a, 0.0));
                        emitterCos = saturate(emitterCos);
                        float receiverCos = saturate(dot(gs_viewNormal, diff) * rcpDist);

                        rt_outGI += ilBitCoeff * emitterCos * receiverCos * direct;
                    }
                }
                sampleLod = min(sampleLod + lodStep, maxLod);
                sampleTexelDist += stepTexelSize;
            }

            // Blute force cosine weighted hemisphere LOL
            for (uint bitIndex = 0u; bitIndex < 32; bitIndex++) {
                rt_outAO += float((aoSectionBits >> bitIndex) & 1u) * WEIGHTS[bitIndex];
            }
        }

        rt_outGI /= float(SSILVB_SAMPLE_SLICES);
        rt_outGI *= 8.0;
        rt_outGI = min(rt_outGI, 128.0);

        rt_outAO /= float(SSILVB_SAMPLE_SLICES);
        rt_outAO *= uval_aoStrength.x;
        rt_outAO = saturate(1.0 - rt_outAO);
        rt_outAO = pow(rt_outAO, uval_aoStrength.y);
    }
}