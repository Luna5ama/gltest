const vec2 NOISE_INV = vec2(1.0) / vec2(1024.0);

float smootherstep(float edge0, float edge1, float x){
    x = clamp((x - edge0)/(edge1 - edge0), 0.0, 1.0);
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
}

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
    return smoothstep(-1.0, 1.0, noiseVal / 0.7);// normalize to about [-1..1]
}

//float sampleWoleyDistance(vec2 tuv, vec2 fuv, const ivec2 offset) {
//    vec2 nOffset = textureLodOffset(NOISE_TEXTURE, tuv, 0.0, offset).xy;
//    vec2 ouv = nOffset * NOISE_INV + (vec2(offset) * NOISE_INV) - fuv;
//    return length(ouv) * 1024.0;
//}
//
//float woleyNoise(vec2 uv) {
//    vec2 tuv = uv * NOISE_INV;
//    vec2 fuv = fract(tuv * 1024.0) * NOISE_INV;
//    float minDist = min(sampleWoleyDistance(tuv, fuv, ivec2(0, 0)), sampleWoleyDistance(tuv, fuv, ivec2(-1, 0)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(1, 0)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(0, -1)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(0, 1)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(-1, -1)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(1, -1)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(-1, 1)));
//    minDist = min(minDist, sampleWoleyDistance(tuv, fuv, ivec2(1, 1)));
//    return smootherstep(0.0, 0.9, minDist);
//}

float fbmP(vec2 uv, float wTime) {
    const float sAngle = -23.37 * 3.1415926 / 180.0;
    const float sCosA = cos(sAngle) * 1.23;
    const float sSinA = sin(sAngle) * 1.11;
    const mat2 sMulMat = mat2(sCosA, sSinA, -sSinA, sCosA);

    vec2 sUV = uv;
    sUV += vec2(1.43, -0.89) * sin(wTime * 0.2);
    sUV += vec2(0.96, 1.31) * cos(wTime * 0.4);
    sUV += vec2(1.37, 1.23) * wTime;
    sUV *= 0.1;

    float sWeight = 0.5;
    float sPow = 0.91;
    float sSpeed = 0.2;

    float result = 0.0;

    for (int i = 0; i < 8; i++) {
        float sTime = wTime * sSpeed;

        float d = pow(pseudo3dNoise(vec3(sUV, sTime)), sPow);
        d += pow(pseudo3dNoise(vec3(sUV, -sTime)), sPow);
        d *= 0.5;

        result += d * sWeight;
        sUV *= sMulMat;
        sWeight *= -0.75;
        sPow *= 1.37;
        sSpeed *= -1.53;
    }

    return result;
}

//float fbmW(vec2 uv, float wTime) {
//    const float angle = 79.33 * 3.1415926 / 180.0;
//    const float cosA = cos(angle) * 1.67;
//    const float sinA = sin(angle) * 1.37;
//    const mat2 octave_m = mat2(cosA, sinA, -sinA, cosA);
//
//    const float mAngle = 113.33 * 3.1415926 / 180.0;
//    const float mCosA = cos(mAngle) * 1.67;
//    const float mSinA = sin(mAngle) * 1.37;
//    const mat2 mMat = mat2(mCosA, mSinA, -mSinA, mCosA);
//
//    vec2 sUV = uv * 1.5;
//
//    float sWeight = -0.001;
//    float sPow = 0.4;
//    float sSpeed = 0.1;
//
//    float result = 0.0;
//    vec2 rotate = vec2(1.0);
//
//    for (int i = 0; i < 4; i++) {
//        float sTime = wTime * sSpeed;
//
//        result += pow(woleyNoise(sUV + sTime * rotate), sPow) * sWeight;
//        sUV *= octave_m;
//        rotate *= mMat;
//
//        sWeight *= -0.91;
//        sPow *= 1.17;
//        sSpeed *= -1.37;
//    }
//
//    return result;
//}

float calcWave(vec2 uv, float wTime) {

    float p = fbmP(uv, wTime);
//    float p = 0.0;

//    float w = fbmW(uv, wTime);
    float w = 0.0;

    float h = p + w;
//     h *= -1.0;
    return h;
}