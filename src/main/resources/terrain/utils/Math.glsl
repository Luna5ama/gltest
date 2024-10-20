const float PI_CONST = 3.14159265358979323846;
const float PI_2_CONST = 2.0 * PI_CONST;
const float PI_HALF_CONST = 0.5 * PI_CONST;

float linearStep(float edge0, float edge1, float x) {
    return clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
}