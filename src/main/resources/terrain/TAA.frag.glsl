#version 460 core

#include "GBuffers.glsl"
#include "GenericGBuffer.glsl"
#include "GlobalCamera.glsl"
#include "utils/Math.glsl"
#include "vbuffer/VelocityBufferSamplers.glsl"
#include "NDPacking.glsl"

uniform sampler2D usam_prevFrame;
uniform sampler2D usam_mainFbo_inputColor;
uniform sampler2D usam_lastMixWeight;

uniform vec3 uClampPixel = vec3(0.5, 0.1, 0.9);
uniform vec3 uClampWorld = vec3(0.5, 0.1, 0.9);
uniform vec3 uMix = vec3(0.5, 0.9, 0.97);
uniform vec2 uWorldVelDiff = vec2(0.5, 0.0);
uniform vec2 uWorldVelCurr = vec2(0.5, 0.0);
uniform vec2 uPixelVelocity = vec2(0.5, 0.0);
uniform vec3 uCameraDelta;

in vec2 fTexCoord;
in vec2 fTexCoordI;
in vec2 fJitterTexCoord;

layout(location = 0) out vec4 rt_mainOutput;
layout(location = 1) out vec4 rt_lastMixWeight;

vec3 depthToWorldCoord(Camera camera, float depth, vec2 texCoord) {
    vec4 clipCoord = vec4(texCoord, depth, 1.0);
    clipCoord.xy = clipCoord.xy * 2.0 - 1.0;
    vec4 worldCoord = camera.invCombined * clipCoord;
    worldCoord.xyz /= worldCoord.w;
    return worldCoord.xyz;
}

void updateNearMinMax(vec4 currColor, inout vec4 nearMin, inout vec4 nearMax) {
    nearMin = min(nearMin, currColor);
    nearMax = max(nearMax, currColor);
}

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);

    vec2 uvVelocity = texelFetch(usam_velocity_uvVelocity, intTexCoord, 0).xy;

    vec2 prevTexCoord = fTexCoord - uvVelocity;

    vec4 lastColor = max(texture(usam_prevFrame, prevTexCoord), 0.0);
    vec4 currColor = max(texture(usam_mainFbo_inputColor, fJitterTexCoord), 0.0);
    currColor = saturate(currColor);
    lastColor = saturate(lastColor);

    vec4 nearMin1 = currColor;
    vec4 nearMax1 = currColor;
    
    vec4 nearCurr;
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(-1, 0));
    updateNearMinMax(nearCurr, nearMin1, nearMax1);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(1, 0));
    updateNearMinMax(nearCurr, nearMin1, nearMax1);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, -1));
    updateNearMinMax(nearCurr, nearMin1, nearMax1);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, 1));
    updateNearMinMax(nearCurr, nearMin1, nearMax1);
    
    vec4 nearMin2 = nearMin1;
    vec4 nearMax2 = nearMax1;
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(-1, -1));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(1, 1));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(1, -1));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(-1, 1));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(-2, 0));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(2, 0));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, -2));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);
    
    nearCurr = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, 2));
    updateNearMinMax(nearCurr, nearMin2, nearMax2);

    float uvSpeed = length(uvVelocity);

    vec2 projFlags = texture(usam_velocity_projFlags, fTexCoord).rg;
    projFlags = max(projFlags, textureOffset(usam_velocity_projFlags, fTexCoord, ivec2(-1, 0)).rg);
    projFlags = max(projFlags, textureOffset(usam_velocity_projFlags, fTexCoord, ivec2(1, 0)).rg);
    projFlags = max(projFlags, textureOffset(usam_velocity_projFlags, fTexCoord, ivec2(0, -1)).rg);
    projFlags = max(projFlags, textureOffset(usam_velocity_projFlags, fTexCoord, ivec2(0, 1)).rg);

    float frustumTest = float(projFlags.x > 0.0);
    float newPixel = float(projFlags.y > 0.0);

    float clampRatio1 = 0.1;
    clampRatio1 += newPixel * 0.5;
    clampRatio1 += length(uCameraDelta) * 1.0;
    clampRatio1 += frustumTest * 0.5;
    clampRatio1 = saturate(clampRatio1);

    float clampRatio2 = 0.05;
    clampRatio2 += length(uCameraDelta) * 2.0;

    lastColor = mix(lastColor, clamp(lastColor, nearMin2, nearMax2), clampRatio2);
    lastColor = mix(lastColor, clamp(lastColor, nearMin1, nearMax1), clampRatio1);

    float lastMixWeight = texture(usam_lastMixWeight, fTexCoord).r;
    float mixWeight = uMix.x;
    mixWeight = mix(lastMixWeight, mixWeight, 0.5);
    float realMixWeight = mixWeight;
    realMixWeight = clamp(realMixWeight, uMix.y, uMix.z);
    rt_mainOutput = mix(currColor, lastColor, realMixWeight);
    mixWeight = mix(lastMixWeight + 0.01, mixWeight, 0.05);
    mixWeight *= 1.0 - frustumTest;

    rt_lastMixWeight.rgb = vec3(mixWeight);
}
