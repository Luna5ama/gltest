#version 460 core

uniform float uMul = 1.0;
uniform float uval_alpha = 1.0;
uniform float uAdd = 0.0;
uniform float uPower = 1.0;
uniform float uLod = 0.0;

layout(binding = 0) uniform sampler2D usam_input;

in vec2 fTexCoord;

out vec4 fragColor;

void main() {
    #if defined(SAMPLE_CHANNEL_RRR)
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).r * uMul + uAdd, uPower));
    fragColor.a = uval_alpha;

    #elif defined(SAMPLE_CHANNEL_GGG)
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).g * uMul + uAdd, uPower));
    fragColor.a = uval_alpha;

    #elif defined(SAMPLE_CHANNEL_BBB)
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).b * uMul + uAdd, uPower));
    fragColor.a = uval_alpha;

    #elif defined(SAMPLE_CHANNEL_AAA)
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).a * uMul + uAdd, uPower));
    fragColor.a = uval_alpha;

    #elif defined(SAMPLE_CHANNEL_RGB)
    fragColor.rgb = pow(textureLod(usam_input, fTexCoord, uLod).rgb * uMul + uAdd, vec3(uPower));
    fragColor.a = uval_alpha;

    #elif defined(SAMPLE_CHANNEL_RGBA)
    fragColor = pow(textureLod(usam_input, fTexCoord, uLod) * uMul + uAdd, vec4(uPower));

    #elif defined(SAMPLE_CHANNEL_RGBPA)
    fragColor = pow(textureLod(usam_input, fTexCoord, uLod) * uMul + uAdd, vec4(uPower));
    fragColor.rgb *= fragColor.a;
    fragColor.a = uval_alpha;
    #endif
}