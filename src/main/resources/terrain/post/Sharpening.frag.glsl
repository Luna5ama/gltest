#version 460 core

#include "../GlobalCamera.glsl"

layout(binding = 0) uniform sampler2D usam_mainFbo_inputColor;

in vec2 fTexCoord;
in vec2 fTexCoordI;

uniform float uSharpnessAmount;

out vec4 fragColor;

void main() {
    ivec2 intTexCoord = ivec2(fTexCoordI);
    // Get the color from the TAA texture
    vec4 colorC = texelFetch(usam_mainFbo_inputColor, intTexCoord, 0);

    vec2 offsetHalf = fTexCoord + 0.5 * ubo_cameraDefault.screenSize.zw;

    // Calculate the blurred version of the image using a simple box blur or other blur filter
    vec4 blurredColor = textureOffset(usam_mainFbo_inputColor, offsetHalf, ivec2(-1, -1));
    blurredColor += textureOffset(usam_mainFbo_inputColor, offsetHalf, ivec2(-1, 0));
    blurredColor += textureOffset(usam_mainFbo_inputColor, offsetHalf, ivec2(0, -1));
    blurredColor += textureOffset(usam_mainFbo_inputColor, offsetHalf, ivec2(0, 0));
    blurredColor *= 0.25; // Average the blurred color

    // Calculate the sharpness effect using the Unsharp Mask filter
    vec4 sharpness = colorC - blurredColor;

    // Combine the sharpness effect with the original image using the 'sharpnessAmount' parameter
    vec4 sharpenedImage = colorC + sharpness * uSharpnessAmount;

    // Output the final sharpened image
    fragColor = sharpenedImage;
}