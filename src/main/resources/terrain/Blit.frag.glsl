#version 460 core

uniform float uMul = 1.0;
uniform float uAdd = 0.0;
uniform float uPower = 1.0;
uniform float uLod = 0.0;

layout(binding = 0) uniform sampler2D usam_input;

in vec2 fTexCoord;

out vec4 fragColor;

subroutine void SampleChannel();

layout(location = 0) subroutine uniform SampleChannel usubroutine_SampleChannel;

subroutine(SampleChannel) void sampleRRR() {
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).r * uMul + uAdd, uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleGGG() {
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).g * uMul + uAdd, uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleBBB() {
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).b * uMul + uAdd, uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleAAA() {
    fragColor.rgb = vec3(pow(textureLod(usam_input, fTexCoord, uLod).a * uMul + uAdd, uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleRGB() {
    fragColor.rgb = pow(textureLod(usam_input, fTexCoord, uLod).rgb * uMul + uAdd, vec3(uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleRGBPA() {
    fragColor = pow(textureLod(usam_input, fTexCoord, uLod) * uMul + uAdd, vec4(uPower));
    fragColor.rgb *= fragColor.a;
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleRGBA() {
    fragColor = pow(textureLod(usam_input, fTexCoord, uLod) * uMul + uAdd, vec4(uPower));
}

subroutine(SampleChannel) void sampleRG() {
    fragColor.rg = pow(textureLod(usam_input, fTexCoord, uLod).rg * uMul + uAdd, vec2(uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleRB() {
    fragColor.rb = pow(textureLod(usam_input, fTexCoord, uLod).rb * uMul + uAdd, vec2(uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleRA() {
    fragColor.ra = pow(textureLod(usam_input, fTexCoord, uLod).ra * uMul + uAdd, vec2(uPower));
    fragColor.b = 1.0;
}

subroutine(SampleChannel) void sampleGB() {
    fragColor.gb = pow(textureLod(usam_input, fTexCoord, uLod).gb * uMul + uAdd, vec2(uPower));
    fragColor.a = 1.0;
}

subroutine(SampleChannel) void sampleGA() {
    fragColor.ga = pow(textureLod(usam_input, fTexCoord, uLod).ga * uMul + uAdd, vec2(uPower));
    fragColor.b = 1.0;
}

subroutine(SampleChannel) void sampleBA() {
    fragColor.ba = pow(textureLod(usam_input, fTexCoord, uLod).ba * uMul + uAdd, vec2(uPower));
    fragColor.r = 1.0;
}

void main() {
    usubroutine_SampleChannel();
}