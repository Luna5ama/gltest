#version 120

vec3 toViewSpace(vec2 texCoord, float viewZ, mat4 proj, mat4 projInv) {
    vec2 ndcXY = texCoord * 2.0 - 1.0;
    float clipW = viewZ * proj[2][3];
    vec2 clipXY = ndcXY * clipW;
    vec2 viewXY = clipXY * vec2(projInv[0][0], projInv[1][1]);
    return vec3(viewXY, viewZ);
}

void main() {
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
