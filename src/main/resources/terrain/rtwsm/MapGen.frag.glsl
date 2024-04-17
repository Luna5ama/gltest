#version 460 core

#ifdef CUTOUT
layout(binding = 0) uniform sampler2D usam_blockAlbedoAtlas;

//struct VertData {
//    vec2 texCoord;
//    float lodMul;
//};
//in VertData fragData;
in vec3 texCoordLodMul;

void main() {
//    float sampleLod = textureQueryLod(usam_blockAlbedoAtlas, fragData.texCoord).y * fragData.lodMul;
//    float alpha = textureLod(usam_blockAlbedoAtlas, fragData.texCoord, sampleLod).a;
    float sampleLod = textureQueryLod(usam_blockAlbedoAtlas, texCoordLodMul.xy).y * texCoordLodMul.z;
    float alpha = textureLod(usam_blockAlbedoAtlas, texCoordLodMul.xy, sampleLod).a;
    if (alpha <= 0.5) discard;
}
#else
layout(early_fragment_tests) in;

void main() {}
#endif