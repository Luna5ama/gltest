#version 460 core

#include "GBuffers.glsl"
#include "GenericGBuffer.glsl"
#include "GlobalCamera.glsl"
#include "vbuffer/VelocityBufferSamplers.glsl"

uniform sampler2D usam_prevFrame;
uniform sampler2D usam_mainFbo_inputColor;

uniform vec3 uClampPixel = vec3(0.5, 0.1, 0.9);
uniform vec3 uClampWorld = vec3(0.5, 0.1, 0.9);
uniform vec3 uMix = vec3(0.5, 0.9, 0.97);
uniform vec2 uWorldVelDiff = vec2(0.5, 0.0);
uniform vec2 uWorldVelCurr = vec2(0.5, 0.0);
uniform vec2 uPixelVelocity = vec2(0.5, 0.0);

in vec2 fTexCoord;
in vec2 fTexCoordI;
in vec2 fJitterTexCoord;

out vec4 fragColor;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);

    float gDepth = texelFetch(usam_gDepth, intTexCoord, 0).r;

    GBufferData gTemp;
    gbuffer_unpack1(texelFetch(usam_gData1, intTexCoord, 0), gTemp);
    vec3 gWorldCoord = gDepth == 1.0 ? vec3(0.0) : gTemp.worldCoord.xyz;

    vec2 uvVelocity = texelFetch(usam_velocity_uvVelocity, intTexCoord, 0).xy;
    vec4 worldVelocitySample = texelFetch(usam_velocity_worldVelocity, intTexCoord, 0);
    vec4 prevWorldVelocitySample = texelFetch(usam_velocity_prevWorldVelocity, intTexCoord, 0);

    vec2 prevTexCoord = fTexCoord - uvVelocity;

    vec4 lastColor = max(texture(usam_prevFrame, prevTexCoord), 0.0);
    vec4 currColor = max(texture(usam_mainFbo_inputColor, fJitterTexCoord), 0.0);

    float vCurr = length(worldVelocitySample.xyz);
    float vPrev = length(prevWorldVelocitySample.xyz);

    vec4 nearCurr0 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(-1, 0));
    vec4 nearCurr1 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(1, 0));
    vec4 nearCurr2 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, -1));
    vec4 nearCurr3 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, 1));

    vec4 nearMin = min(nearCurr0, min(nearCurr1, min(nearCurr2, nearCurr3)));
    vec4 nearMax = max(nearCurr0, max(nearCurr1, max(nearCurr2, nearCurr3)));

    float newPixel = smoothstep(0.2, 1.0, worldVelocitySample.w);
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(-1, 0)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(1, 0)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(0, -1)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(0, 1)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(-1, -1)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(1, -1)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(-1, 1)).w));
    newPixel = max(newPixel, smoothstep(0.2, 1.0, texelFetchOffset(usam_velocity_worldVelocity, intTexCoord, 0, ivec2(1, 1)).w));

    float vDiffWeight = smoothstep(0.0, 1.0, sqrt(abs(vCurr - vPrev)));

    float clampRatio = 0.0;
//    clampRatio += vDiffWeight;
//    clampRatio += newPixel;
//    clampRatio += smoothstep(0.0, 0.1, vCurr) * 0.5;
//    clampRatio = clamp(clampRatio, 0.0, 1.0);

    lastColor = mix(lastColor, clamp(lastColor, nearMin, nearMax), clampRatio);

    float mixWeight = uMix.x;

//    mixWeight *= 1.0 - newPixel;
//    mixWeight *= 1.0 - (smoothstep(0.0, 0.5, vCurr) / (max(length(gWorldCoord), 1.0) * 0.1)) * 0.4;
//    mixWeight *= max(1.0 - vDiffWeight, 0.0);
//    mixWeight = clamp(mixWeight, uMix.y, uMix.z);

    fragColor = mix(currColor, lastColor, mixWeight);
//    fragColor.rgb = mixWeight.rrr;
}
