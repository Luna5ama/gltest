#include "../BRDFs.glsl"

const float EMISSIVE_INTENSITY = 16.0;

vec3 calcAmbient(sampler2D lightMapTex, vec3 viewDir, vec3 normal, float roughness, vec2 lightCoord, float blockLight, float skylight) {
    const vec3 AMBIENT_DIFFUSE_1 = vec3(0.16169041, 0.80845207, -0.5659165);
    const vec3 AMBIENT_DIFFUSE_2 = vec3(-0.16169041, 0.80845207, 0.5659165);
    const float AMBIENT_DIFFUSE_INTENSITY = 0.9;

    vec2 lightValue = lightCoord * 1.06667 - 0.03333;

    vec3 blockLightColor = texture(lightMapTex, vec2(lightCoord.x, 0.0)).rgb;
    vec3 skylightColor = texture(lightMapTex, vec2(0.0, lightCoord.y)).rgb;

    vec3 ambientBase = vec3(0.0);
    ambientBase += skylight * lightValue.y * skylightColor * skylightColor;
    ambientBase += blockLight * lightValue.x * blockLightColor * blockLightColor;

    float ambientLight = 0.1;
    ambientLight += brdf_diffuse_orenNayar(AMBIENT_DIFFUSE_1, viewDir, normal, roughness) * 0.45;
    ambientLight += brdf_diffuse_orenNayar(AMBIENT_DIFFUSE_2, viewDir, normal, roughness) * 0.45;

    return ambientLight * ambientBase;
}

vec3 calcAmbient(sampler2D lightMapTex, vec3 viewDir, vec3 normal, float roughness, vec2 lightCoord, vec3 blockLight, vec3 skylight) {
    const vec3 AMBIENT_DIFFUSE_1 = vec3(0.16169041, 0.80845207, -0.5659165);
    const vec3 AMBIENT_DIFFUSE_2 = vec3(-0.16169041, 0.80845207, 0.5659165);
    const float AMBIENT_DIFFUSE_INTENSITY = 0.9;

    vec2 lightValue = lightCoord * 1.06667 - 0.03333;

    vec3 blockLightColor = texture(lightMapTex, vec2(lightCoord.x, 0.0)).rgb;
    vec3 skylightColor = texture(lightMapTex, vec2(0.0, lightCoord.y)).rgb;

    vec3 ambientBase = vec3(0.0);
    ambientBase += skylight * lightValue.y * skylightColor * skylightColor;
    ambientBase += blockLight * lightValue.x * blockLightColor * blockLightColor;

    float ambientLight = 0.5;
    ambientLight += brdf_diffuse_orenNayar(AMBIENT_DIFFUSE_1, viewDir, normal, roughness) * 0.45;
    ambientLight += brdf_diffuse_orenNayar(AMBIENT_DIFFUSE_2, viewDir, normal, roughness) * 0.45;

    return ambientLight * ambientBase;
}

vec3 gtaoMultiBounce(vec3 albedo, float ao) {
    vec3 a = 2.0404 * albedo - 0.3324;
    vec3 b = 4.7951 * albedo - 0.6417;
    vec3 c = 2.7552 * albedo + 0.6903;
    float ao2 = ao * ao;
    float ao3 = ao2 * ao;
    return max(a * ao3 - b * ao2 + c * ao, vec3(ao));
}