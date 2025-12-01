#version 460 core

#include "Common.glsl"

layout(local_size_x = 16, local_size_y = 16) in;

layout(IMAGE_FORMAT) restrict writeonly uniform IMAGE_TYPE IMAGE_NAME;

void main() {
    #if CLEAR_TYPE == 0
    #define MY_CLAER_VALUE ZERO
    #elif CLEAR_TYPE == 1
    #define MY_CLAER_VALUE ONE
    #elif CLEAR_TYPE == 2
    #define MY_CLAER_VALUE ONE_BITS
    #endif
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);
    imageStore(IMAGE_NAME, texelPos, MY_CLAER_VALUE);
}