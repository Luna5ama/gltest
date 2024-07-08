#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

uniform ivec2 uval_viewportSizeI;

layout(std430) readonly buffer VBufferDepthQuadIndex {
    uint64_t ssbo_vBufferDepthQuadIndex[];
};

float readDepth(ivec2 coord) {
    if (any(greaterThanEqual(coord, uval_viewportSizeI)) || any(lessThan(coord, ivec2(0)))) {
        return 0.0;
    }
    uint64_t dataRaw = ssbo_vBufferDepthQuadIndex[coord.y * uval_viewportSizeI.x + coord.x];
    return uintBitsToFloat(uint(dataRaw >> 32));
}

void main() {
    ivec2 coord0 = ivec2(gl_FragCoord.xy) * 2;
    float depth = readDepth(coord0);
    depth = min(depth, readDepth(coord0 + ivec2(1, 0)));
    depth = min(depth, readDepth(coord0 + ivec2(0, 1)));
    depth = min(depth, readDepth(coord0 + ivec2(1, 1)));
    gl_FragDepth = depth;
}