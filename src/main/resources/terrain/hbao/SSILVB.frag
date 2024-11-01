#version 460 core
// References:
// Screen Space Indirect Lighting with Visibility Bitmask
// https://arxiv.org/pdf/2301.11376
//
// GT-VBAO
// https://www.shadertoy.com/view/XXGSDd9
//#pragma optionNV (unroll all)
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

uint calcSectorBits(vec2 H) {
    uvec2 HInt = uvec2(floor(H * 32.0));
    uint ones = 0xFFFFFFFFu; // don't inline here! ANGLE bug: https://issues.angleproject.org/issues/353039526
    uint mX = HInt.x < 32u ? ones << HInt.x : 0u;
    uint mY = HInt.y != 0u ? ones >> (32u - HInt.y) : 0u;
   return mX & mY;
}

vec2 sliceRelCDFCos(vec2 x, float angN, float sinN, float cosN) {
    vec2 phi = x * PI_CONST - PI_HALF_CONST;

    vec2 c = step(angN, phi);
    vec2 n0 = c * 2.0 + 1.0;
    vec2 n1 = c * -2.0 + 1.0;
    vec2 n2 = c * 4.0;

    vec2 t0 = n0 * cosN + n1 * cos(angN - 2.0 * phi) + (n2 * angN + (n1 * 2.0) * phi + PI_CONST) * sinN;
    float t1 = 4.0 * (cosN + angN * sinN);

    return t0 / t1;
}

vec2 sliceRelCDFUniform(vec2 x, float angN, float sinN, float cosN) {
    vec2 phi = x * PI_CONST - PI_HALF_CONST;

    vec2 c = step(angN, phi);
    vec2 m0 = c * 2.0;
    vec2 m1 = c * -2.0 + 1.0;

    return  0.5 * (m0 + m1 * cos(angN - phi) + sinN);
}

#define NOISE_FRAME ubo_cameraDefault.gFrameIndex
//#define NOISE_FRAME 0u

void main() {
    outValue = vec4(1.0);
    if (textureLod(usam_gDepth, fTexCoord, 0).r != 0.0) {
        GBufferData gs_data;
        gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

        vec3 gs_viewCoord = (ubo_cameraDefault.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
        vec3 gs_viewNormal = textureLod(usam_solidLighting2, fTexCoord, 0.0).rgb * 2.0 - 1.0;

        vec3 viewLightDir = mat3(ubo_cameraDefault.modelView) * uShadowMap.lightDir;
        vec3 viewDir = normalize(-gs_viewCoord);

        float sampleAngleDelta = PI_CONST / SSILVB_SAMPLE_SLICES;
        float baseIGN = IGN(gl_FragCoord.xy, NOISE_FRAME);
        float initialAngle = baseIGN * sampleAngleDelta;
        vec2 sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy * ubo_cameraDefault.screenSize.xy;

        outValue = vec4(0.0);

        uint r2Index = (hash21(uvec2(gl_FragCoord.yx)) & 511u) + NOISE_FRAME;
        float baseSampleLod = r2Seq1(r2Index) * 0.6 - 0.3;

        for (uint sliceIndex = 0; sliceIndex < SSILVB_SAMPLE_SLICES; sliceIndex++) {
            float sampleAngle = initialAngle + sampleAngleDelta * float(sliceIndex);
            vec2 sampleDir0 = vec2(cos(sampleAngle), sin(sampleAngle));
            uint sampleSectorBits = 0u;

            for (int i = 0; i < 2u; i++) {
                float flip = float((i << 1) - 1);
                vec2 sampleDir = sampleDir0 * flip;

                vec3 planeNormal = normalize(cross(vec3(sampleDir, 0.0), viewDir));
                vec3 projNormal = normalize(gs_viewNormal - planeNormal * dot(gs_viewNormal, planeNormal));
                vec3 T = cross(planeNormal, projNormal);
                float cosN = dot(projNormal, viewDir);
                float angN = -sign(dot(viewDir, T)) * acosFastPositive(cosN);
                float sinN = sin(angN);

                float maxDist = length(sampleDir * sphereRadius);

                float lodStep = radiusToLodStep(maxDist);
                float sampleLod = baseSampleLod;

                float sampleTexelDist = 1.5;

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
                        vec3 backOffset = viewDir * uval_thickness * float(stepIndex);
                        vec3 backPos = diff - backOffset;

                        vec2 cosH;
                        cosH.x = dot(frontPos, viewDir) * inversesqrt(dot(frontPos, frontPos));
                        cosH.y = dot(backPos, viewDir) * inversesqrt(dot(backPos, backPos));

                        vec2 H = vec2(acosFast(cosH.x), acosFast(cosH.y));

                        H = saturate((H + angN) * RCP_PI_CONST + 0.5);
                        H = vec2(min(H.x, H.y), max(H.x, H.y));
                        H = sliceRelCDFCos(H, angN, sinN, cosN);
//                        H = sliceRelCDFUniform(H, angN, sinN, cosN);

//                        H = saturate(H + baseIGN * (1.0 / 32.0));

                        uint stepSectorBits = calcSectorBits(H);

                        uint ilCond = uint((stepSectorBits & ~sampleSectorBits) != 0);
                        ilCond &= uint(all(greaterThanEqual(sampleUV, vec2(0.0))));
                        ilCond &= uint(all(lessThanEqual(sampleUV, vec2(1.0))));

                        if (bool(ilCond)) {
                            vec4 sample1 = textureLod(usam_solidLighting1, sampleUV, sampleLod * 0.5);
                            vec4 sample2 = textureLod(usam_solidLighting2, sampleUV, sampleLod * 0.5);
                            vec3 sampleNormal = sample2.rgb * 2.0 - 1.0;
                            vec3 direct = sample1.rgb;

                            uint ilBit = bitCount(stepSectorBits & ~sampleSectorBits);
                            float ilDiffuse = (float(ilBit) * (1.0 / 32.0));
                            float rcpDist = fastRcpSqrtNR0(distSq);
                            ilDiffuse *= saturate(dot(gs_viewNormal, diff) * rcpDist);
                            ilDiffuse *= saturate(dot(sampleNormal, -diff) * rcpDist);

                            outValue.rgb += ilDiffuse * direct;
                        }

                        sampleSectorBits |= stepSectorBits;
                    }

                    sampleLod = sampleLod + lodStep;
                    sampleTexelDist += stepTexelSize;
                }
            }

            outValue.a += float(bitCount(sampleSectorBits)) * (1.0 / 32.0);
        }
        outValue *= (0.5 / SSILVB_SAMPLE_SLICES);
        outValue.rgb *= 2.0;

        outValue.a *= uval_aoStrength.x;
        outValue.a = saturate(1.0 - outValue.a);
        outValue.a = pow(outValue.a, uval_aoStrength.y);
    }
}