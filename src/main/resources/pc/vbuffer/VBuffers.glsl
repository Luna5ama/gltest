#include "../BitPacking.glsl"

uint vbuffer_encodeBCoord(vec3 bCoord) {
    uvec3 temp = (uvec3(bCoord * vec3(2047.0, 1023.0, 2047.0)) & uvec3(2047u, 1023u, 2047u)) << uvec3(0u, 11u, 21u);
    return temp.x | temp.y | temp.z;
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