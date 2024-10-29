#define PI 3.14159265358979323846
#define PI_INV 0.31830988618379067153776752674503
#define INV_SQRT_2 0.7071067811865475

float brdf_diffuse_lambertian(vec3 L, vec3 V, vec3 N, float roughness) {
    return max(0.0, dot(N, L));
}

float brdf_diffuseN_lambertian(vec3 L, vec3 V, vec3 N, float roughness) {
    return brdf_diffuse_lambertian(L, V, N, roughness) * PI_INV;
}

float brdf_diffuse_orenNayar(vec3 L, vec3 V, vec3 N, float roughness) {
    float LdotV = dot(L, V);

    float cosTheta_i = clamp(dot(N, L), 0.0, 1.0);
    float cosTheta_r = clamp(dot(N, V), 0.0, 1.0);

    float s = LdotV - cosTheta_i * cosTheta_r;
    float t = mix(1.0, max(cosTheta_i, cosTheta_r), step(0.0, s));

    float r = INV_SQRT_2 * atan(roughness);
    float r2 = roughness * roughness;
    float A = 1.0 - 0.5 * (r2 / (r2 + 0.33));
    float B = 0.45 * r2 / (r2 + 0.09);

    float sinTheta_i = sqrt(1.0 - cosTheta_i * cosTheta_i);
    float sinTheta_r = sqrt(1.0 - cosTheta_r * cosTheta_r);
    float cosPhiDiff = dot(normalize(L - N * cosTheta_i), normalize(V - N * cosTheta_r));

    float alpha = max(cosTheta_i, cosTheta_r);
    float beta = min(cosTheta_i, cosTheta_r);

    float orenNayarDiffuse = (A + B * max(0.0, cosPhiDiff) * sinTheta_i * sinTheta_r) * cosTheta_i;
    return max(orenNayarDiffuse, 0.0);
}

float brdf_diffuseN_orenNayar(vec3 L, vec3 V, vec3 N, float roughness) {
    return brdf_diffuse_orenNayar(L, V, N, roughness) * PI_INV;
}