#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

uniform sampler2D usam_cullTempTex;

void main() {
    gl_FragDepth = texelFetch(usam_cullTempTex, ivec2(gl_FragCoord.xy), 0).r;
}