#version 460 core
#pragma optionNV (unroll all)

uniform sampler2D usam_blockAlbedoAtlas;
uniform sampler2D usam_noise4;

uniform float uTime;

#include "../ColorUtils.glsl"
#include "../GlobalCamera.glsl"
#include "../GBuffers.glsl"
#include "../utils/Rand.glsl"
#include "../utils/R2Seqs.glsl"

#define NOISE_TEXTURE usam_noise4
#include "WaveCalc4.glsl"

struct VertData {
    vec3 worldCoord;
    vec3 worldNormal;
    vec3 waveCoord;
    vec2 texCoord;
    vec2 lightCoord;
    vec4 colorMul;
};

in VertData vertData;
in flat uint frag_mdlAttrib;

layout(location = 0) out uvec4 rt_tex1;
layout(location = 1) out uvec4 rt_tex2;

void main() {
    GBufferData outData;

    outData.texCoord = vertData.texCoord;
    outData.albedo = textureLod(usam_blockAlbedoAtlas, outData.texCoord, textureQueryLod(usam_blockAlbedoAtlas, outData.texCoord).y);
    outData.worldNormal = vec3(0.0, 1.0, 0.0);

    const float EPS_MUL = 1.0;
    const float NORMAL_WAVE_AMP = 1.0;

    vec3 a = vertData.waveCoord;
    a.y -= calcWave(a.xz, uTime) * NORMAL_WAVE_AMP;

    vec3 b = vertData.waveCoord + dFdx(vertData.waveCoord) * EPS_MUL;
    b.y -= calcWave(b.xz, uTime) * NORMAL_WAVE_AMP;

    vec3 c = vertData.waveCoord + dFdy(vertData.waveCoord) * EPS_MUL;
    c.y -= calcWave(c.xz, uTime) * NORMAL_WAVE_AMP;

    if (bool((frag_mdlAttrib >> 3u) & 1u)){
        outData.albedo = vec4(1.0);
        outData.worldNormal = normalize(cross(b - a, c - a));
    }

    uint worldCoordRand1 = uint(rand(vertData.worldCoord.xyz) * 1024.0);
    uint worldCoordRand2 = uint(rand(vertData.worldCoord.yzx) * 1024.0);

    outData.worldNormal.x += 0.004 * (r2Seq1(worldCoordRand1 + ubo_cameraDefault.gFrameIndex) * 2.0 - 1.0);
    outData.worldNormal.y += 0.004 * (r2Seq1(worldCoordRand2 + ubo_cameraDefault.gFrameIndex) * 2.0 - 1.0);

    outData.albedo *= vertData.colorMul;
    outData.albedo.rgb = srgb2linear(outData.albedo.rgb);

    outData.worldCoord = vertData.worldCoord;
    outData.materialID = frag_mdlAttrib;
    outData.lightCoord = vertData.lightCoord;
    outData.worldTangent = vec3(1.0, 0.0, 0.0);

    gbuffer_pack1(rt_tex1, outData);
    gbuffer_pack2(rt_tex2, outData);
}