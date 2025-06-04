#version 460 core

#pragma optionNV (unroll all)

layout(local_size_x = 128) in;

struct ParticleDataRaw {
    vec4 data1;
    uvec4 data2;
};

struct ParticleData {
    vec2 position;
    vec2 velocity;
    uint generation;
};

layout(std430) restrict buffer ParticleDataBuffer {
    uvec4 data;
    ParticleDataRaw particles[];
} ssbo_particleData;

layout(r32i) uniform iimage2D uimg_renderCount;

uniform sampler2D usam_noiseField;

ParticleData particle_unpack(ParticleDataRaw raw) {
    ParticleData p;
    p.position = raw.data1.xy;
    p.velocity = raw.data1.zw;
    p.generation = raw.data2.x;
    return p;
}

ParticleDataRaw particle_pack(ParticleData p) {
    ParticleDataRaw raw;
    raw.data1 = vec4(p.position, p.velocity);
    raw.data2 = uvec4(p.generation, 0u, 0u, 0u);
    return raw;
}

uvec2 pcg2d(uvec2 v) {
    v = v * 1664525u + 1013904223u;

    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;

    v = v ^ (v>>16u);

    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;

    v = v ^ (v>>16u);

    return v;
}

uint rand_hash11(uint n) {
    // integer hash copied from Hugo Elias
    n = (n << 13U) ^ n;
    n = n * (n * n * 15731U + 789221U) + 1376312589U;
    return n;
}

vec2 rand_r2Seq2(uint idx) {
    const float g = 1.32471795724474602596;
    const vec2 a = vec2(1.0 / g, 1.0 / (g * g));
    return fract(0.5 + a * idx);
}

vec2 rand_uintToFloat(uvec2 v) {
    return vec2(v) * (1.0 / float(0xffffffffU));
}

vec2 sampleNoiseField(vec2 pos) {
    return normalize(texture(usam_noiseField, pos / NOISE_FIELD_SIZE).xy);
}

void main() {
    uint idx = gl_GlobalInvocationID.x;
    uint randIdx = rand_hash11(idx + 1232u);
    ParticleData p = particle_unpack(ssbo_particleData.particles[idx]);

    for (uint i = 0; i < 16; i++){
        uint outOfBound = 0u;
        outOfBound |= uint(any(lessThan(p.position, vec2(0.0))));
        outOfBound |= uint(any(greaterThan(p.position, vec2(NOISE_FIELD_SIZE))));
        if (bool(outOfBound)) {
            p.position = rand_uintToFloat(pcg2d(uvec2(randIdx, p.generation * 1135u))) * NOISE_FIELD_SIZE;
            p.position = fract(p.position) * NOISE_FIELD_SIZE * vec2(1.0, 0.1);
//            p.position = fract(p.position) * NOISE_FIELD_SIZE;
            p.velocity = vec2(0.0);
            p.velocity += sampleNoiseField(p.position) * 1.0;
//            p.velocity += (rand_uintToFloat(pcg2d(uvec2(p.generation * 1324u, randIdx * 5566u))) - 0.5) * 8.0;
            p.generation = (p.generation + 1u) & 0xFFFu;
        }

        ivec2 renderPos = ivec2(p.position);
        imageAtomicAdd(uimg_renderCount, renderPos, 1);

        p.position += p.velocity;
        p.velocity = normalize(mix(p.velocity, sampleNoiseField(p.position), 0.01));
    }

    ssbo_particleData.particles[idx] = particle_pack(p);
}
