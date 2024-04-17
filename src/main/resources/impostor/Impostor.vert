#version 460 core

layout(std140) uniform GlobalCamera {
    mat4 projection;
    mat4 modelView;
    mat4 combined;
    mat4 invProjection;
    mat4 invModelView;
    mat4 invCombined;
    vec4 screenSize;
    vec2 cameraRotation;
    vec2 planeZ;
    int gFrameIndex;
};

const vec3 VERTICES[8] = vec3[8](
    vec3(-1.0, -1.0, -1.0),
    vec3( 1.0, -1.0, -1.0),
    vec3( 1.0,  1.0, -1.0),
    vec3(-1.0,  1.0, -1.0),
    vec3(-1.0, -1.0,  1.0),
    vec3( 1.0, -1.0,  1.0),
    vec3( 1.0,  1.0,  1.0),
    vec3(-1.0,  1.0,  1.0)
);

const vec3 NORMALS[6] = vec3[6](
    vec3( 0.0,  0.0, -1.0),
    vec3( 0.0,  0.0,  1.0),
    vec3(-1.0,  0.0,  0.0),
    vec3( 1.0,  0.0,  0.0),
    vec3( 0.0,  1.0,  0.0),
    vec3( 0.0, -1.0,  0.0)
);

const int INDICES[36] = int[36](
    0, 1, 2, 2, 3, 0,
    1, 5, 6, 6, 2, 1,
    5, 4, 7, 7, 6, 5,
    4, 0, 3, 3, 7, 4,
    3, 2, 6, 6, 7, 3,
    4, 5, 1, 1, 0, 4
);

uniform vec3 uvCameraPosition;

out flat vec3 fNormal;
out vec3 fSurfacePos;

void main() {
    vec3 vertexPos = VERTICES[INDICES[gl_VertexID]];
    fSurfacePos = vertexPos;
    fNormal = NORMALS[gl_VertexID / 6];
    gl_Position = projection * modelView * vec4(vertexPos - uvCameraPosition, 1.0);
}
