layout(std430, binding = 1) restrict readonly buffer ChunkPosData {
    vec4 cameraChunkLocalPos;
    ivec4 chunkPos[];
} ssbo_chunkPosData;

vec3 chunks_getChunkRenderPos(uint chunkIndex) {
    ivec3 chunkPos = ssbo_chunkPosData.chunkPos[chunkIndex + 1].xyz - ssbo_chunkPosData.chunkPos[0].xyz;
    return vec3(chunkPos << ivec3(4)) - ssbo_chunkPosData.cameraChunkLocalPos.xyz;
}