float rand(float v) {
    return fract(sin(v * 91.3458) * 47453.5453);
}

float rand(vec2 v) {
    return fract(sin(dot(v, vec2(12.9898, 78.233))) * 43578.5453);
}

float rand(vec3 v) {
    return rand(v.xy + rand(v.z));
}