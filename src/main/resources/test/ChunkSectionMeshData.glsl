struct ChunkSectionMeta {
    uvec4 chunkSectionOffset;
    uint chunkSectionID;
    uint faceCount;
    uint faceMetadataIndex32;
};

struct FaceMetadata {
    uint chunkSectionID;
    uint faceID;
    uint quadCount;
    uint quadDataIndex32;
};

layout(std430) restrict readonly buffer MeshletMetadataBuffer {
    uint meshletMetaData[];
};

ChunkSectionMeta readChunkSectionMeta(uint index32) {
    ChunkSectionMeta meta;
    meta.chunkSectionOffset = uvec4(
        meshletMetaData[index32],
        meshletMetaData[index32 + 1],
        meshletMetaData[index32 + 2],
        meshletMetaData[index32 + 3]
    );
    meta.chunkSectionID = meshletMetaData[index32 + 4];
    meta.faceCount = meshletMetaData[index32 + 5];
    meta.faceMetadataIndex32 = meshletMetaData[index32 + 6];
    return meta;
}

FaceMetadata readFaceMetadata(uint index32) {
    FaceMetadata meta;
    meta.chunkSectionID = meshletMetaData[index32];
    meta.faceID = meshletMetaData[index32 + 1];
    meta.quadCount = meshletMetaData[index32 + 2];
    meta.quadDataIndex32 = meshletMetaData[index32 + 3];
    return meta;
}

layout(std430) restrict readonly buffer LocalQuadDataBuffer {
    uint localQuadData[];
};

struct LocalQuadData {
    uint globalQuadID;
    vec3 offset;
};

LocalQuadData readLocalQuadData(uint index) {
    LocalQuadData data;
    uint index32 = index * 2;
    data.globalQuadID = localQuadData[index32];
    data.offset = unpackUnorm4x8(localQuadData[index32 + 1]).xyz * 16.0;
    return data;
}

// Total: 64 bytes
//struct GlobalQuadData {
//    uint32_t materialID; // 4
//    vec3i16 pos[4];   // 24
//    vec3i8 color[4];  // 12
//    vec3i8 normal[4]; // 12
//    vec2i8 uv[4];    // 8
//}
layout(std430) restrict readonly buffer GlobalQuadDataBuffer {
    uint globalQuadData[];
};

struct GlobalQuadData {
    uint materialID;
    vec3 pos[4];
    vec3 color[4];
    vec3 normal[4];
    vec2 uv[4];
};

GlobalQuadData readGlobalQuadData(uint index) {
    GlobalQuadData data;
    uint index32 = index * 16;
    data.materialID = globalQuadData[index32];

    vec2 temp16[6];
    temp16[0] = unpackSnorm2x16(globalQuadData[index32 + 1]);
    temp16[1] = unpackSnorm2x16(globalQuadData[index32 + 2]);
    temp16[2] = unpackSnorm2x16(globalQuadData[index32 + 3]);
    temp16[3] = unpackSnorm2x16(globalQuadData[index32 + 4]);
    temp16[4] = unpackSnorm2x16(globalQuadData[index32 + 5]);
    temp16[5] = unpackSnorm2x16(globalQuadData[index32 + 6]);
    data.pos[0] = vec3(temp16[0].xy, temp16[1].x);
    data.pos[1] = vec3(temp16[1].y, temp16[2].xy);
    data.pos[2] = vec3(temp16[3].xy, temp16[4].x);
    data.pos[3] = vec3(temp16[4].y, temp16[5].xy);

    vec4 temp8[3];
    temp8[0] = unpackUnorm4x8(globalQuadData[index32 + 7]);
    temp8[1] = unpackUnorm4x8(globalQuadData[index32 + 8]);
    temp8[2] = unpackUnorm4x8(globalQuadData[index32 + 9]);
    data.color[0] = temp8[0].xyz;
    data.color[1] = vec3(temp8[0].w, temp8[1].xy);
    data.color[2] = vec3(temp8[1].zw, temp8[2].x);
    data.color[3] = temp8[2].yzw;

    temp8[0] = unpackSnorm4x8(globalQuadData[index32 + 10]);
    temp8[1] = unpackSnorm4x8(globalQuadData[index32 + 11]);
    temp8[2] = unpackSnorm4x8(globalQuadData[index32 + 12]);
    data.normal[0] = temp8[0].xyz;
    data.normal[1] = vec3(temp8[0].w, temp8[1].xy);
    data.normal[2] = vec3(temp8[1].zw, temp8[2].x);
    data.normal[3] = temp8[2].yzw;

    temp8[0] = unpackUnorm4x8(globalQuadData[index32 + 13]);
    temp8[1] = unpackUnorm4x8(globalQuadData[index32 + 14]);
    data.uv[0] = temp8[0].xy;
    data.uv[1] = temp8[0].zw;
    data.uv[2] = temp8[1].xy;
    data.uv[3] = temp8[1].zw;

    return data;
}

const uint QUAD_TRIANGLE_INDICES[6] = uint[6](0, 2, 1, 3, 1, 2);