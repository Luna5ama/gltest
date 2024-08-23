#version 460 core

#if defined(DEPTH_OUTPUT)
#define DATA_TYPE float
#else
#define DATA_TYPE vec4
#endif

layout(binding = 0) uniform sampler2D usam_input;

uniform int uval_level;

in vec2 fTexCoordI;

#if defined(KERNEL_AVG)
DATA_TYPE kernel(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d) {
    return (a + b + c + d) * 0.25;
}
#elif defined(KERNEL_MIN)
DATA_TYPE kernel(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d) {
    return min(min(a, b), min(c, d));
}
#elif defined(KERNEL_MAX)
DATA_TYPE kernel(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d) {
    return max(max(a, b), max(c, d));
}
#endif

#if defined(DEPTH_OUTPUT)
uniform vec2 uval_planeZ;

#if defined(DEPTH_LINEAR)
float depthToFloat(float depth) {
    return mix(uval_planeZ.x, uval_planeZ.y, depth);
}
float floatToDepth(float depth) {
    return (depth - uval_planeZ.x) / (uval_planeZ.y - uval_planeZ.x);
}
#elif defined(DEPTH_LOG)
float depthToFloat(float depth) {
    return (uval_planeZ.x * uval_planeZ.y) / (depth * (uval_planeZ.x - uval_planeZ.y) + uval_planeZ.y);
}
float floatToDepth(float depth) {
    float farMinusNear = uval_planeZ.y - uval_planeZ.x;
    float farPlusNear = uval_planeZ.y + uval_planeZ.x;
    return depth * (-farPlusNear / farMinusNear) - ((2.0 * uval_planeZ.y * uval_planeZ.x) / farMinusNear);
}
#endif

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    float c1 = texelFetch(usam_input, intTexCoord, uval_level).r;
    c1 = depthToFloat(c1);
    float c2 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 0)).r;
    c2 = depthToFloat(c2);
    float c3 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(0, 1)).r;
    c3 = depthToFloat(c3);
    float c4 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 1)).r;
    c4 = depthToFloat(c4);

    float v = kernel(c1, c2, c3, c4);

    gl_FragDepth = floatToDepth(v);
}
#else
out vec4 fragColor;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    vec4 c1 = texelFetch(usam_input, intTexCoord, uval_level);
    vec4 c2 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 0));
    vec4 c3 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(0, 1));
    vec4 c4 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 1));

    fragColor = kernel(c1, c2, c3, c4);
}
#endif