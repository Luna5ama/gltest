uniform sampler2D usam_solidLighting1;
uniform sampler2D usam_solidLighting2;

vec3 sl_unpackNormal(vec4 sampleValue) {
    vec3 normal;
    normal.xy = sampleValue.ba;
    normal.z = sqrt(1.0 - dot(normal.xy, normal.xy));
    normal.z *= sign(sampleValue.g);
    return normal;
}