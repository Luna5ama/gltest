#include "/util/BitPacking.glsl"

void ndpacking_restorNormalZ(inout vec3 normal, uint signBitIn) {
    normal.z = sqrt(abs(1.0 - dot(normal.xy, normal.xy)));
    int signBit = int(signBitIn >> 31u);
    signBit = (-signBit) * 2 + 1;
    normal.z = float(signBit) * normal.z;
}

uint ndpacking_extractSignBit(float value) {
    return uint(int(sign(value))) & 0x80000000u;
}

uvec2 ndpacking_pack(vec3 normal, float depth) {
    uvec2 packedV;
//    packedV.x = packS16(normal.x);
//    packedV.x |= packS15(normal.y) << 16;
//    packedV.x |= ndpacking_extractSignBit(normal.z);
    packedV.x = packSnorm4x8(vec4(normal, 0.0));

    packedV.y = floatBitsToUint(depth);
    return packedV;
}

void ndpacking_unpack(uvec2 packedV, out vec3 normal, out float depth) {
//    normal.x = unpackS16(packedV.x & 0xFFFFu);
//    normal.y = unpackS15((packedV.x >> 16) & 0x7FFFu);
//    ndpacking_restorNormalZ(normal, packedV.x & 0x80000000u);
    normal = unpackSnorm4x8(packedV.x).xyz;
    depth = uintBitsToFloat(packedV.y);
}

float ndpacking_unpackDepth(uvec2 packedV) {
    return uintBitsToFloat(packedV.y);
}

vec3 ndpacking_unpackNormal(uvec2 packedV) {
    vec3 normal;
    normal.x = unpackS16(packedV.x & 0xFFFFu);
    normal.y = unpackS15((packedV.x >> 16) & 0x7FFFu);
    ndpacking_restorNormalZ(normal, packedV.x & 0x80000000u);
    return normal;
}