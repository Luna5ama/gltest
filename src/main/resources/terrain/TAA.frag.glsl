#version 460 core

#include "GBuffers.glsl"
#include "GenericGBuffer.glsl"
#include "GlobalCamera.glsl"
#include "utils/Math.glsl"
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

    vec2 prevTexCoord = fTexCoord - uvVelocity;

    vec4 lastColor = max(texture(usam_prevFrame, prevTexCoord), 0.0);
    vec4 currColor = max(texture(usam_mainFbo_inputColor, fJitterTexCoord), 0.0);

    vec4 nearCurr0 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(-1, 0));
    vec4 nearCurr1 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(1, 0));
    vec4 nearCurr2 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, -1));
    vec4 nearCurr3 = textureOffset(usam_mainFbo_inputColor, fJitterTexCoord, ivec2(0, 1));

    vec4 nearMin = min(nearCurr0, min(nearCurr1, min(nearCurr2, nearCurr3)));
    vec4 nearMax = max(nearCurr0, max(nearCurr1, max(nearCurr2, nearCurr3)));

    float uvSpeed = length(uvVelocity);

    float clampRatio = 0.25;
    clampRatio += uvSpeed * 100.0;
    clampRatio = saturate(clampRatio);
    lastColor = mix(lastColor, clamp(lastColor, nearMin, nearMax), clampRatio);

    float loi = texture(usam_velocity_worldVelocity, fTexCoord).r;
    loi = max(loi, textureOffset(usam_velocity_worldVelocity, fTexCoord, ivec2(-1, 0)).r);
    loi = max(loi, textureOffset(usam_velocity_worldVelocity, fTexCoord, ivec2(1, 0)).r);
    loi = max(loi, textureOffset(usam_velocity_worldVelocity, fTexCoord, ivec2(0, -1)).r);
    loi = max(loi, textureOffset(usam_velocity_worldVelocity, fTexCoord, ivec2(0, 1)).r);

    float lol = 1.0 - float(loi > 0.0);
    float mixWeight = uMix.x;
    mixWeight *= lol;
    mixWeight *= saturate(1.0 - uvSpeed * 100.0);

    fragColor = mix(currColor, lastColor, mixWeight);
}
