/*
    https://graphicrants.blogspot.com/2013/08/specular-brdf-reference.html
    http://www.codinglabs.net/article_physically_based_rendering_cook_torrance.aspx
*/

/*
    n: Macro Surface normal
    m: Microfacet normal, h is used for impl
    a: Roughness^2
    l: Vector from point to light
    v: Vector from point to viewer
    h: Half vector of v and l

    ndf: Normal Distribution Function
    gs: Geometrical Shadowing
*/
#define PI 3.14159265358979323846

float _gs_Smith_Backmann(float nDotV, float a) {
    float c = nDotV / (a * sqrt(1.0 - nDotV * nDotV));
    float c2 = c * c;
    return mix((3.535 * c + 2.181 * c2) / (1.0 + 2.276 * c + 2.577 * c2), 1.0, step(1.6, c));
}

float _gs_Smith_GGX(float nDotV, float a2) {
    float numerator = 2.0 * nDotV;
    float denominator = nDotV + sqrt(a2 + (1.0 - a2) * (nDotV * nDotV));
    return numerator / denominator;
}

float _gs_Smith_Schlick(float nDotV, float k) {
    return nDotV / (nDotV * (1.0 - k) + k);
}

float _gs_Smith_Schlick_Beckmann(float nDotV, float a) {
    float k = a * sqrt(2.0 / PI);
    return _gs_Smith_Schlick(nDotV, k);
}

float _gs_Smith_Schlick_GGX(float nDotV, float a) {
    float k = a / 2.0;
    return _gs_Smith_Schlick(nDotV, k);
}

float _cts_pow5(float x) {
    return x * x * x * x * x;
}

float _cts_pow2(float x) {
    return x * x;
}

vec3 _cts_pow5(vec3 x) {
    return x * x * x * x * x;
}

vec3 _cts_pow2(vec3 x) {
    return x * x;
}

vec3 cts_f0ToIor(vec3 f0) {
    vec3 f0Sqrt = sqrt(f0) * 0.9999;
    return (1.0 + f0Sqrt) / (1.0 - f0Sqrt);
}

float cts_f0ToIor(float f0) {
    float f0Sqrt = sqrt(f0) * 0.9999;
    return (1.0 + f0Sqrt) / (1.0 - f0Sqrt);
}

vec3 cts_frenel_cook_torrance(float lDotH, float f0) {
    float ior = cts_f0ToIor(f0);
    float c = float(lDotH);
    float g = sqrt(ior * ior + c * c - float(1.0));
    return vec3(0.5 * _cts_pow2((g - c) / (g + c)) * (1.0 + _cts_pow2(((g + c) * c - 1.0) / ((g - c) * c + 1.0))));
}

vec3 cts_frenel_schlick_f0(float lDotH, vec3 f0) {
    return f0 + (1.0 - f0) * _cts_pow5(1.0 - lDotH);
}

vec3 cts_frenel_schlick(float lDotH, vec3 ior) {
    vec3 f0 = _cts_pow2((ior - 1.0) / (ior + 1.0));
    return f0 + (1.0 - f0) * _cts_pow5(1.0 - lDotH);
}

// Fresnel Term Approximations for Metals by Lazanyi
vec3 cts_fresnel_lazanyi(float lDotH, vec3 ior, vec3 k) {
    vec3 k2 = _cts_pow2(k);
    return (_cts_pow2(ior - 1.0) + 4 * ior * _cts_pow5(1.0 - lDotH) + k2) / (_cts_pow2(ior + 1.0) + k2);
}

// f: Fresnel term
vec3 cts_main(vec3 n, vec3 l, vec3 v, vec3 f, float a) {
    float nDotL = dot(n, l);
    if (nDotL <= 0.0) return vec3(0.0);
    vec3 h = normalize(l + v);

    float nDotV = dot(n, v);
    float nDotH = dot(n, h);

    float vDotL = dot(v, l);
    float vDotH = dot(v, h);

    float a2 = a * a;
    float nDotH2 = nDotH * nDotH;
    float nDotH4 = nDotH2 * nDotH2;

    // Normal Distribution Function Term
    float d;
    // Blinn Phong
//    {
//        float power = 2.0 / a2 - 2.0;
//        d = pow(nDotH, power) / (PI * a2);
//    }
    // Beckmann
//    {
//        float expTerm = (nDotH2 - 1.0) / (a2 * nDotH2);
//        d = exp(expTerm) / (PI * a2 * nDotH4);
//    }
    // GGX (Trowbridge-Reitz)
    {
        float sqTerm = nDotH2 * (a2 - 1.0) + 1.0;
        d = a2 / max(PI * sqTerm * sqTerm, 0.00001);
    }
//    // GGX
//    {
//        float sqTerm = nDotH2 * (a2 - 1.0) + 1.0;
//        d = max(sign(nDotH), 0.0) * a2 / max(PI * sqTerm * sqTerm, 0.00001);
//    }

    // Geometric Shadowing Term
    float g;
//    // Implicit
//    {
//        g = nDotL * nDotV;
//    }
    // Neumann
//    {
//        float numerator = nDotL * nDotV;
//        float denominator = max(nDotL, nDotV);
//        g = numerator / denominator;
//    }
    // Cook-Torrance
//    {
//        float termC = (2.0 * nDotH) / vDotH;
//        float term1 = termC * nDotV;
//        float term2 = termC * nDotL;
//        g = min(1.0, min(term1, term2));
//    }
    // Kelemen
//    {
//        float numerator = nDotL * nDotV;
//        float denominator = vDotH * vDotH;
//        g = numerator / denominator;
//    }
    // Smith
    {
        float gl, gv;
        // Beckmann
//        {
//            gl = _gs_Smith_Backmann(nDotL, a);
//            gv = _gs_Smith_Backmann(nDotV, a);
//        }
        // GGX
//        {
//            gl = _gs_Smith_GGX(nDotL, a2);
//            gv = _gs_Smith_GGX(nDotV, a2);
//        }
        // Schlick Beckman
//        {
//            gl = _gs_Smith_Schlick_Beckmann(nDotL, a);
//            gv = _gs_Smith_Schlick_Beckmann(nDotV, a);
//        }
        // Schlick GGX
        {
            gl = _gs_Smith_Schlick_GGX(max(nDotL, 0.0), a);
            gv = _gs_Smith_Schlick_GGX(max(nDotV, 0.0), a);
        }
        g = gl * gv;
    }

    return (d * f * g) / (4 * nDotL * nDotV);
}

// PPBR Diffuse Lighting for GGX+Smith Microsurfaces by Earl Hammon Jr.
// (https://www.gdcvault.com/play/1024478/PBR-Diffuse-Lighting-for-GGX)
vec3 bsdf_diffuseHammon(float nDotL, float nDotV, float nDotH, float lDotV, vec3 albedo, float a) {
    if (nDotL <= 0.0) return vec3(0.0);
    float facing = 0.5 + 0.5 * lDotV;
    float singleRough = facing * (0.9 - 0.4 * facing) * ((0.5 + nDotH) / nDotH);
    float singleSmooth = 1.05 * (1.0 - _cts_pow5(1.0 - nDotL)) * (1.0 - _cts_pow5(1.0 - nDotV));
    float single = mix(singleSmooth, singleRough, 1.0) / PI;
    float multi = 0.1159 * a;
    return albedo * (single + albedo * multi);
}