version 460 core
#pragma optionNV (unroll all)

layout(binding = 2) uniform sampler2D noiseTexture;

layout(quads, fractional_odd_spacing, ccw) in;

uniform float uTime;
uniform vec3 originOffset;

#include "../ColorUtils.glsl"
#include "../GlobalCamera.glsl"

#define NOISE_TEXTURE noiseTexture
#include "WaveCalc4.glsl"

struct VertData {
    vec3 colorMul;
    vec2 texCoord;
    vec3 normal;
    vec2 lightCoord;
};

struct GeomData {
    vec3 worldCoord;
    vec3 viewCoord;
    vec3 colorMul;
    vec2 texCoord;
    vec2 lightCoord;
    vec3 waveCoord;
};

in VertData tessData[];
in flat int tessIsWater[];

out GeomData fragData;
out flat int fragIsWater;

#define TESS_X gl_TessCoord.x
#define TESS_Y gl_TessCoord.y

void calcColorMul() {
    vec3 c00 = tessData[0].colorMul;
    vec3 c01 = tessData[1].colorMul;
    vec3 c10 = tessData[2].colorMul;
    vec3 c11 = tessData[3].colorMul;

    vec3 c0 = (c01 - c00) * TESS_Y + c00;
    vec3 c1 = (c11 - c10) * TESS_Y + c10;
    fragData.colorMul = (c1 - c0) * TESS_X + c0;
}

void calcTexcoord() {
    vec2 t00 = tessData[0].texCoord;
    vec2 t01 = tessData[1].texCoord;
    vec2 t10 = tessData[2].texCoord;
    vec2 t11 = tessData[3].texCoord;

    vec2 t0 = (t01 - t00) * TESS_Y + t00;
    vec2 t1 = (t11 - t10) * TESS_Y + t10;
    fragData.texCoord = (t1 - t0) * TESS_X + t0;
}

void calcLightcoord() {
    vec2 l00 = tessData[0].lightCoord;
    vec2 l01 = tessData[1].lightCoord;
    vec2 l10 = tessData[2].lightCoord;
    vec2 l11 = tessData[3].lightCoord;

    vec2 l0 = (l01 - l00) * TESS_Y + l00;
    vec2 l1 = (l11 - l10) * TESS_Y + l10;
    fragData.lightCoord = (l1 - l0) * TESS_X + l0;
}

void main() {
    vec4 p00 = gl_in[0].gl_Position;
    vec4 p01 = gl_in[1].gl_Position;
    vec4 p10 = gl_in[2].gl_Position;
    vec4 p11 = gl_in[3].gl_Position;

    // bilinearly interpolate position coordinate across patch
    vec4 p0 = (p01 - p00) * TESS_Y + p00;
    vec4 p1 = (p11 - p10) * TESS_Y + p10;
    vec4 coord = (p1 - p0) * TESS_X + p0;

    vec3 waveCoord = coord.xyz;

//    if (bool(fragIsWater)){
        float waveHeight = calcWave(waveCoord.xz, uTime);
        coord.y += 0.1;
        coord.y -= waveHeight;
//    }

    coord.xyz += originOffset;

    vec3 worldCoord = coord.xyz;
    vec4 viewCoord = modelView * vec4(coord.xyz, 1.0);

    fragData.worldCoord = worldCoord;
    fragData.viewCoord = viewCoord.xyz;
    fragData.waveCoord = waveCoord;
    gl_Position = projection * viewCoord;
    
    calcColorMul();
    calcTexcoord();
    calcLightcoord();

    fragIsWater = tessIsWater[0];
}