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

float stepC() {
    return exp2(float(GTAO_SAMPLE_STEPS)) - 1.0;
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

vec2 linearStep(vec2 edge0, vec2 edge1, vec2 x) {
    return clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
}

// https://graphics.stanford.edu/%7Eseander/bithacks.html
uint bitCount(uint value) {
    value = value - ((value >> 1u) & 0x55555555u);
    value = (value & 0x33333333u) + ((value >> 2u) & 0x33333333u);
    return ((value + (value >> 4u) & 0xF0F0F0Fu) * 0x1010101u) >> 24u;
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

vec2 acosF(vec2 inX) {
    vec2 x1 = abs(inX);
    vec2 x2 = x1 * x1;
    vec2 x3 = x2 * x1;
    vec2 s;

    s = -0.2121144f * x1 + 1.5707288f;
    s = 0.0742610f * x2 + s;
    s = -0.0187293f * x3 + s;
    s = sqrt(1.0f - x1) * s;

    return s;
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

    vec3 frontOffset = viewDir * length(gs_viewCoord) * uval_thickness.x;
    vec3 backOffset = frontOffset + viewDir * uval_thickness.y;

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

        float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
        float baseLod = clamp(radiusToBaseLod(maxDist), 0.0, maxLod);
        baseLod += r2Seq1(ubo_cameraDefault.gFrameIndex + uint(IGN(gl_FragCoord.xy) * 1024.0)) - 0.5;

        float sampleLod = baseLod;
        float sampleTexelDist = 1.5;

        uint sampleSectorBits = 0u;

        for (int stepIndex = 0; stepIndex < GTAO_SAMPLE_STEPS * 2; stepIndex++) {
            float fi = float(stepIndex);

            float sampleLodTexelSize = lodTexelSize(sampleLod) * 1.0;
            float sampleLodTexelSizeHalf = sampleLodTexelSize * 0.5;
            sampleTexelDist += sampleLodTexelSizeHalf;

            vec2 sampleTexelCoord = gl_FragCoord.xy + sampleDir * sampleTexelDist;
            vec2 sampleUV = sampleTexelCoord * ubo_cameraDefault.screenSize.zw;

            float sampleViewZ = textureLod(usam_linearDepth, sampleUV, sampleLod).r;
            vec3 sampleViewXYZ = toViewSpace(sampleUV, sampleViewZ, ubo_cameraDefault.projection, ubo_cameraDefault.invProjection);
            vec3 diff = sampleViewXYZ - gs_viewCoord;

            vec2 cosH;
            cosH.x = dot(normalize(diff - frontOffset), projNormal);
            cosH.y = dot(normalize(diff - backOffset), projNormal);

            cosH = max(cosH, 0.0);
            vec2 H = (acos(cosH) / PI_HALF_CONST);
            uint stepSectorBits = calcSectorBits(min(H.x, H.y), max(H.x, H.y));
            sampleSectorBits |= stepSectorBits;

            sampleLod = min(sampleLod + 0.25, maxLod);
            sampleTexelDist += sampleLodTexelSizeHalf;
        }

        outValue.a += float(bitCount(sampleSectorBits)) / float(SECTOR_COUNT);
    }

    outValue.a /= float(GTAO_SAMPLE_SLICES);
    outValue.a *= uval_aoStrength.x;
    outValue.a = 1.0 - outValue.a;
    outValue.a = pow(outValue.a, uval_aoStrength.y);
}