#version 460 core
//#pragma optionNV (unroll all)

#include "../utils/Rand.glsl"
#include "../utils/R2Seqs.glsl"
#include "../utils/Math.glsl"
#include "../GBuffers.glsl"
#include "../GenericGBuffer.glsl"
#include "../GlobalCamera.glsl"

#define MAX_RADIUS_PIXELS 128

uniform float uval_tangentBias;
uniform float uval_radius;
uniform vec4 uval_aoRes;
uniform vec2 uval_focalLen;
uniform float uval_aoStrength;

float v_r = uval_radius;
float v_r2 = v_r * v_r;
float v_negInvR2 = -1.0 / v_r2;

in vec2 fTexCoord;
in mat3 fNormalMatrix;

layout(location = 0) out vec4 outValue;

vec3 getViewPos(vec2 uv) {
    GBufferData data;
    gbuffer_unpack1(texture(usam_gData1, uv), data);
    return (ubo_camera.modelView * vec4(data.worldCoord, 1.0)).xyz;
}

float tan2sin(float x) {
    return x * inversesqrt(x * x + 1.0);
}

float invLength(vec2 v) {
    return inversesqrt(dot(v, v));
}

float tangent(vec3 v) {
    return v.z * invLength(v.xy);
}

float tangent(vec3 P, vec3 S) {
    return -(P.z - S.z) * invLength(S.xy - P.xy);
}

float length2(vec3 V) {
    return dot(V, V);
}

vec3 minDiff(vec3 P, vec3 Pr, vec3 Pl) {
    vec3 V1 = Pr - P;
    vec3 V2 = P - Pl;
    return (length2(V1) < length2(V2)) ? V1 : V2;
}

vec2 snapUVOffset(vec2 uv) {
    return round(uv * uval_aoRes.xy) * uval_aoRes.zw;
}

float falloff(float d2) {
    return d2 * v_negInvR2 + 1.0f;
}

float HorizonOcclusion(vec2 deltaUV, vec3 P, vec3 dPdu, vec3 dPdv, float randstep, float numSamples) {
    float ao = 0;

    // Offset the first coord with some noise
    vec2 uv = fTexCoord + snapUVOffset(randstep*deltaUV);
    deltaUV = snapUVOffset(deltaUV);

    // Calculate the tangent vector
    vec3 T = deltaUV.x * dPdu + deltaUV.y * dPdv;

    // Get the angle of the tangent vector from the viewspace axis
    float tanH = tangent(T) + uval_tangentBias;
    float sinH = tan2sin(tanH);

    // Sample to find the maximum angle
    for (float s = 1; s <= numSamples; ++s) {
        uv += deltaUV;
        vec3 S = getViewPos(uv);
        float tanS = tangent(P, S);
        float d2 = length2(S - P);

        // Is the sample within the radius and the angle greater?
        if (d2 < v_r2 && tanS > tanH) {
            float sinS = tan2sin(tanS);
            // Apply falloff based on the distance
            ao += falloff(d2) * (sinS - sinH);

            tanH = tanS;
            sinH = sinS;
        }
    }

    return ao;
}

vec2 RotateDirections(vec2 Dir, vec2 CosSin) {
    return vec2(Dir.x*CosSin.x - Dir.y*CosSin.y,
        Dir.x*CosSin.y + Dir.y*CosSin.x);
}

void ComputeSteps(inout vec2 stepSizeUv, inout float numSteps, float rayRadiusPix, float rand) {
    // Avoid oversampling if numSteps is greater than the kernel radius in pixels
    numSteps = min(HBAO_STEP_SAMPLES, rayRadiusPix);

    // Divide by Ns+1 so that the farthest samples are not fully attenuated
    float stepSizePix = rayRadiusPix / (numSteps + 1);

    // Clamp numSteps if it is greater than the max kernel footprint
    float maxNumSteps = MAX_RADIUS_PIXELS / stepSizePix;
    if (maxNumSteps < numSteps){
        // Use dithering to avoid AO discontinuities
        numSteps = floor(maxNumSteps + rand);
        numSteps = max(numSteps, 1);
        stepSizePix = MAX_RADIUS_PIXELS / numSteps;
    }

    // Step size in uv space
    stepSizeUv = stepSizePix * uval_aoRes.zw;
}

void main(void) {
    GBufferData gDataC;
    gbuffer_unpack1(texture(usam_gData1, fTexCoord), gDataC);

    vec3 viewCoordC = (ubo_camera.modelView * vec4(gDataC.worldCoord, 1.0)).xyz;

    // Sample neighboring pixels
    vec3 viewCoordR = getViewPos(fTexCoord + vec2(uval_aoRes.z, 0));
    vec3 viewCoordL = getViewPos(fTexCoord + vec2(-uval_aoRes.z, 0));
    vec3 viewCoordT = getViewPos(fTexCoord + vec2(0, uval_aoRes.w));
    vec3 viewCoordB = getViewPos(fTexCoord + vec2(0, -uval_aoRes.w));

    // Calculate tangent basis vectors using the minimu difference
    vec3 dPdu = minDiff(viewCoordC, viewCoordR, viewCoordL);
    vec3 dPdv = minDiff(viewCoordC, viewCoordR, viewCoordB) * (uval_aoRes.y * uval_aoRes.z);

    uint worldCoordRand0 = uint(rand(gDataC.worldCoord.xyz) * 1024.0);
    uint worldCoordRand1 = uint(rand(gDataC.worldCoord.yzx) * 1024.0);
    uint worldCoordRand2 = uint(rand(gDataC.worldCoord.zxy) * 1024.0);

    vec3 random = vec3(
        r2Seq1(worldCoordRand0 + ubo_camera.gFrameIndex),
        r2Seq1(worldCoordRand1 + ubo_camera.gFrameIndex),
        r2Seq1(worldCoordRand2 + ubo_camera.gFrameIndex)
    );

    // Calculate the projected size of the hemisphere
    vec2 rayRadiusUV = 0.5 * v_r * uval_focalLen / -viewCoordC.z;
    float rayRadiusPix = rayRadiusUV.x * uval_aoRes.x;

    float ao = 1.0;

    // Make sure the radius of the evaluated hemisphere is more than a pixel
    if (rayRadiusPix > 1.0) {
        ao = 0.0;
        float numSteps;
        vec2 stepSizeUV;

        // Compute the number of steps
        ComputeSteps(stepSizeUV, numSteps, rayRadiusPix, random.z);

        float alpha = 2.0 * PI_CONST / HBAO_DIR_SAMPLES;

        // Calculate the horizon occlusion of each direction
        for (float d = 0; d < HBAO_DIR_SAMPLES; ++d)
        {
            float theta = alpha * d;

            // Apply noise to the direction
            vec2 dir = RotateDirections(vec2(cos(theta), sin(theta)), random.xy);
            vec2 deltaUV = dir * stepSizeUV;

            // Sample the pixels along the direction
            ao += HorizonOcclusion(deltaUV,
                viewCoordC,
                dPdu,
                dPdv,
                random.z,
                numSteps);
        }

        // Average the results and produce the final AO
        ao = 1.0 - ao / HBAO_DIR_SAMPLES * uval_aoStrength;
    }

    outValue.rgb = vec3(ao);
    outValue.a = ao;
}