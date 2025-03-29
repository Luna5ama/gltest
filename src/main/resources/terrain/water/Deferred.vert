#version 460 core

uniform sampler2D usam_noise4;

#include "../GlobalCamera.glsl"
#include "../ColorUtils.glsl"
#include "../TerrainVertAttrib.glsl"

#define NOISE_TEXTURE usam_noise4
#include "WaveCalc4.glsl"

uniform float uTime;
uniform vec3 originOffset;

struct VertData {
    vec3 worldCoord;
    vec3 worldNormal;
    vec3 waveCoord;
    vec2 texCoord;
    vec2 lightCoord;
    vec4 colorMul;
};

out VertData vertData;
out uint frag_mdlAttrib;

const vec3 COORD_CONVERT = vec3(2.51773861295491E-4);
const vec4 WATER_MUL = vec4(0.6, 0.48, 0.24, 1.0);

void main() {
    frag_mdlAttrib = vMdlAttrib;
    bool vertIsWater = bool((frag_mdlAttrib >> 3u) & 1u);
    vertData.waveCoord = fma(vPos, COORD_CONVERT, vChunkOffset * 16.0);
    vertData.worldCoord = vertData.waveCoord + originOffset;

    if (vertIsWater) {
        float waveHeight = calcWave(vertData.waveCoord.xz, uTime);
        vertData.worldCoord.y -= waveHeight;
    }

    gl_Position = ubo_camera.combined * vec4(vertData.worldCoord, 1.0);

    vertData.colorMul = vec4(vColorMul, 1.0);
    vertData.colorMul = bool(vertIsWater) ? vertData.colorMul * WATER_MUL : vertData.colorMul;

    vertData.texCoord = vTexCoord;
    vertData.worldNormal = vNormal;
    vertData.lightCoord = vLightCoord;
}