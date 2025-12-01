#version 460 core

#include "Common.glsl"
#include "/util/Hash.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(IMAGE_FORMAT) restrict writeonly uniform IMAGE_TYPE IMAGE_NAME;

uniform uvec2 uval_seed;

void main() {
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);
    uvec4 randU = hash_44_q3(uvec4(gl_GlobalInvocationID.xy, uval_seed));
    imageStore(IMAGE_NAME, texelPos, CONVERT_UVEC(randU));
}