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

uniform float uval_thickness;
uniform vec4 uval_radius;
uniform vec2 uval_aoStrength;
uniform vec3 uval_projectParams;

#include "../solid/Texs.glsl"
#include "../solid/Utils.glsl"

in vec2 fTexCoordI;
in vec2 fTexCoord;

layout(location = 0) out vec4 outValue;

#define F_INDEX ubo_camera.gFrameIndex

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

vec3 gs_viewCoord;
vec3 gs_viewNormal;
vec3 viewDir;

vec2 sphereRadius;

float sampleH(uint dirIndex, vec2 sampleDir) {
    vec2 texelCoord = gl_FragCoord.xy;
    
    float maxDistX = sampleDir.x != 0.0 ? (sampleDir.x >= 0.0 ? (ubo_camera.screenSize.x - texelCoord.x) / sampleDir.x : -texelCoord.x / sampleDir.x) : 1e6;
    float maxDistY = sampleDir.y != 0.0 ? (sampleDir.y < 0.0 ? (-texelCoord.y / sampleDir.y) : (ubo_camera.screenSize.y - texelCoord.y) / sampleDir.y) : 1e6;
    float maxDist = min(maxDistX, maxDistY);
    maxDist = min(maxDist, length(sampleDir * sphereRadius));

    float maxLod = float(textureQueryLevels(usam_linearDepth)) - 1.0;
    float baseLod = clamp(radiusToBaseLod(maxDist), 0.0, maxLod);
    baseLod += r2Seq1(ubo_camera.gFrameIndex + uint(IGN(gl_FragCoord.xy) * 1024.0)) - 0.5;

    float sampleLod = baseLod;
    float sampleTexelDist = 1.5;

    float maxCosH = -1.0;

    for (int stepIndex = 0; stepIndex < GTAO_SAMPLE_STEPS * 2; stepIndex++) {
        float fi = float(stepIndex);

        float sampleLodTexelSize = lodTexelSize(sampleLod) * 4.0;
        float sampleLodTexelSizeHalf = sampleLodTexelSize * 0.5;
        sampleTexelDist += sampleLodTexelSizeHalf;

        vec2 sampleTexelCoord = texelCoord + sampleDir * sampleTexelDist;
        vec2 sampleUV = sampleTexelCoord * ubo_camera.screenSize.zw;

        float sampleViewZ = textureLod(usam_linearDepth, sampleUV, sampleLod).r;
        vec3 sampleViewXYZ = toViewSpace(sampleUV, sampleViewZ, ubo_camera.projection, ubo_camera.invProjection);
        vec3 diff = sampleViewXYZ - gs_viewCoord;

        float distSq = dot(diff, diff);
        float cosH = dot(diff, viewDir) * fastRcpSqrtNR0(max(distSq, 0.000001));
        float falloff = linearStep(uval_radius.z, uval_radius.w, distSq);
        cosH = mix(cosH, maxCosH, falloff);
        maxCosH = max(maxCosH, cosH);
        maxCosH = mix(maxCosH, cosH, uval_thickness);

        sampleLod = min(sampleLod + 0.25, maxLod);
        sampleTexelDist += sampleLodTexelSizeHalf;
    }

    return acosFast4(clamp(maxCosH, -1.0, 1.0));
}

void main() {
    if (textureLod(usam_gDepth, fTexCoord, 0).r == 0.0) {
        outValue = vec4(1.0);
        return;
    }

    GBufferData gs_data;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gs_data);

    vec3 worldNormalThis = texture(usam_solidLighting2, fTexCoord).rgb;

    gs_viewCoord = (ubo_camera.modelView * vec4(gs_data.worldCoord, 1.0)).xyz;
    gs_viewNormal = mat3(ubo_camera.modelView) * worldNormalThis;
    viewDir = normalize(-gs_viewCoord);

    float sampleAngleDelta = PI_CONST / GTAO_SAMPLE_SLICES;
    float initialAngle = IGN(gl_FragCoord.xy, ubo_camera.gFrameIndex) * sampleAngleDelta;
    sphereRadius = (uval_radius.x / -gs_viewCoord.z) * uval_projectParams.xy * ubo_camera.screenSize.xy;

    outValue = vec4(0.0);

    for (uint sliceIndex = 0; sliceIndex < GTAO_SAMPLE_SLICES; sliceIndex++) {
        float sampleAngle = initialAngle + sampleAngleDelta * float(sliceIndex);
        vec2 sampleDir = vec2(cos(sampleAngle), sin(sampleAngle));
        uint dirIndex = sliceIndex << 2u;
        vec2 h;
        h.x = sampleH(dirIndex, sampleDir);
        h.y = sampleH(dirIndex | 1u, -sampleDir);

        vec3 planeNormal = normalize(cross(vec3(sampleDir, 0.0), viewDir));
        vec3 perp = cross(viewDir, planeNormal);
        vec3 projNormal = gs_viewNormal - planeNormal * dot(gs_viewNormal, planeNormal);

        float lenProjNormal = length(projNormal) + 0.000001f;
        float recipMag = 1.0 / lenProjNormal;

        float cosAng = dot(projNormal, perp) * recipMag;
        float gamma = acosFast4(cosAng) - 0.5 * PI;
        float cosGamma = dot(projNormal, viewDir) * recipMag;
        float sinGamma = cosAng * (-2.0);

        // clamp to normal hemisphere
        h.x = gamma + max(-h.x - gamma, -(0.5 * PI));
        h.y = gamma + min(h.y - gamma, (0.5 * PI));

        // See Activision GTAO paper: https://www.activision.com/cdn/research/s2016_pbs_activision_occlusion.pptx
        // Integrate arcCos weight.
        float ao = ((lenProjNormal) * 0.25 *
        ((h.x * sinGamma + cosGamma - cos(2.0 * h.x - gamma)) +
        (h.y * sinGamma + cosGamma - cos(2.0 * h.y - gamma))));

        outValue.a += ao;
    }

    outValue.a /= float(GTAO_SAMPLE_SLICES);

    outValue.a = 1.0 - outValue.a;
    outValue.a *= uval_aoStrength.x;
    outValue.a = 1.0 - outValue.a;
    outValue.a = pow(outValue.a, uval_aoStrength.y);
}