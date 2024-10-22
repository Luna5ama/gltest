const float PI_CONST = 3.14159265358979323846;
const float PI_2_CONST = 2.0 * PI_CONST;
const float PI_HALF_CONST = 0.5 * PI_CONST;

float saturate(float x) { return clamp(x, 0.0, 1.0); }

vec2 saturate(vec2 x) { return clamp(x, 0.0, 1.0); }

vec3 saturate(vec3 x) { return clamp(x, 0.0, 1.0); }

vec4 saturate(vec4 x) { return clamp(x, 0.0, 1.0); }

float linearStep(float edge0, float edge1, float x) {
    return saturate((x - edge0) / (edge1 - edge0));
}