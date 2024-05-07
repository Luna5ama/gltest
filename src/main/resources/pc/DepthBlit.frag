#version 460 core

#include "GlobalCamera.glsl"

uniform sampler2D usam_input;

in vec2 fTexCoordI;

const float CONVERT = 0.0000152587890625;

const float FLOAT_POS_INF = uintBitsToFloat(0x7F800000u);

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    float linearDepth = texelFetch(usam_input, intTexCoord, 0).r;
    gl_FragDepth = linearDepth;
//    if (linearDepth == FLOAT_POS_INF) {
//        gl_FragDepth = 1.0;
//    } else {
//        float eyeZ = -linearDepth;
//        float near = ubo_cameraDefault.planeZ.x;
//        float far = ubo_cameraDefault.planeZ.y;
//        float farMinusNear = far - near;
//        float depth = ((eyeZ * ((-(far + near)) / (far - near)) - ((2 * far * near) / (far - near))) / -eyeZ) * 0.5 + 0.5;
//        gl_FragDepth = depth;
//    }
}