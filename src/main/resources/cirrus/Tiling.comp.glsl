#version 460 core

layout(local_size_x = 16, local_size_y = 16) in;

uniform sampler2D usam_normalizedImage;

layout(r8) uniform writeonly image2D uimg_tiledImage;

vec4 texture_tiling(sampler2D t, vec2 texCoord) {
    vec2 dist0 = smoothstep(0.0, 0.5, abs(fract(texCoord - vec2(0.5, 0.5)) - vec2(0.5, 0.5)));
    vec2 dist1 = smoothstep(0.0, 0.5, abs(fract(texCoord - vec2(0.5, 0.5) + vec2(0.5, 0.5)) - vec2(0.5, 0.5)));
    vec2 dist2 = smoothstep(0.0, 0.5, abs(fract(texCoord - vec2(0.5, 0.5) + vec2(1.0, 0.5)) - vec2(0.5, 0.5)));
    vec2 dist3 = smoothstep(0.0, 0.5, abs(fract(texCoord - vec2(0.5, 0.5) + vec2(0.5, 1.0)) - vec2(0.5, 0.5)));

    vec4 result = vec4(0.0);
    result += texture(t, texCoord) * dist0.x * dist0.y;
    result += texture(t, texCoord + vec2(0.5, 0.5)) * dist1.x * dist1.y;
    result += texture(t, texCoord + vec2(1.0, 0.5)) * dist2.x * dist2.y;
    result += texture(t, texCoord + vec2(0.5, 1.0)) * dist3.x * dist3.y;

    return result;
}

void main() {
    ivec2 texelPos = ivec2(gl_GlobalInvocationID.xy);
    vec2 screenPos = (vec2(texelPos) + 0.5) / vec2(textureSize(usam_normalizedImage, 0));
    float value = texture_tiling(usam_normalizedImage, screenPos).r;
    imageStore(uimg_tiledImage, texelPos, vec4(value));
}