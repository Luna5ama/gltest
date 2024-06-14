#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

uniform usampler2D usam_vBufferTex1;

layout(std430) readonly buffer VBufferDepthQuadIndex {
    uint64_t ssbo_vBufferDepthQuadIndex[];
};

in vec2 fTexCoordI;

layout(location = 0) out uvec2 rt_vbuffer;

void main() {
    ivec2 coord = ivec2(fTexCoordI);
    ivec2 outputSize = textureSize(usam_vBufferTex1, 0);

    uint bufferIndex = coord.y * outputSize.x + coord.x;
    uint64_t dataRaw = ssbo_vBufferDepthQuadIndex[bufferIndex];
    uint depth = uint(dataRaw >> 32u);
    uint bCoord = texelFetch(usam_vBufferTex1, coord, 0).x;
    uint quadIndex = uint(dataRaw);

    gl_FragDepth = uintBitsToFloat(depth);
    rt_vbuffer = uvec2(quadIndex, bCoord);
}