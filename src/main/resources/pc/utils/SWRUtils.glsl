vec3 swr_barycentric(vec2 pa, vec4 v, vec3 d, float denom) {
    float d20p = dot(pa, v.xy);
    float d21p = dot(pa, v.zw);

    vec3 uvw;
    uvw.y = (d.z * d20p - d.y * d21p) * denom;
    uvw.z = (d.x * d21p - d.y * d20p) * denom;
    uvw.x = 1.0 - uvw.y - uvw.z;
    return uvw;
}

void swr_calcBarycentricGradMxMy(vec4 screenBound, vec2 invTexelN, vec2 a, vec2 b, vec2 c, out vec3 bcBase, out vec3 bcMx, out vec3 bcMy, out vec3 b1Dx, out vec3 b1Dy) {
    vec4 v = vec4(b, c) - vec4(a, a);
    vec3 d = vec3(
        dot(v.xy, v.xy),
        dot(v.xy, v.zw),
        dot(v.zw, v.zw)
    );

    float denom = 1.0 / (d.x * d.z - d.y * d.y);
    bcBase = swr_barycentric(screenBound.xy - a.xy, v, d, denom);
    bcMx = swr_barycentric(screenBound.zy - a.xy, v, d, denom);
    bcMy = swr_barycentric(screenBound.xw - a.xy, v, d, denom);

    b1Dx = (bcMx - bcBase) * invTexelN.x;
    b1Dy = (bcMy - bcBase) * invTexelN.y;
}

void swr_calcBarycentricGrad(vec4 screenBound, vec2 invTexelN, vec2 a, vec2 b, vec2 c, out vec3 bcBase, out vec3 b1Dx, out vec3 b1Dy) {
    vec4 v = vec4(b, c) - vec4(a, a);
    vec3 d = vec3(
        dot(v.xy, v.xy),
        dot(v.xy, v.zw),
        dot(v.zw, v.zw)
    );

    float denom = 1.0 / (d.x * d.z - d.y * d.y);
    bcBase = swr_barycentric(screenBound.xy - a.xy, v, d, denom);
    vec3 bcMx = swr_barycentric(screenBound.zy - a.xy, v, d, denom);
    vec3 bcMy = swr_barycentric(screenBound.xw - a.xy, v, d, denom);

    b1Dx = (bcMx - bcBase) * invTexelN.x;
    b1Dy = (bcMy - bcBase) * invTexelN.y;
}

void swr_calcAttribGrad(vec2 invTexelN, vec3 bcBase, vec3 bcMx, vec3 bcMy, vec3 attribs, out float attribBase, out float attribDx, out float attribDy) {
    attribBase = dot(attribs, bcBase);
    float attribMx = dot(attribs, bcMx);
    float attribMy = dot(attribs, bcMy);
    attribDx = (attribMx - attribBase) * invTexelN.x;
    attribDy = (attribMy - attribBase) * invTexelN.y;
}

void swr_calcAttribGrad(vec2 invTexelN, vec3 bcBase, vec3 bcMx, vec3 bcMy, vec3 attribsX, vec3 attribsY, out vec2 attribBase, out vec2 attribDx, out vec2 attribDy) {
    attribBase = vec2(dot(attribsX, bcBase), dot(attribsY, bcBase));
    vec2 attribMx = vec2(dot(attribsX, bcMx), dot(attribsY, bcMx));
    vec2 attribMy = vec2(dot(attribsX, bcMy), dot(attribsY, bcMy));
    attribDx = (attribMx - attribBase) * invTexelN.x;
    attribDy = (attribMy - attribBase) * invTexelN.y;
}

void swr_calcAttribGrad(vec2 invTexelN, vec3 bcBase, vec3 bcMx, vec3 bcMy, vec3 attribsX, vec3 attribsY, vec3 attribsZ, out vec3 attribBase, out vec3 attribDx, out vec3 attribDy) {
    attribBase = vec3(dot(attribsX, bcBase), dot(attribsY, bcBase), dot(attribsZ, bcBase));
    vec3 attribMx = vec3(dot(attribsX, bcMx), dot(attribsY, bcMx), dot(attribsZ, bcMx));
    vec3 attribMy = vec3(dot(attribsX, bcMy), dot(attribsY, bcMy), dot(attribsZ, bcMy));
    attribDx = (attribMx - attribBase) * invTexelN.x;
    attribDy = (attribMy - attribBase) * invTexelN.y;
}

void swr_calcAttribGrad(vec2 invTexelN, vec3 bcBase, vec3 bcMx, vec3 bcMy, vec3 attribsX, vec3 attribsY, vec3 attribsZ, vec3 attribsW, out vec4 attribBase, out vec4 attribDx, out vec4 attribDy) {
    attribBase = vec4(dot(attribsX, bcBase), dot(attribsY, bcBase), dot(attribsZ, bcBase), dot(attribsW, bcBase));
    vec4 attribMx = vec4(dot(attribsX, bcMx), dot(attribsY, bcMx), dot(attribsZ, bcMx), dot(attribsW, bcMx));
    vec4 attribMy = vec4(dot(attribsX, bcMy), dot(attribsY, bcMy), dot(attribsZ, bcMy), dot(attribsW, bcMy));
    attribDx = (attribMx - attribBase) * invTexelN.x;
    attribDy = (attribMy - attribBase) * invTexelN.y;
}

const uvec4 DECODE_POS_SHIFT = uvec4(0u, 16u, 0u, 16u);
const uvec4 DECODE_POS_MASK = uvec4(0xFFFFu);
vec4 decodePos(uvec2 data) {
    return vec4(uvec4((data.xxyy >> DECODE_POS_SHIFT) & DECODE_POS_MASK));
}