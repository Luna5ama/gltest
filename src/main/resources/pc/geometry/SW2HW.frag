#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

uniform ivec2 uval_viewportSizeI;

layout(std430) readonly buffer VBufferDepthQuadIndex {
    uint64_t ssbo_vBufferDepthQuadIndex[];
};

#ifndef DEPTH_ONLY
layout(location = 0) out uint rt_vbuffer;
#endif

void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);

    uint bufferIndex = coord.y * uval_viewportSizeI.x + coord.x;
    uint64_t dataRaw = ssbo_vBufferDepthQuadIndex[bufferIndex];
    gl_FragDepth = uintBitsToFloat(uint(dataRaw >> 32));

    #ifndef DEPTH_ONLY
    rt_vbuffer = uint(dataRaw);
    #endif
}