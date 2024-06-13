layout(std140) uniform ShadowMap {
    mat4 matrix;
    mat4 invMatrix;
    vec4 shadowMapSize;
    vec4 boundSizeXY;
    vec4 planeZ;
    vec4 shadowWorldOffset;
    vec3 lightDir;
    vec2 boundSizeZ;
} uShadowMap;

vec2 rtwsm_warpTexCoord(sampler1DArray warpingMap, vec2 uv) {
    vec2 result = uv;
    vec4 warp = vec4(texture(warpingMap, vec2(result.x, 0.0)).rg, texture(warpingMap, vec2(result.y, 1.0)).rg);
    result += warp.xz;
    return result;
}

vec2 rtwsm_warpTexCoordTexelSize(sampler1DArray warpingMap, vec2 uv, out vec2 texelSize) {
    vec2 result = uv;
    vec4 warp = vec4(texture(warpingMap, vec2(result.x, 0.0)).rg, texture(warpingMap, vec2(result.y, 1.0)).rg);
    result += warp.xz;
    texelSize = warp.yw;
    return result;
}

float rtwsm_sampleShadowDepth(sampler2DShadow shadowMap, vec3 coord, float lod) {
    return textureLod(shadowMap, coord, lod);
}

float rtwsm_sampleShadowDepth(sampler2D shadowMap, vec2 coord, float lod) {
    return textureLod(shadowMap, coord, lod).r;
}

float rtwsm_sampleShadowDepthOffset(sampler2DShadow shadowMap, vec3 coord, float lod, vec2 offsetPixels) {
    vec3 offsetPos = coord;
    offsetPos.xy += offsetPixels;
    return textureLod(shadowMap, offsetPos, lod);
}

float rtwsm_sampleShadowDepthOffset(sampler2D shadowMap, vec2 coord, float lod, vec2 offsetPixels) {
    vec2 offsetPos = coord;
    offsetPos += offsetPixels;
    return textureLod(shadowMap, offsetPos, lod).r;
}

float rtwsm_linearDepth(float d) {
    return mix(uShadowMap.planeZ.x, uShadowMap.planeZ.y, d);
}