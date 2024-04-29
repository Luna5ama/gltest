uint packU11(float v) {
    return clamp(uint(v * 2047.0), 0u, 2047u);
}

float unpackU11(uint v) {
    return float(v) / 2047.0;
}

uint packU10(float v) {
    return clamp(uint(v * 1023.0), 0u, 1023u);
}

float unpackU10(uint v) {
    return float(v) / 1023.0;
}