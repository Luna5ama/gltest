const vec2 NOISE_INV = vec2(1.0) / vec2(4096.0);

vec2 getGradient(vec2 intPos, float t) {
    float rand = textureLod(NOISE_TEXTURE, intPos * NOISE_INV, 0.0).r;

    // Rotate gradient: random starting rotation, random rotation rate
    float angle = 6.283185 * rand + 4.0 * t * rand;
    return vec2(cos(angle), sin(angle));
}


float pseudo3dNoise(vec3 pos) {
    vec2 i = floor(pos.xy);
    vec2 f = pos.xy - i;
    vec2 blend = f * f * (3.0 - 2.0 * f);
    float noiseVal =
    mix(
        mix(
            dot(getGradient(i + vec2(0, 0), pos.z), f - vec2(0, 0)),
            dot(getGradient(i + vec2(1, 0), pos.z), f - vec2(1, 0)),
            blend.x),
        mix(
            dot(getGradient(i + vec2(0, 1), pos.z), f - vec2(0, 1)),
            dot(getGradient(i + vec2(1, 1), pos.z), f - vec2(1, 1)),
            blend.x),
        blend.y
    );
    return noiseVal / 0.7;// normalize to about [-1..1]
}

const int FBM_ITER = 16;

float calcWave(vec2 uv, float wTime) {
//    const float WAVE_LENGTH = 0.1;
//    return 0.1 * sin(uv.x / WAVE_LENGTH - wTime / WAVE_LENGTH);
    uv += vec2(0.4, -0.1) * sin(wTime * 0.2);
    uv += vec2(0.1, 0.6) * cos(wTime * 0.4);
    uv += vec2(0.6, 0.3) * wTime;

    const float angle = -23.33 * 3.1415926 / 180.0;
    const float cosA = cos(angle) * 1.333;
    const float sinA = sin(angle) * 1.177;
    const mat2 octave_m = mat2(cosA, sinA, -sinA, cosA);

    vec2 sUV = uv * 0.1;
    float sTime = wTime * 0.5;
    float sAdd = 0.0;
    float sWeight = 5.0;

    float sSum = 0.0;
    float noiseVal = 0.0;

    for (int i = 0; i < FBM_ITER; i++) {
        float noise = 0.0;
        noise += pseudo3dNoise(vec3(sUV, sTime));
        noise += pseudo3dNoise(vec3(sUV, sTime));
        noise *= 0.5;

        noiseVal += sWeight * clamp(sAdd + noise, 0.0, 1.0);
        sSum += sWeight;

        sTime *= -1.2;
        sUV *= octave_m;
        sWeight *= 0.75;
        sAdd *= 1.2;
    }

    noiseVal /= sSum;

    return noiseVal;
}

const vec2 NORMAL_EPS_MUL = vec2(1.0, 0.0);

vec3 calcNormal(vec2 uv, float height, float wTime, float epsInput) {
    vec2 eps = vec2(epsInput) * NORMAL_EPS_MUL;
    vec3 n;
    n.y = 1.0 - height;
    n.x = 1.0 - calcWave(uv + eps, wTime) - n.y;
    n.z = 1.0 - calcWave(uv + eps.yx, wTime) - n.y;
    n.y = eps.x;
    return normalize(n);
}