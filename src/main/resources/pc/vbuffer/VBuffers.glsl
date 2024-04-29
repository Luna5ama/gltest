#include "../BitPacking.glsl"

uint vbuffer_encodeBCoord(vec4 bCoord) {
    return packU11(bCoord.x) |
    packU10(bCoord.y) << 11u |
    packU11(bCoord.z) << 21u;
}

vec4 vbuffer_decodeBCoord(uint packedV) {
    vec4 bCoord = vec4(
        unpackU11(packedV & 2047u),
        unpackU10((packedV >> 11u) & 1023u),
        unpackU11((packedV >> 21u) & 2047u),
        0.0
    );
    bCoord.w = 1.0 - dot(bCoord.xyz, vec3(1.0));
    return bCoord;
}