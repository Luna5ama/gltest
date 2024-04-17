struct GBufferData {
    vec3 worldCoord;
    vec3 worldNormal;
    vec4 albedo;
    uint materialID;
    vec2 texCoord;
    vec2 lightCoord;
    vec3 worldTangent;
};

void gbuffer_restorNormalZ(inout vec3 normal, uint signBitIn) {
    normal.z = sqrt(abs(1.0 - dot(normal.xy, normal.xy)));
    int signBit = int(signBitIn >> 31u);
    signBit = (-signBit) * 2 + 1;
    normal.z = float(signBit) * normal.z;
}

uint gbuffer_extractSignBit(float value) {
    return uint(int(sign(value))) & 0x80000000u;
}

void gbuffer_pack1(out uvec4 packedV, in GBufferData data) {
    packedV.x = packHalf2x16(data.worldCoord.xy);
    packedV.y = packHalf2x16(vec2(data.worldCoord.z, 0.0)) | (packSnorm4x8(vec4(data.worldNormal.xy, 0.0, 0.0)) << 16);
    packedV.z = packUnorm4x8(data.albedo);
    packedV.w = gbuffer_extractSignBit(data.worldNormal.z) | data.materialID;
}

void gbuffer_unpack1(uvec4 packedV, out GBufferData data) {
    data.worldCoord.xy = unpackHalf2x16(packedV.x);
    data.worldCoord.z = unpackHalf2x16(packedV.y).x;
    data.worldNormal.xy = unpackSnorm4x8(packedV.y >> 16).xy;
    data.albedo = unpackUnorm4x8(packedV.z);
    data.materialID = packedV.w & 0x7FFFFFFFu;
    gbuffer_restorNormalZ(data.worldNormal, packedV.w & 0x80000000u);
}

void gbuffer_pack2(out uvec4 packedV, in GBufferData data) {
    packedV.x = gbuffer_extractSignBit(data.worldTangent.z) | packUnorm2x16(data.texCoord * 0.5);
    packedV.y = packSnorm4x8(vec4(data.worldTangent.xy, data.lightCoord));
}

void gbuffer_unpack2(uvec4 packedV, out GBufferData data) {
    uint texCoordBit = packedV.x & 0x7FFFFFFFu;
    data.texCoord = unpackUnorm2x16(texCoordBit) * 2.0;
    vec4 temp = unpackSnorm4x8(packedV.y);
    data.lightCoord = temp.zw;
    data.worldTangent.xy = temp.xy;
    gbuffer_restorNormalZ(data.worldTangent, packedV.x & 0x80000000u);
}