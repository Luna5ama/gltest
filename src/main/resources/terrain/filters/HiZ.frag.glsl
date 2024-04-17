#version 460 core

#if defined(DEPTH_OUTPUT)
#define DATA_TYPE float
#else
#define DATA_TYPE vec4
#endif

layout(binding = 0) uniform sampler2D usam_input;

uniform int uval_level;

in vec2 fTexCoordI;

subroutine DATA_TYPE Kernal(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d);

subroutine(Kernal) DATA_TYPE Kernel_avg(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d) {
    return (a + b + c + d) * 0.25;
}

subroutine(Kernal) DATA_TYPE Kernel_min(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d) {
    return min(min(a, b), min(c, d));
}

subroutine(Kernal) DATA_TYPE Kernel_max(DATA_TYPE a, DATA_TYPE b, DATA_TYPE c, DATA_TYPE d) {
    return max(max(a, b), max(c, d));
}

subroutine uniform Kernal usubroutine_kernel;

#if defined(DEPTH_OUTPUT)
uniform vec2 uval_planeZ;

subroutine float DepthToFloat(float depth);
subroutine float FloatToDepth(float depth);

subroutine(DepthToFloat) float DepthToFloat_Linear(float depth) {
    return mix(uval_planeZ.x, uval_planeZ.y, depth);
}

subroutine(DepthToFloat) float DepthToFloat_Log(float depth) {
    return (uval_planeZ.x * uval_planeZ.y) / (depth * (uval_planeZ.x - uval_planeZ.y) + uval_planeZ.y);
}

subroutine(FloatToDepth) float FloatToDepth_Linear(float depth) {
    return (depth - uval_planeZ.x) / (uval_planeZ.y - uval_planeZ.x);
}

subroutine(FloatToDepth) float FloatToDepth_Log(float depth) {
    float farMinusNear = uval_planeZ.y - uval_planeZ.x;
    float farPlusNear = uval_planeZ.y + uval_planeZ.x;
    return depth * (-farPlusNear / farMinusNear) - ((2.0 * uval_planeZ.y * uval_planeZ.x) / farMinusNear);
}

subroutine uniform DepthToFloat usubroutine_depthToFloat;
subroutine uniform FloatToDepth usubroutine_floatToDepth;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    float c1 = texelFetch(usam_input, intTexCoord, uval_level).r;
    c1 = usubroutine_depthToFloat(c1);
    float c2 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 0)).r;
    c2 = usubroutine_depthToFloat(c2);
    float c3 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(0, 1)).r;
    c3 = usubroutine_depthToFloat(c3);
    float c4 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 1)).r;
    c4 = usubroutine_depthToFloat(c4);

    float v = usubroutine_kernel(c1, c2, c3, c4);

    gl_FragDepth = usubroutine_floatToDepth(v);
}
#else
out vec4 fragColor;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    vec4 c1 = texelFetch(usam_input, intTexCoord, uval_level);
    vec4 c2 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 0));
    vec4 c3 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(0, 1));
    vec4 c4 = texelFetchOffset(usam_input, intTexCoord, uval_level, ivec2(1, 1));

    fragColor = usubroutine_kernel(c1, c2, c3, c4);
}
#endif